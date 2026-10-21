package com.freeturn.app.data

import com.freeturn.app.data.config.BypassRuleSet
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RuleTunnelHttpTest {
    private fun input(text: String) = text.byteInputStream(Charsets.US_ASCII)

    @Test fun `download inbound is authenticated loopback and precedes bypass and partial routes`() {
        val base = JSONObject().put("inbounds", JSONArray().put(JSONObject().put("tag", "tun-in")))
            .put("route", JSONObject().put("final", "direct").put("rules", JSONArray()
                .put(JSONObject().put("rule_set", "ru").put("outbound", "direct"))
                .put(JSONObject().put("ip_cidr", "10.0.0.0/8").put("outbound", "proxy"))))
        val result = JSONObject(RuleDownloadTunnel.configure(base.toString(), RuleDownloadTunnel.Endpoint(32123, "test-secret")))
        val inbound = result.getJSONArray("inbounds").getJSONObject(1)
        assertEquals("127.0.0.1", inbound.getString("listen"))
        assertEquals(32123, inbound.getInt("listen_port"))
        assertEquals("test-secret", inbound.getJSONArray("users").getJSONObject(0).getString("password"))
        val rules = result.getJSONObject("route").getJSONArray("rules")
        assertEquals(RuleDownloadTunnel.TAG, rules.getJSONObject(0).getJSONArray("inbound").getString(0))
        assertEquals("proxy", rules.getJSONObject(0).getString("outbound"))
        assertEquals("direct", rules.getJSONObject(1).getString("outbound"))
        assertEquals(3, rules.length())
    }

    @Test fun `CONNECT authenticates only the local proxy and preserves remote DNS names`() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 3000
            val executor = Executors.newSingleThreadExecutor()
            try {
                val request = executor.submit<String> {
                    server.accept().use { socket ->
                        socket.soTimeout = 3000
                        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val lines = mutableListOf<String>()
                        while (true) { val line = reader.readLine(); if (line.isNullOrEmpty()) break; lines += line }
                        socket.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
                        lines.joinToString("\n")
                    }
                }
                RuleTunnelHttp.connect(RuleDownloadTunnel.Endpoint(server.localPort, "private-test"), "no-dns.invalid", 443).close()
                val headers = request.get(3, TimeUnit.SECONDS)
                assertTrue(headers.startsWith("CONNECT no-dns.invalid:443 HTTP/1.1"))
                val auth = Base64.getEncoder().encodeToString("${RuleDownloadTunnel.USER}:private-test".toByteArray())
                assertTrue(headers.contains("Proxy-Authorization: Basic $auth"))
            } finally { executor.shutdownNow() }
        }
    }
    @Test fun `HTTPS is required before attempting any connection`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuleTunnelHttp.download(RuleDownloadTunnel.Endpoint(1, "test"), "http://no-dns.invalid/rules")
        }
    }
    @Test fun `fixed length responses are read exactly`() {
        val stream = input("HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabcEXTRA")
        val headers = RuleTunnelHttp.readHeaders(stream)
        assertEquals("abc", RuleTunnelHttp.readBody(stream, headers).toString(Charsets.US_ASCII))
    }
    @Test fun `chunked response supports extensions and trailers`() {
        val stream = input("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2;x=y\r\nab\r\n1\r\nc\r\n0\r\nX-Final: yes\r\n\r\n")
        assertEquals("abc", RuleTunnelHttp.readBody(stream, RuleTunnelHttp.readHeaders(stream)).toString(Charsets.US_ASCII))
    }
    @Test fun `truncated responses cannot replace the stored rule file`() {
        val fixed = RuleTunnelHttp.Headers(200, mapOf("content-length" to "10"))
        assertThrows(EOFException::class.java) { RuleTunnelHttp.readBody(input("short"), fixed) }
        val chunked = RuleTunnelHttp.Headers(200, mapOf("transfer-encoding" to "chunked"))
        assertThrows(EOFException::class.java) { RuleTunnelHttp.readBody(input("5\r\nab"), chunked) }
    }
    @Test fun `oversize and ambiguous responses are rejected before body allocation`() {
        for (fields in listOf(
            mapOf("content-length" to (BypassRuleSet.MAX_BYTES + 1).toString()),
            mapOf("content-length" to "-1"),
            mapOf("content-length" to "3", "transfer-encoding" to "chunked"),
            mapOf("content-encoding" to "gzip"))) {
            assertThrows(IllegalArgumentException::class.java) { RuleTunnelHttp.readBody(input("abc"), RuleTunnelHttp.Headers(200, fields)) }
        }
    }
    @Test fun `headers and conflicting lengths are bounded`() {
        assertThrows(IllegalArgumentException::class.java) {
            RuleTunnelHttp.readHeaders(input("HTTP/1.1 200 OK\r\nContent-Length: 3\r\nContent-Length: 4\r\n\r\n"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuleTunnelHttp.readHeaders(input("HTTP/1.1 200 OK\r\nX-Header: " + "a".repeat(40000) + "\r\n\r\n"))
        }
    }
    @Test fun `clearing an old service cannot remove a newer tunnel endpoint`() {
        val old = RuleDownloadTunnel.Endpoint(1, "old")
        val current = RuleDownloadTunnel.Endpoint(2, "new")
        try {
            RuleDownloadTunnel.publish(current)
            RuleDownloadTunnel.clear(old)
            assertSame(current, RuleDownloadTunnel.endpoint)
            RuleDownloadTunnel.clear(current)
            assertNull(RuleDownloadTunnel.endpoint)
        } finally { RuleDownloadTunnel.clear(current) }
    }

    @Test fun `APK streaming exceeds rule limit without buffering the full body`() {
        val size = 8L * 1024 * 1024
        val source = object : java.io.InputStream() {
            var remaining = size
            override fun read(): Int = if (remaining-- > 0) 42 else -1
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (remaining == 0L) return -1
                val n = minOf(remaining, length.toLong()).toInt()
                java.util.Arrays.fill(buffer, offset, offset + n, 42.toByte())
                remaining -= n
                return n
            }
        }
        var received = 0L
        var largestWrite = 0
        var reported = 0L
        val sink = object : java.io.OutputStream() {
            override fun write(b: Int) { received++ }
            override fun write(b: ByteArray, off: Int, len: Int) {
                assertTrue(b[off] == 42.toByte() && b[off + len - 1] == 42.toByte())
                received += len
                largestWrite = maxOf(largestWrite, len)
            }
        }
        RuleTunnelHttp.copyBody(source, RuleTunnelHttp.Headers(200, mapOf("content-length" to "$size")),
            sink, size, progress = { bytes, total ->
                assertEquals(size, total)
                assertTrue(bytes >= reported)
                reported = bytes
            })
        assertEquals(size, received)
        assertEquals(size, reported)
        assertTrue(largestWrite <= 32768)
    }

    @Test fun `streaming bounds apply to unknown and chunked lengths`() {
        for ((body, fields) in listOf(
            "12345" to emptyMap(),
            "5\r\n12345\r\n0\r\n\r\n" to mapOf("transfer-encoding" to "chunked"))) {
            assertThrows(IllegalArgumentException::class.java) {
                RuleTunnelHttp.copyBody(input(body), RuleTunnelHttp.Headers(200, fields),
                    java.io.ByteArrayOutputStream(), 4)
            }
        }
    }

    @Test fun `streaming cancellation interrupts copying before the next read`() {
        val sink = java.io.ByteArrayOutputStream()
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            RuleTunnelHttp.copyBody(input("abcdef"), RuleTunnelHttp.Headers(200, mapOf("content-length" to "6")),
                sink, 6, checkActive = { throw java.util.concurrent.CancellationException() })
        }
        assertEquals(0, sink.size())
    }

    @Test fun `repeated GitHub nonframing headers do not reject a valid response`() {
        val stream = input("HTTP/1.1 200 OK\r\nContent-Length: 3\r\nSet-Cookie: a=b\r\nSet-Cookie: c=d\r\n\r\nabc")
        val headers = RuleTunnelHttp.readHeaders(stream)
        assertEquals("abc", RuleTunnelHttp.readBody(stream, headers).toString(Charsets.US_ASCII))
    }
}
