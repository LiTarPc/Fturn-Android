package com.freeturn.app.data

import com.freeturn.app.data.config.BypassRuleSet
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Base64
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Bounded HTTPS GET through an authenticated local CONNECT proxy; never falls back to direct. */
internal object RuleTunnelHttp {
    private const val HEADER_LIMIT = 32768
    internal data class Headers(val status: Int, val values: Map<String, String>)

    fun download(endpoint: RuleDownloadTunnel.Endpoint, address: String): ByteArray {
        val output = ByteArrayOutputStream()
        downloadTo(endpoint, address, output, BypassRuleSet.MAX_BYTES.toLong(), "text/plain")
        return output.toByteArray()
    }

    fun downloadTo(endpoint: RuleDownloadTunnel.Endpoint, address: String, output: OutputStream,
                   maxBytes: Long, accept: String,
                   progress: (Long, Long?) -> Unit = { _, _ -> }, checkActive: () -> Unit = {}) {
        var uri = URI(address)
        repeat(6) {
            checkActive()
            require(uri.scheme == "https" && uri.host != null && uri.userInfo == null) { "Downloads require HTTPS" }
            val port = if (uri.port == -1) 443 else uri.port
            connect(endpoint, uri.host, port).use { tunnel ->
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(tunnel, uri.host, port, true) as SSLSocket
                ssl.use { socket ->
                    socket.soTimeout = 60000
                    socket.sslParameters = socket.sslParameters.apply {
                        endpointIdentificationAlgorithm = "HTTPS"
                        serverNames = listOf(SNIHostName(uri.host))
                    }
                    socket.startHandshake()
                    val target = uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
                    val host = uri.host + if (port == 443) "" else ":$port"
                    socket.outputStream.write(("GET $target HTTP/1.1\r\nHost: $host\r\n" +
                        "User-Agent: Fturn-update\r\nAccept: $accept\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n")
                        .toByteArray(Charsets.US_ASCII))
                    socket.outputStream.flush()
                    val input = socket.inputStream.buffered()
                    val headers = readHeaders(input)
                    if (headers.status in setOf(301, 302, 303, 307, 308)) {
                        uri = uri.resolve(headers.values["location"] ?: error("Redirect without Location"))
                    } else {
                        check(headers.status == 200) { "Download HTTP ${headers.status}" }
                        copyBody(input, headers, output, maxBytes, progress, checkActive)
                        return
                    }
                }
            }
        }
        error("Too many download redirects")
    }

    internal fun connect(endpoint: RuleDownloadTunnel.Endpoint, host: String, port: Int): Socket {
        require(host.isNotBlank() && host.none { it.isWhitespace() || it == '\r' || it == '\n' } && port in 1..65535)
        val socket = Socket()
        try {
            socket.soTimeout = 20000
            socket.connect(InetSocketAddress("127.0.0.1", endpoint.port), 15000)
            val auth = Base64.getEncoder().encodeToString("${RuleDownloadTunnel.USER}:${endpoint.password}".toByteArray(Charsets.UTF_8))
            socket.outputStream.write(("CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n" +
                "Proxy-Authorization: Basic $auth\r\n\r\n").toByteArray(Charsets.US_ASCII))
            socket.outputStream.flush()
            // Do not buffer here: subsequent bytes belong to the TLS socket.
            val response = readHeaders(socket.inputStream)
            check(response.status == 200) { "VPN download channel: HTTP ${response.status}" }
            return socket
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }

    private fun line(input: InputStream, limit: Int = HEADER_LIMIT): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("Truncated HTTP response")
            if (b == 10) return out.toString("US-ASCII").removeSuffix("\r")
            require(out.size() < limit) { "HTTP header too large" }
            out.write(b)
        }
    }

    internal fun readHeaders(input: InputStream): Headers {
        var remaining = HEADER_LIMIT
        val status = line(input, remaining).also { remaining -= it.length + 2 }
        val parts = status.split(' ', limit = 3)
        require(parts.size >= 2 && parts[0] in setOf("HTTP/1.0", "HTTP/1.1")) { "Invalid HTTP response" }
        val code = parts[1].toIntOrNull() ?: error("Invalid HTTP status")
        val fields = linkedMapOf<String, String>()
        while (true) {
            val item = line(input, remaining).also { remaining -= it.length + 2 }
            require(remaining >= 0) { "HTTP headers too large" }
            if (item.isEmpty()) break
            val colon = item.indexOf(':'); require(colon > 0) { "Invalid HTTP header" }
            val key = item.substring(0, colon).lowercase(java.util.Locale.ROOT)
            val value = item.substring(colon + 1).trim()
            if (key in setOf("content-length", "transfer-encoding", "content-encoding", "location")) {
                require(key !in fields || fields[key] == value) { "Conflicting HTTP headers" }
                fields[key] = value
            } else fields[key] = fields[key]?.let { "$it, $value" } ?: value
        }
        return Headers(code, fields)
    }

    internal fun readBody(input: InputStream, headers: Headers): ByteArray = ByteArrayOutputStream().apply {
        copyBody(input, headers, this, BypassRuleSet.MAX_BYTES.toLong())
    }.toByteArray()

    internal fun copyBody(input: InputStream, headers: Headers, output: OutputStream, limit: Long,
                          progress: (Long, Long?) -> Unit = { _, _ -> }, checkActive: () -> Unit = {}) {
        require(limit > 0)
        val encoding = headers.values["content-encoding"]
        require(encoding == null || encoding.equals("identity", true)) { "Unsupported HTTP content encoding" }
        val transfer = headers.values["transfer-encoding"]
        val length = headers.values["content-length"]
        require(transfer == null || length == null) { "Ambiguous HTTP body framing" }
        val total = length?.let { it.toLongOrNull() ?: error("Invalid HTTP content length") }
        require(total == null || total in 0..limit) { "Download exceeds size limit" }
        var copied = 0L
        val buffer = ByteArray(32768)
        progress(0, total)
        fun copy(count: Long) {
            require(count >= 0 && count <= limit - copied) { "Download exceeds size limit" }
            var left = count
            while (left > 0) {
                checkActive()
                val n = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
                if (n < 0) throw EOFException("Truncated download")
                if (n == 0) continue
                output.write(buffer, 0, n); left -= n; copied += n
                progress(copied, total)
            }
        }
        when {
            transfer != null -> {
                require(transfer.equals("chunked", true)) { "Unsupported HTTP transfer encoding" }
                while (true) {
                    checkActive()
                    val size = line(input, 1024).substringBefore(';').trim().toLongOrNull(16)
                        ?: error("Invalid HTTP chunk size")
                    if (size == 0L) {
                        var trailerBytes = 0
                        while (true) {
                            val trailer = line(input); trailerBytes += trailer.length + 2
                            require(trailerBytes <= HEADER_LIMIT) { "HTTP trailers too large" }
                            if (trailer.isEmpty()) break
                        }
                        break
                    }
                    copy(size)
                    require(line(input, 2).isEmpty()) { "Invalid HTTP chunk delimiter" }
                }
            }
            total != null -> copy(total)
            else -> while (true) {
                checkActive()
                val n = input.read(buffer)
                if (n < 0) break
                require(n.toLong() <= limit - copied) { "Download exceeds size limit" }
                output.write(buffer, 0, n); copied += n
                progress(copied, null)
            }
        }
    }
}
