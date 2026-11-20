package com.freeturn.app.domain.router

import android.content.Context
import com.freeturn.app.domain.ssh.SSHManager
import java.net.UnknownHostException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Credentials live only in the caller's operation; only verified host fingerprints are persisted. */
class RouterSetupManager(private val context: Context) {
    private val hosts = context.getSharedPreferences("router_setup_hosts", Context.MODE_PRIVATE)
    private val ssh = SSHManager()
    private val files = listOf("fturn-fou-auto.sh", "fturn-fou-auto.init", "fturn-router-common.sh", "fturn-router", "install-fturn-router.sh")

    suspend fun run(host: String, port: Int, user: String, password: String, key: String, reset: Boolean): String = withContext(Dispatchers.IO) {
        require(host.isNotBlank() && port in 1..65535 && user.isNotBlank()) { "Укажите адрес, порт и пользователя SSH" }
        require(reset || key.matches(Regex("[0-9a-fA-F]{64}"))) { "Некорректный код привязки" }
        val target = MessageDigest.getInstance("SHA-256").digest("$host:$port".toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        val known = hosts.getString(target, null)
        val sockets = try {
            routerSockets(host)
        } catch (e: UnknownHostException) {
            throw IllegalArgumentException("Не удалось определить адрес роутера. Проверьте адрес или введите LAN IP.", e)
        }
        val probe = ssh.executeSilentCommand(host, port, user, password,
            """test "${'$'}(id -u)" = 0 && test -r /etc/openwrt_release && echo FTURN_SSH_OK""", knownFingerprint = known, socketFactory = sockets)
        check(probe.lines().any { it.trim() == "FTURN_SSH_OK" }) {
            if (probe.startsWith("ERROR:")) sshError(probe, password)
            else "SSH подключён, но роутер не подтвердил OpenWrt и права root."
        }
        val fingerprint = checkNotNull(ssh.lastSeenFingerprint)
        check(hosts.edit().putString(target, fingerprint).commit()) { "Не удалось сохранить отпечаток SSH роутера" }
        val script = if (reset) {
            "test -x /usr/sbin/fturn-router || { echo FTURN_HELPER_MISSING; exit 1; }\n/usr/sbin/fturn-router reset\n"
        } else {
            val assets = files.associateWith { name -> context.assets.open("router-setup/$name").bufferedReader().use { it.readText() } }
            RouterSetupScript.install(assets, key)
        }
        val result = ssh.executeWithStdin(host, port, user, password, "sh -s", script,
            knownFingerprint = fingerprint, execTimeoutMs = 300_000, socketFactory = sockets)
        val marker = if (reset) "FTURNRESET_OK" else "FTURNINSTALL_OK"
        check(result.lines().any { it.trim() == marker }) {
            val action = if (reset) "Сброс на роутере" else "Установка помощника"
            when {
                result.startsWith("ERROR:") -> "$action. ${sshError(result, password)}"
                reset && result.lineSequence().any { it.trim() == "FTURN_HELPER_MISSING" } ->
                    "На роутере не найден помощник Fturn с поддержкой сброса. Проверьте адрес роутера и версию помощника."
                else -> {
                    val detail = hideSecret(hideSecret(result, password), key).takeLast(1500)
                    "$action не завершён. ${detail.ifBlank { "Роутер не прислал подтверждение." }}"
                }
            }
        }
        if (reset) "Настройки Fturn на роутере сброшены. Обычная маршрутизация восстановлена."
        else "Помощник установлен. Включите USB-модем Android и VPN Fturn. Подключение и LAN определяются автоматически."
    }
    private fun hideSecret(message: String, secret: String): String =
        if (secret.isEmpty()) message else message.replace(secret, "***")

    private fun sshError(result: String, password: String): String {
        val detail = hideSecret(result.removePrefix("ERROR:").trim(), password).take(600)
        val hint = when {
            detail.contains("Auth fail", ignoreCase = true) || detail.contains("Auth cancel", ignoreCase = true) ->
                "SSH отклонил вход. Проверьте имя пользователя и снова введите пароль роутера."
            detail.contains("refused", ignoreCase = true) || detail.contains("ECONNREFUSED") ->
                "Роутер отклонил соединение. Проверьте порт и включён ли SSH."
            detail.contains("timed out", ignoreCase = true) || detail.contains("timeout", ignoreCase = true) ||
                detail.contains("unreachable", ignoreCase = true) || detail.contains("no route", ignoreCase = true) ->
                "Роутер недоступен по SSH. Проверьте подключение телефона к сети роутера и его адрес."
            else -> "Ошибка SSH."
        }
        return if (detail.isBlank()) hint else "$hint\n$detail"
    }

    // Bind only these SSH sockets to the directly connected LAN, even while cellular/VPN is default.
    private fun routerSockets(host: String): com.jcraft.jsch.SocketFactory {
        val address = java.net.InetAddress.getByName(host)
        val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
        val network = connectivity.allNetworks.firstOrNull { candidate ->
            val caps = connectivity.getNetworkCapabilities(candidate)
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == false &&
                connectivity.getLinkProperties(candidate)?.routes?.any { !it.isDefaultRoute && it.matches(address) } == true
        }
        return object : com.jcraft.jsch.SocketFactory {
            override fun createSocket(host: String, port: Int): java.net.Socket {
                val socket = java.net.Socket()
                try {
                    network?.bindSocket(socket)
                    socket.connect(java.net.InetSocketAddress(address, port), 5_000)
                    return socket
                } catch (e: Exception) { socket.close(); throw e }
            }
            override fun getInputStream(socket: java.net.Socket) = socket.getInputStream()
            override fun getOutputStream(socket: java.net.Socket) = socket.getOutputStream()
        }
    }

}
