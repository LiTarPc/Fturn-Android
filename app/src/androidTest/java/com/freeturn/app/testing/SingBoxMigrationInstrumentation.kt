package com.freeturn.app.testing

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.TunnelTransport
import com.freeturn.app.service.SingBoxVpnService
import io.nekohasekai.libbox.Libbox
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Isolated test APK only. The caller grants VPN permission to the separate test package. */
class SingBoxMigrationInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val context = targetContext
        var service: SingBoxVpnService? = null
        var binding: ServiceConnection? = null
        val result = Bundle()
        var resultCode = Activity.RESULT_CANCELED
        try {
            check(Libbox.version() == "1.14.4")
            sendStatus(0, Bundle().apply { putString("stream", "PASS native sing-box 1.14.4 on Android\n") })
            val rules = JSONArray()
            val cidrs = context.assets.open("rules/ru-aggregated.zone").bufferedReader().useLines { lines ->
                JSONArray(lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList())
            }
            rules.put(JSONObject().put("type", "inline").put("tag", "ru").put("rules", JSONArray()
                .put(JSONObject().put("ip_cidr", cidrs))))
            val base = "vless://11111111-2222-4333-8444-555555555555@vpn.example:443?"
            val key = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA"
            for (query in listOf("type=tcp", "security=tls&sni=front.example&fp=chrome",
                "security=reality&sni=front.example&pbk=$key&sid=0011&flow=xtls-rprx-vision",
                "type=ws&security=tls&sni=front.example&path=%2Fsocket%3Fed%3D2048",
                "type=grpc&security=tls&sni=front.example&alpn=h2&serviceName=test-service")) {
                Libbox.checkConfig(com.freeturn.app.data.config.VlessProfile.parse(base + query).singBoxConfig("127.0.0.1:19000", rules))
            }
            sendStatus(0, Bundle().apply { putString("stream", "PASS VLESS variants with ${cidrs.length()} RU CIDRs\n") })
            val wg = """
                [Interface]
                PrivateKey = AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=
                Address = 10.8.0.2/32
                DNS = 1.1.1.1
                [Peer]
                PublicKey = ISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0+P0A=
                AllowedIPs = 0.0.0.0/0
                Endpoint = 127.0.0.1:19000
            """.trimIndent()
            Libbox.checkConfig(com.freeturn.app.data.config.WireGuardProfile.parse(wg).singBoxConfig("127.0.0.1:19000", rules))
            sendStatus(0, Bundle().apply { putString("stream", "PASS WireGuard endpoint with RU CIDRs\n") })
            val bound = CountDownLatch(1)
            binding = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    service = (binder as SingBoxVpnService.LocalBinder).service
                    bound.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName?) {}
            }
            val intent = Intent(context, SingBoxVpnService::class.java)
            runOnMainSync {
                context.startForegroundService(intent)
                check(context.bindService(intent, binding!!, Context.BIND_AUTO_CREATE))
            }
            check(bound.await(15, TimeUnit.SECONDS)) { "VPN service did not bind" }
            val cfg = ClientConfig(tunnelTransport = TunnelTransport.VLESS,
                vlessUri = base + "type=tcp", localPort = "127.0.0.1:19000",
                bypassRulesEnabled = false, splitTunnelUseDefaults = false)
            repeat(2) { iteration ->
                service!!.startTunnel(cfg)
                Thread.sleep(1000)
                // The VPN excludes its owner. Probe from the shell UID to exercise the TUN.
                val command = "/system/bin/env CLASSPATH=${context.applicationInfo.sourceDir} app_process /system/bin com.freeturn.app.testing.SingBoxNetworkProbe"
                sendStatus(0, Bundle().apply { putString("stream", "RUN shell TUN probe\n") })
                val response = ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
                check(response.contains("FTURN_SB1144_OK")) { "TUN probe failed: ${response.take(1000)}" }
                sendStatus(0, Bundle().apply { putString("stream", "PASS Android TUN payload, cycle ${iteration + 1}\n") })
                service!!.stopTunnel()
            }
            result.putString("stream", "PASS sing-box Android migration: load, configs, TUN payload and reconnect\n")
            resultCode = Activity.RESULT_OK
        } catch (error: Throwable) {
            sendStatus(0, Bundle().apply { putString("stream", "FAIL ${error.message}\n") })
            result.putString("stream", "FAIL ${error.javaClass.simpleName}: ${error.message}\n${error.stackTraceToString()}\n")

        } finally {
            runCatching { service?.stopTunnel() }
            binding?.let { runCatching { context.unbindService(it) } }
            context.stopService(Intent(context, SingBoxVpnService::class.java))
        }
        finish(resultCode, result)
    }
}
