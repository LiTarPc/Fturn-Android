package com.freeturn.app.domain.update

import com.freeturn.app.data.RuleDownloadTunnel
import com.freeturn.app.data.RuleTunnelHttp
import com.freeturn.app.domain.proxy.ProxyServiceState
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI

/** Uses the active VPN for API checks, GitHub redirects and streamed APK data. */
internal class UpdateHttpClient {
    fun text(address: String, checkActive: () -> Unit = {}): String {
        val output = ByteArrayOutputStream()
        copyTo(address, output, 4L * 1024 * 1024, "application/vnd.github+json", checkActive = checkActive)
        return output.toString("UTF-8")
    }

    fun copyTo(address: String, output: OutputStream, limit: Long, accept: String,
               onRoute: (Boolean) -> Unit = {}, progress: (Long, Long?) -> Unit = { _, _ -> },
               checkActive: () -> Unit = {}) {
        val tunnel = selectRoute(RuleDownloadTunnel.endpoint, ProxyServiceState.tunnelActive.value)
        onRoute(tunnel != null)
        if (tunnel != null) {
            RuleTunnelHttp.downloadTo(tunnel, address, output, limit, accept, progress, checkActive)
            return
        }
        var uri = URI(address)
        repeat(6) {
            checkActive()
            require(uri.scheme == "https" && uri.host != null && uri.userInfo == null) { "Downloads require HTTPS" }
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", "Fturn-update")
            try {
                val code = connection.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    uri = uri.resolve(connection.getHeaderField("Location") ?: error("Redirect without Location"))
                } else {
                    check(code == 200) { "Download HTTP $code" }
                    val headers = buildMap {
                        if (connection.contentLengthLong >= 0) put("content-length", connection.contentLengthLong.toString())
                        connection.contentEncoding?.let { put("content-encoding", it) }
                    }
                    // URLConnection already decodes chunk framing on the direct path.
                    connection.inputStream.use {
                        RuleTunnelHttp.copyBody(it, RuleTunnelHttp.Headers(code, headers), output, limit, progress, checkActive)
                    }
                    return
                }
            } finally { connection.disconnect() }
        }
        error("Too many download redirects")
    }

    companion object {
        internal fun selectRoute(endpoint: RuleDownloadTunnel.Endpoint?, vpnActive: Boolean): RuleDownloadTunnel.Endpoint? {
            check(endpoint != null || !vpnActive) { "Канал загрузки VPN недоступен. Переподключите VPN и повторите загрузку." }
            return endpoint
        }
    }
}
