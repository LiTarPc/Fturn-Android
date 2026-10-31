package com.freeturn.app.domain.captcha

import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** No response body, URL, bearer or exception message from IPC may enter diagnostics. */
class CaptchaIpcClient(private val generation: String, private val token: String, private val pagePort: Int? = 8765) : AutoCloseable {
    enum class Failure { UNAVAILABLE, STALE, INVALID_RESPONSE }
    class IpcException(val failure: Failure) : Exception(failure.name)
    class Descriptor(val event: CaptchaProtocol.Challenge, val url: String) {
        override fun toString() = "CaptchaDescriptor(${event.requestId}, ${event.mode})"
    }
    private val closed = AtomicBoolean(false)
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    fun fetch(event: CaptchaProtocol.Challenge): Descriptor {
        val json = try { JSONObject(request(event.ipcPort, "/v1/captcha/${event.requestId}")) }
            catch (e: IpcException) { throw e }
            catch (_: Exception) { throw IpcException(Failure.INVALID_RESPONSE) }
        return try {
            if (json.getString("generation") != generation || json.getString("request_id") != event.requestId ||
                json.getString("mode") != event.mode.name.lowercase() || json.getLong("expires_at_ms") != event.expiresAt ||
                event.expiresAt <= System.currentTimeMillis()) throw IpcException(Failure.STALE)
            val url = json.getString("url")
            if (!isLocalPage(url, pagePort ?: URI(url).port)) throw IpcException(Failure.INVALID_RESPONSE)
            Descriptor(event, url)
        } catch (e: IpcException) { throw e }
        catch (_: Exception) { throw IpcException(Failure.INVALID_RESPONSE) }
    }

    fun report(event: CaptchaProtocol.Challenge, status: String, reason: String) {
        require(status in setOf("available", "unavailable", "manual_requested"))
        require(reason in setOf("host_unavailable", "renderer_gone", "load_failed", "challenge_expired", "user_requested", ""))
        request(event.ipcPort, "/v1/captcha/${event.requestId}/host", JSONObject()
            .put("generation", generation).put("status", status).put("reason", reason).toString())
    }

    fun resume(port: Int) {
        request(port, "/v1/auth/resume", JSONObject().put("generation", generation).toString())
    }

    private fun request(port: Int, path: String, body: String? = null): String {
        if (closed.get()) throw IpcException(Failure.STALE)
        require(port in 1..65535)
        val connection = URL("http://127.0.0.1:$port$path").openConnection(Proxy.NO_PROXY) as HttpURLConnection
        connections.add(connection)
        try {
            if (closed.get()) throw IpcException(Failure.STALE)
            connection.apply {
                connectTimeout = 1500; readTimeout = 2500
                instanceFollowRedirects = false; useCaches = false
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("X-Fturn-Generation", generation)
                setRequestProperty("Cache-Control", "no-store")
                if (body != null) {
                    requestMethod = "POST"; doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    setFixedLengthStreamingMode(bytes.size)
                    outputStream.use { it.write(bytes) }
                }
            }
            when (connection.responseCode) {
                200, 204 -> Unit
                409, 410 -> throw IpcException(Failure.STALE)
                else -> throw IpcException(Failure.UNAVAILABLE)
            }
            if (connection.responseCode == 204) return ""
            val bytes = connection.inputStream.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(2048)
                while (buffer.size() <= 16 * 1024) {
                    val n = input.read(chunk, 0, minOf(chunk.size, 16 * 1024 + 1 - buffer.size()))
                    if (n < 0) break
                    buffer.write(chunk, 0, n)
                }
                buffer.toByteArray()
            }
            if (bytes.size > 16 * 1024) throw IpcException(Failure.INVALID_RESPONSE)
            return bytes.toString(Charsets.UTF_8)
        } catch (e: IpcException) { throw e }
        catch (_: Exception) { throw IpcException(Failure.UNAVAILABLE) }
        finally { connections.remove(connection); connection.disconnect() }
    }

    override fun close() {
        closed.set(true)
        connections.forEach { it.disconnect() }
        connections.clear()
    }

    companion object {
        fun isLocalPage(url: String, allowedPort: Int = 8765): Boolean = runCatching {
            if (url.length > 8192 || url.any { it == '\r' || it == '\n' }) return false
            val uri = URI(url)
            uri.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost") &&
                uri.port == allowedPort && uri.port in 1..65535 && uri.rawUserInfo == null && uri.fragment == null &&
                uri.rawPath.startsWith("/")
        }.getOrDefault(false)

        fun sameOrigin(first: String, other: String): Boolean = runCatching {
            val a = URI(first); val b = URI(other)
            if (!isLocalPage(other, a.port)) return false
            a.scheme == b.scheme && a.host == b.host && a.port == b.port
        }.getOrDefault(false)
    }
}
