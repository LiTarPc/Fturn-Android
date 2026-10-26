package com.freeturn.app.domain.proxy

import org.junit.Assert.*
import org.junit.Test
import java.io.DataInputStream
import java.net.*
import java.util.Base64

class RawProxyServerTest {
    private fun socket(port: Int) = Socket("127.0.0.1", port).apply { soTimeout = 3000 }
    private fun headers(input: java.io.InputStream): String {
        val data = StringBuilder()
        while (!data.endsWith("\r\n\r\n")) { val c = input.read(); check(c >= 0); data.append(c.toChar()) }
        return data.toString()
    }
    private fun authenticate(client: Socket, password: String = "password") {
        val output = client.getOutputStream(); val input = DataInputStream(client.getInputStream())
        output.write(byteArrayOf(5, 1, 2)); assertEquals(5, input.readUnsignedByte()); assertEquals(2, input.readUnsignedByte())
        output.write(byteArrayOf(1, 4) + "user".toByteArray() + byteArrayOf(password.length.toByte()) + password.toByteArray())
        assertEquals(1, input.readUnsignedByte()); assertEquals(if (password == "password") 0 else 1, input.readUnsignedByte())
    }
    @Test fun `HTTP rejects missing credentials before destination access`() {
        RawProxyServer("127.0.0.1", 0, "user", "password", true).use { proxy ->
            proxy.start()
            socket(proxy.localPort).use { client ->
                client.getOutputStream().write("CONNECT invalid.invalid:443 HTTP/1.1\r\n\r\n".toByteArray())
                assertTrue(headers(client.getInputStream()).startsWith("HTTP/1.1 407"))
            }
        }
    }
    @Test fun `HTTP authenticated tunnel preserves response after upload half close`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { target ->
            val thread = Thread {
                target.accept().use { peer ->
                    val data = ByteArray(4); DataInputStream(peer.getInputStream()).readFully(data)
                    check(String(data) == "ping"); check(peer.getInputStream().read() == -1)
                    peer.getOutputStream().write("pong".toByteArray())
                }
            }.apply { isDaemon = true; start() }
            RawProxyServer("127.0.0.1", 0, "user", "password", true).use { proxy ->
                proxy.start()
                socket(proxy.localPort).use { client ->
                    val auth = Base64.getEncoder().encodeToString("user:password".toByteArray())
                    client.getOutputStream().write("CONNECT 127.0.0.1:${target.localPort} HTTP/1.1\r\nProxy-Authorization: Basic $auth\r\n\r\n".toByteArray())
                    assertTrue(headers(client.getInputStream()).startsWith("HTTP/1.1 200"))
                    client.getOutputStream().write("ping".toByteArray()); client.shutdownOutput()
                    assertEquals("pong", String(client.getInputStream().readBytes()))
                }
            }
            thread.join(3000); assertFalse(thread.isAlive)
        }
    }
    @Test fun `SOCKS requires authentication and rejects an invalid password`() {
        RawProxyServer("127.0.0.1", 0, "user", "password", false).use { proxy ->
            proxy.start()
            socket(proxy.localPort).use { client ->
                client.getOutputStream().write(byteArrayOf(5, 1, 0))
                val input = DataInputStream(client.getInputStream())
                assertEquals(5, input.readUnsignedByte()); assertEquals(255, input.readUnsignedByte())
            }
            socket(proxy.localPort).use { client -> authenticate(client, "incorrect"); assertEquals(-1, client.getInputStream().read()) }
        }
    }
    @Test fun `SOCKS UDP association relays payload and closes with its control connection`() {
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { target ->
            target.soTimeout = 3000
            val thread = Thread {
                val request = DatagramPacket(ByteArray(100), 100); target.receive(request)
                target.send(DatagramPacket("reply".toByteArray(), 5, request.socketAddress))
            }.apply { isDaemon = true; start() }
            RawProxyServer("127.0.0.1", 0, "user", "password", false).use { proxy ->
                proxy.start()
                socket(proxy.localPort).use { client ->
                    authenticate(client)
                    client.getOutputStream().write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
                    val response = ByteArray(10); DataInputStream(client.getInputStream()).readFully(response)
                    assertEquals(0, response[1].toInt())
                    val relay = ((response[8].toInt() and 255) shl 8) or (response[9].toInt() and 255)
                    DatagramSocket().use { udp ->
                        udp.soTimeout = 3000
                        udp.send(DatagramPacket(byteArrayOf(0), 1, InetAddress.getLoopbackAddress(), relay))
                        val payload = byteArrayOf(0, 0, 0, 1, 127, 0, 0, 1, (target.localPort shr 8).toByte(), target.localPort.toByte()) + "query".toByteArray()
                        udp.send(DatagramPacket(payload, payload.size, InetAddress.getLoopbackAddress(), relay))
                        val reply = DatagramPacket(ByteArray(100), 100); udp.receive(reply)
                        assertEquals("reply", String(reply.data, 10, reply.length - 10))
                    }
                }
            }
            thread.join(3000); assertFalse(thread.isAlive)
        }
    }
}
