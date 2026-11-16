package com.freeturn.app.testing

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import com.freeturn.app.data.config.RouterTunnelConfig
import io.nekohasekai.libbox.*
import java.lang.reflect.Proxy
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/** Rootless FOU receiver and local echo targets. No Android TUN or saved VPN profile is used. */
class RouterFouInstrumentation : Instrumentation() {
    private var duration = 180_000L
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val alive = AtomicBoolean(true)
        var command: CommandServer? = null
        var udp: DatagramSocket? = null
        var tcp: ServerSocket? = null
        val result = Bundle()
        var code = Activity.RESULT_CANCELED
        val stop = targetContext.filesDir.resolve("fou-acceptance-stop")
        stop.delete()
        try {
            udp = DatagramSocket(40553, InetAddress.getByName("127.0.0.1"))
            val echoUdp = udp
            Thread {
                val buffer = ByteArray(4096)
                while (alive.get()) {
                    try { val packet = DatagramPacket(buffer, buffer.size); echoUdp.receive(packet); echoUdp.send(packet)
                    } catch (_: SocketException) { break }
                }
            }.start()
            tcp = ServerSocket(40553, 8, InetAddress.getByName("127.0.0.1"))
            val echoTcp = tcp
            Thread {
                while (alive.get()) {
                    try {
                        val client = echoTcp.accept()
                        Thread { client.use { socket -> socket.getInputStream().copyTo(socket.getOutputStream()) } }.start()
                    } catch (_: SocketException) { break }
                }
            }.start()
            val base = targetContext.cacheDir.resolve("fou-acceptance").apply { mkdirs() }
            Libbox.setup(SetupOptions().apply {
                basePath = base.absolutePath; workingPath = base.absolutePath; tempPath = base.absolutePath
                fixAndroidStack = true
            })
            val config = RouterTunnelConfig(enabled = true, automatic = true, automaticLan = true, pairingKey = "42".repeat(32)).configure("""
                {"log":{"level":"warn"},"inbounds":[],"outbounds":[{"type":"direct","tag":"direct"}],
                 "route":{"auto_detect_interface":false,"rules":[{"ip_cidr":["198.51.100.1/32"],"action":"route","outbound":"direct","override_address":"127.0.0.1"}],"final":"direct"}}
            """.trimIndent())
            Libbox.checkConfig(config)
            command = Libbox.newCommandServer(stub(CommandServerHandler::class.java), stub(PlatformInterface::class.java))
            command.start()
            command.startOrReloadService(config, OverrideOptions())
            sendStatus(0, Bundle().apply { putString("stream", "READY_FOU control=40081 udp=40080 echo=40553 no_TUN\n") })
            val until = System.currentTimeMillis() + duration
            while (!stop.exists() && System.currentTimeMillis() < until) Thread.sleep(200)
            result.putString("stream", "PASS Android FOU receiver lifecycle without VPN TUN\n")
            code = Activity.RESULT_OK
        } catch (e: Throwable) {
            result.putString("stream", "FAIL ${e.javaClass.simpleName}: ${e.message}\n${e.stackTraceToString()}\n")
        } finally {
            alive.set(false); udp?.close(); tcp?.close()
            runCatching { command?.closeService() }; runCatching { command?.close() }; stop.delete()
        }
        finish(code, result)
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> stub(type: Class<T>): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Void.TYPE -> null
            String::class.java -> ""
            else -> if (method.returnType.isInterface) stub(method.returnType) else null
        }
    } as T
}
