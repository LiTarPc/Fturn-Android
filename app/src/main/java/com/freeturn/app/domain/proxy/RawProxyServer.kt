package com.freeturn.app.domain.proxy

import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Explicit proxy sockets use Android's native RAW VPN. It never owns a TUN. */
class RawProxyServer(
    private val address: String, private val port: Int, private val user: String,
    private val password: String, private val http: Boolean,
    private val protectReply: (DatagramSocket) -> Unit = {},
) : Closeable {
    private val stopped = AtomicBoolean(false)
    private val clients = ConcurrentHashMap.newKeySet<Closeable>()
    private val slots = Semaphore(128)
    private val udpSlots = Semaphore(512)
    private val workers = Executors.newCachedThreadPool()
    private val server = ServerSocket(port, 64, InetAddress.getByName(address))
    internal val localPort get() = server.localPort

    fun start() {
        workers.execute {
            while (!stopped.get()) {
                val client = try { server.accept() } catch (_: Exception) { break }
                if (!slots.tryAcquire()) { client.close(); continue }
                clients += client
                workers.execute {
                    try { client.use { if (http) connectHttp(it) else connectSocks(it) } }
                    catch (_: Exception) { /* A failed explicit request does not stop RAW. */ }
                    finally { clients -= client; slots.release() }
                }
            }
        }
    }

    private fun equal(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    private fun ipv4(host: String): InetAddress = InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }
        ?: throw IllegalArgumentException("RAW proxy requires IPv4")
    private fun target(host: String, port: Int): Socket {
        require(port in 1..65535 && host.isNotBlank() && host.none { it.isWhitespace() })
        check(!stopped.get())
        val resolved = ipv4(host)
        return Socket().also { socket ->
            clients += socket
            try { check(!stopped.get()); socket.connect(InetSocketAddress(resolved, port), 15000); check(!stopped.get()) }
            catch (e: Exception) { clients -= socket; socket.close(); throw e }
        }
    }

    private fun line(input: InputStream, limit: Int): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) throw EOFException()
            if (value == 10) return bytes.toString("US-ASCII").removeSuffix("\r")
            require(bytes.size() < limit) { "Proxy header too large" }
            bytes.write(value)
        }
    }

    private fun connectHttp(client: Socket) {
        client.soTimeout = 15000
        val input = client.getInputStream()
        val request = line(input, 8192).split(' ')
        require(request.size == 3 && request[0] == "CONNECT" && request[2] in setOf("HTTP/1.0", "HTTP/1.1"))
        var remaining = 16384
        var auth: String? = null
        while (true) {
            val header = line(input, remaining); remaining -= header.length + 2
            require(remaining >= 0)
            if (header.isEmpty()) break
            val key = header.substringBefore(':').lowercase()
            if (key == "proxy-authorization") { require(auth == null); auth = header.substringAfter(':').trim() }
        }
        val expected = "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
        if (auth == null || !equal(auth, expected)) {
            client.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\nContent-Length: 0\r\n\r\n".toByteArray())
            return
        }
        val endpoint = request[1]
        val host = endpoint.substringBeforeLast(':')
        val destination = try { target(host, endpoint.substringAfterLast(':').toInt()) }
        catch (_: Exception) { client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray()); return }
        try { destination.use {
            client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            relay(client, destination)
        } } finally { clients -= destination }
    }

    private fun readAddress(input: DataInputStream, type: Int): String = when (type) {
        1 -> InetAddress.getByAddress(ByteArray(4).also(input::readFully)).hostAddress!!
        3 -> String(ByteArray(input.readUnsignedByte()).also(input::readFully), Charsets.US_ASCII)
        4 -> InetAddress.getByAddress(ByteArray(16).also(input::readFully)).hostAddress!!
        else -> throw IllegalArgumentException("Unsupported SOCKS address")
    }
    private fun reply(client: Socket, status: Int, port: Int = 0) {
        client.getOutputStream().write(byteArrayOf(5, status.toByte(), 0, 1, 0, 0, 0, 0, (port shr 8).toByte(), port.toByte()))
    }
    private fun connectSocks(client: Socket) {
        client.soTimeout = 15000
        val input = DataInputStream(client.getInputStream())
        if (input.readUnsignedByte() != 5) return
        val methods = ByteArray(input.readUnsignedByte()).also(input::readFully)
        if (2.toByte() !in methods) { client.getOutputStream().write(byteArrayOf(5, -1)); return }
        client.getOutputStream().write(byteArrayOf(5, 2))
        if (input.readUnsignedByte() != 1) return
        val username = String(ByteArray(input.readUnsignedByte()).also(input::readFully), Charsets.UTF_8)
        val secret = String(ByteArray(input.readUnsignedByte()).also(input::readFully), Charsets.UTF_8)
        if (!equal(username, user) || !equal(secret, password)) { client.getOutputStream().write(byteArrayOf(1, 1)); return }
        client.getOutputStream().write(byteArrayOf(1, 0))
        if (input.readUnsignedByte() != 5) return
        val command = input.readUnsignedByte()
        if (input.readUnsignedByte() != 0) return
        val host = readAddress(input, input.readUnsignedByte())
        val port = input.readUnsignedShort()
        when (command) {
            1 -> {
                val destination = try { target(host, port) } catch (_: Exception) { reply(client, 5); return }
                try { destination.use { reply(client, 0); relay(client, destination) } }
                finally { clients -= destination }
            }
            3 -> associate(client)
            else -> reply(client, 7)
        }
    }

    private fun relay(client: Socket, destination: Socket) {
        clients += destination
        client.soTimeout = 0
        val lastActivity = AtomicLong(System.nanoTime())
        fun pipe(input: InputStream, output: OutputStream) {
            val buffer = ByteArray(16384)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return
                output.write(buffer, 0, count)
                lastActivity.set(System.nanoTime())
            }
        }
        val upload = workers.submit {
            try { pipe(client.getInputStream(), destination.getOutputStream()); destination.shutdownOutput() }
            catch (_: Exception) { runCatching { client.close() }; runCatching { destination.close() } }
        }
        try {
            pipe(destination.getInputStream(), client.getOutputStream())
            client.shutdownOutput()
            while (!upload.isDone && !stopped.get()) {
                try { upload.get(1, java.util.concurrent.TimeUnit.SECONDS) }
                catch (_: java.util.concurrent.TimeoutException) {
                    if (System.nanoTime() - lastActivity.get() > java.util.concurrent.TimeUnit.SECONDS.toNanos(60)) break
                }
            }
        } finally { upload.cancel(true); clients -= destination; runCatching { destination.close() }; runCatching { client.close() } }
    }

    private fun associate(client: Socket) {
        val relay = DatagramSocket(InetSocketAddress(address, 0))
        data class Flow(val socket: DatagramSocket, val last: AtomicLong)
        val flows = ConcurrentHashMap<InetSocketAddress, Flow>()
        clients += relay
        try {
            // Only replies to the authenticated LAN peer bypass the VPN. New
            // destination sockets below remain unprotected and enter RAW.
            protectReply(relay)
            reply(client, 0, relay.localPort)
            client.soTimeout = 0
            val control = workers.submit { try { while (client.getInputStream().read() >= 0) { } } finally { relay.close() } }
            var peer: InetSocketAddress? = null
            try {
                while (!stopped.get() && !relay.isClosed) {
                    val packet = DatagramPacket(ByteArray(65535), 65535)
                    relay.receive(packet)
                    val from = packet.socketAddress as InetSocketAddress
                    if (from.address != client.inetAddress || (peer != null && from != peer)) continue
                    if (packet.length < 4) continue
                    val input = DataInputStream(packet.data.inputStream(0, packet.length))
                    if (input.readUnsignedShort() != 0 || input.readUnsignedByte() != 0) continue
                    val host = try { readAddress(input, input.readUnsignedByte()) } catch (_: Exception) { continue }
                    if (input.available() < 2) continue
                    val port = input.readUnsignedShort(); if (port == 0) continue
                    val endpoint = try { InetSocketAddress(ipv4(host), port) } catch (_: Exception) { continue }
                    val payload = ByteArray(input.available()).also(input::readFully)
                    if (peer == null) peer = from
                    var flow = flows[endpoint]
                    if (flow == null) {
                        if (flows.size >= 128) continue
                        if (!udpSlots.tryAcquire()) continue
                        val upstream = try { DatagramSocket().apply { soTimeout = 1000 } }
                        catch (e: Exception) { udpSlots.release(); throw e }
                        clients += upstream
                        if (stopped.get()) { clients -= upstream; upstream.close(); udpSlots.release(); break }
                        try { upstream.connect(endpoint) }
                        catch (_: Exception) { clients -= upstream; upstream.close(); udpSlots.release(); continue }
                        val state = Flow(upstream, AtomicLong(System.nanoTime()))
                        flow = state
                        flows[endpoint] = state
                        val recipient = from
                        workers.execute {
                            try {
                                while (!upstream.isClosed) {
                                    val response = DatagramPacket(ByteArray(65507), 65507)
                                    try { upstream.receive(response) }
                                    catch (_: java.net.SocketTimeoutException) {
                                        if (System.nanoTime() - state.last.get() > java.util.concurrent.TimeUnit.SECONDS.toNanos(120)) break
                                        continue
                                    }
                                    state.last.set(System.nanoTime())
                                    val ip = endpoint.address.address
                                    val header = byteArrayOf(0, 0, 0, 1) + ip + byteArrayOf((port shr 8).toByte(), port.toByte())
                                    val result = header + response.data.copyOf(response.length)
                                    if (result.size > 65507) continue
                                    relay.send(DatagramPacket(result, result.size, recipient))
                                }
                            } catch (_: Exception) { } finally { flows.remove(endpoint, state); clients -= upstream; upstream.close(); udpSlots.release() }
                        }
                    }
                    flow.last.set(System.nanoTime())
                    flow.socket.send(DatagramPacket(payload, payload.size))
                }
            } finally { control.cancel(true) }
        } finally {
            relay.close(); clients -= relay
            flows.values.forEach { clients -= it.socket; it.socket.close() }
        }
    }

    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { server.close() }
        clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }
}
