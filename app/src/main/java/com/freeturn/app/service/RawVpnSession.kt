package com.freeturn.app.service

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import com.freeturn.app.data.BypassRuleStore
import com.freeturn.app.data.config.ClientConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/** Private same-UID descriptor channel. TUN is attached only after RAW is ready. */
class RawVpnSession(private val service: SingBoxVpnService, val cfg: ClientConfig) : Closeable {
    private val id = UUID.randomUUID().toString()
    val tunName = "fturn-tun-$id"
    val protectName = "fturn-protect-$id"
    private val tunServer = LocalServerSocket(tunName)
    private val protectServer = LocalServerSocket(protectName)
    private val attached = CountDownLatch(1)
    private val transferred = CountDownLatch(1)
    private val workers = Executors.newFixedThreadPool(4)
    private val clients = ConcurrentHashMap.newKeySet<LocalSocket>()
    @Volatile private var closed = false
    @Volatile private var descriptor: ParcelFileDescriptor? = null
    @Volatile private var transferFailure: Throwable? = null
    val rulesFile: File = service.filesDir.resolve("raw-session-$id.json")

    init {
        try {
            val cidrs = linkedSetOf<String>()
            val binary = mutableListOf<String>()
            if (cfg.bypassRulesEnabled) {
                val sets = BypassRuleStore(service).materialize(cfg.bypassRuleSets)
                for (i in 0 until sets.length()) {
                    val rule = sets.getJSONObject(i)
                    if (rule.optString("type") == "inline") {
                        val values = rule.getJSONArray("rules").getJSONObject(0).getJSONArray("ip_cidr")
                        for (j in 0 until values.length()) {
                            val value = values.getString(j)
                            if (':' !in value) cidrs += value // RAW deliberately blocks IPv6.
                        }
                    } else binary += rule.getString("path")
                }
            }
            rulesFile.writeText(JSONObject().put("version", 1).put("enabled", cfg.bypassRulesEnabled)
                .put("ru", false).put("sites", JSONArray()).put("apps", JSONArray())
                .put("cidrs", JSONArray(cidrs.toList())).put("srs_files", JSONArray(binary)).toString())
            workers.execute {
                try {
                    tunServer.accept().use { client ->
                        require(client.peerCredentials.uid == Process.myUid()) { "TUN peer UID mismatch" }
                        clients += client
                        try {
                            attached.await()
                            check(!closed) { "RAW session closed" }
                            val fd = checkNotNull(descriptor)
                            client.setFileDescriptorsForSend(arrayOf(fd.fileDescriptor))
                            client.outputStream.write("${cfg.rawAddress}|${cfg.rawMtu}".toByteArray())
                            client.setFileDescriptorsForSend(null)
                            check(client.inputStream.read() == 1) { "RAW rejected TUN descriptor" }
                        } finally { clients -= client }
                    }
                } catch (e: Throwable) { transferFailure = e }
                finally { transferred.countDown() }
            }
            workers.execute {
                while (!closed) {
                    val client = try { protectServer.accept() } catch (_: Exception) { break }
                    clients += client
                    workers.execute {
                        client.use {
                            try {
                                client.soTimeout = 5000
                                require(client.peerCredentials.uid == Process.myUid())
                                require(client.inputStream.read() == 1)
                                val fds = client.ancillaryFileDescriptors.orEmpty()
                                try {
                                    require(fds.size == 1)
                                    ParcelFileDescriptor.dup(fds.single()).use { copy ->
                                        check(service.protect(copy.fd)) { "VPN socket protection failed" }
                                        val network = PhysicalNetworks.select(service.getSystemService(android.net.ConnectivityManager::class.java))?.id
                                            ?: error("Physical network unavailable")
                                        network.bindSocket(copy.fileDescriptor)
                                    }
                                    client.outputStream.write(1)
                                } finally { fds.forEach { runCatching { Os.close(it) } } }
                            } catch (_: Exception) { runCatching { client.outputStream.write(0) } }
                            finally { clients -= client }
                        }
                    }
                }
            }
        } catch (e: Throwable) { close(); throw e }
    }

    fun attach(fd: ParcelFileDescriptor) {
        descriptor = fd
        attached.countDown()
        check(transferred.await(10, TimeUnit.SECONDS)) { "RAW TUN transfer timed out" }
        transferFailure?.let { throw IllegalStateException("RAW TUN: ${it.message}", it) }
    }

    fun environment(): Map<String, String> = mapOf(
        "FREETURN_RAW_TUN_SOCKET" to tunName, "FREETURN_RAW_TUN_DEFERRED" to "1",
        "FREETURN_RAW_PROTECT_SOCKET" to protectName)

    override fun close() {
        closed = true
        attached.countDown()
        runCatching { tunServer.close() }; runCatching { protectServer.close() }
        clients.forEach { runCatching { it.close() } }
        workers.shutdownNow()
        rulesFile.delete()
    }
}
