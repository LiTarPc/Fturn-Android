package com.freeturn.app.domain.captcha

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CaptchaIpcClientTest {
    private val generation = "a".repeat(32)
    private val requestId = "b".repeat(32)
    private val token = "c".repeat(64)
    private fun fixture(block: (HttpServer, CaptchaIpcClient) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool { r -> Thread(r).apply { isDaemon = true } }
        server.executor = executor; server.start()
        val client = CaptchaIpcClient(generation, token)
        try { block(server, client) } finally { client.close(); server.stop(0); executor.shutdownNow() }
    }
    private fun event(port: Int, expiry: Long) = CaptchaProtocol.Challenge(generation, port, requestId,
        CaptchaProtocol.Mode.BROWSER, CaptchaProtocol.Phase.PENDING, expiry)

    @Test fun descriptorAndExplicitActionsAreAuthenticatedAndGenerationBound() = fixture { server, client ->
        val expiry = System.currentTimeMillis() + 180_000
        val seen = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.createContext("/v1/") { exchange ->
            if (exchange.requestHeaders.getFirst("Authorization") != "Bearer $token" ||
                exchange.requestHeaders.getFirst("X-Fturn-Generation") != generation) {
                exchange.sendResponseHeaders(401, -1); exchange.close()
            } else {
                seen.add(exchange.requestMethod + " " + exchange.requestURI.path)
                val body = if (exchange.requestMethod == "GET") JSONObject().put("generation", generation)
                    .put("request_id", requestId).put("mode", "browser").put("expires_at_ms", expiry)
                    .put("url", "http://127.0.0.1:8765/check?opaque=private-value").toString().toByteArray() else ByteArray(0)
                exchange.requestBody.use { it.readBytes() }
                exchange.sendResponseHeaders(if (body.isEmpty()) 204 else 200, if (body.isEmpty()) -1 else body.size.toLong())
                if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
                exchange.close()
            }
        }
        val e = event(server.address.port, expiry)
        val page = client.fetch(e)
        assertTrue(page.url.contains("private-value"))
        assertFalse(page.toString().contains("private-value"))
        client.report(e, "manual_requested", "user_requested")
        client.resume(e.ipcPort)
        assertEquals(listOf("GET /v1/captcha/$requestId", "POST /v1/captcha/$requestId/host", "POST /v1/auth/resume"), seen)
    }

    @Test fun externalPageAndWrongGenerationAreRejectedWithoutDisclosingPayload() = fixture { server, client ->
        val expiry = System.currentTimeMillis() + 180_000
        var wrongGeneration = true
        server.createContext("/v1/captcha/") { exchange ->
            val body = JSONObject().put("generation", if (wrongGeneration) "old" else generation)
                .put("request_id", requestId).put("mode", "browser").put("expires_at_ms", expiry)
                .put("url", "https://example.com/private-secret").toString().toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) }; exchange.close()
        }
        for (wrong in listOf(true, false)) {
            wrongGeneration = wrong
            try { client.fetch(event(server.address.port, expiry)); fail("Unsafe descriptor accepted") }
            catch (e: CaptchaIpcClient.IpcException) {
                assertEquals(if (wrong) CaptchaIpcClient.Failure.STALE else CaptchaIpcClient.Failure.INVALID_RESPONSE, e.failure)
                assertFalse(e.toString().contains("private-secret"))
            }
        }
    }

    @Test fun redirectsOversizedBodiesAndLateRequestsFailClosed() = fixture { server, client ->
        var redirect = true
        var unexpectedFollow = false
        server.createContext("/redirected") { exchange -> unexpectedFollow = true; exchange.sendResponseHeaders(204, -1); exchange.close() }
        server.createContext("/v1/captcha/") { exchange ->
            if (redirect) {
                exchange.responseHeaders.add("Location", "/redirected")
                exchange.sendResponseHeaders(302, -1)
            } else {
                val body = ByteArray(18 * 1024) { 'x'.code.toByte() }
                exchange.sendResponseHeaders(200, body.size.toLong())
                runCatching { exchange.responseBody.use { it.write(body) } }
            }
            exchange.close()
        }
        for (isRedirect in listOf(true, false)) {
            redirect = isRedirect
            try { client.fetch(event(server.address.port, System.currentTimeMillis() + 180_000)); fail("Unsafe response accepted") }
            catch (_: CaptchaIpcClient.IpcException) { }
        }
        assertFalse(unexpectedFollow)
        client.close()
        try { client.resume(server.address.port); fail("Closed process accepted a request") }
        catch (e: CaptchaIpcClient.IpcException) { assertEquals(CaptchaIpcClient.Failure.STALE, e.failure) }
    }
}
