package com.freeturn.app.data.server

import com.freeturn.app.data.config.BypassRuleSet
import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.DnsMode
import com.freeturn.app.data.config.ObfProfile
import com.freeturn.app.data.config.Provider
import com.freeturn.app.data.config.SplitTunnelMode
import com.freeturn.app.data.config.SshConfig
import com.freeturn.app.data.config.TunnelTransport
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// Имена JSON-ключей - контракт с сохранёнными данными: менять только с миграцией.
internal object ServerJson {
    fun encodeList(list: List<Server>): String {
        val arr = JSONArray()
        list.forEach { arr.put(encode(it)) }
        return arr.toString()
    }

    fun decodeList(raw: String?): List<Server> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { decode(arr.getJSONObject(it)) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun encode(p: Server): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("ssh", JSONObject().apply {
            put("ip", p.ssh.ip)
            put("port", p.ssh.port)
            put("username", p.ssh.username)
            put("password", p.ssh.password)
            put("authType", p.ssh.authType)
            put("sshKey", p.ssh.sshKey)
            put("hostFingerprint", p.ssh.hostFingerprint)
            put("rootMode", p.ssh.rootMode)
            put("sudoPassword", p.ssh.sudoPassword)
        })
        put("client", JSONObject().apply {
            put("serverAddress", p.client.serverAddress)
            put("vkLink", p.client.vkLink)
            put("provider", p.client.provider)
            put("threads", p.client.threads)
            put("streamsPerCred", p.client.streamsPerCred)
            put("useUdp", p.client.useUdp)
            put("manualCaptcha", p.client.manualCaptcha)
            put("localPort", p.client.localPort)
            put("isRawMode", p.client.isRawMode)
            put("rawCommand", p.client.rawCommand)
            put("tcpForward", p.client.tcpForward)
            put("bond", p.client.bond)

            put("debugMode", p.client.debugMode)
            put("useCarrierDns", p.client.useCarrierDns)
            put("dnsMode", p.client.dnsMode)
            put("customDns", p.client.customDns)
            put("syncServerSwitches", p.client.syncServerSwitches)
            put("magicSwitch", p.client.magicSwitch)
            put("magicTurn", p.client.magicTurn)
            put("tunnelTransport", p.client.tunnelTransport)
            put("wireGuardConfig", p.client.wireGuardConfig)
            put("vlessUri", p.client.vlessUri)
            put("bypassRulesEnabled", p.client.bypassRulesEnabled)
            put("bypassRuleSets", JSONArray().apply {
                p.client.bypassRuleSets.forEach { rule -> put(JSONObject()
                    .put("name", rule.name).put("format", rule.format).put("content", rule.content)) }
            })
            put("wireGuardTunnelName", p.client.wireGuardTunnelName)
            put("splitTunnelMode", p.client.splitTunnelMode)
            put("splitTunnelApps", p.client.splitTunnelApps)
            put("splitTunnelUseDefaults", p.client.splitTunnelUseDefaults)
            put("logsEnabled", p.client.logsEnabled)
            put("clientId", p.client.clientId)
        })
        put("proxyListen", p.proxyListen)
        put("proxyConnect", p.proxyConnect)
        put("opts", JSONObject().apply {
            put("obfProfile", p.opts.obfProfile)
            put("obfKey", p.opts.obfKey)
        })
    }

    private fun decode(o: JSONObject): Server {
        val sshO = o.optJSONObject("ssh") ?: JSONObject()
        val cliO = o.optJSONObject("client") ?: JSONObject()
        val optsO = o.optJSONObject("opts") ?: JSONObject()
        return Server(
            id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
            name = o.optString("name").ifBlank { Server.FALLBACK_NAME },
            ssh = SshConfig(
                ip = sshO.optString("ip"),
                port = sshO.optInt("port", 22),
                username = sshO.optString("username", "root"),
                password = sshO.optString("password"),
                authType = sshO.optString("authType", SshConfig.AUTH_PASSWORD),
                sshKey = sshO.optString("sshKey"),
                hostFingerprint = sshO.optString("hostFingerprint"),
                rootMode = sshO.optString("rootMode", SshConfig.ROOT),
                sudoPassword = sshO.optString("sudoPassword")
            ),
            client = ClientConfig(
                serverAddress = cliO.optString("serverAddress"),
                vkLink = cliO.optString("vkLink"),
                provider = cliO.optString("provider", Provider.VK).let {
                    if (it in Provider.VALUES) it else Provider.VK
                },
                // Фоллбэки = дефолты ClientConfig (для новых полей).
                threads = cliO.optInt("threads", 12),
                streamsPerCred = cliO.optInt("streamsPerCred", 6),
                useUdp = cliO.optBoolean("useUdp", false),
                manualCaptcha = cliO.optBoolean("manualCaptcha", false),
                localPort = cliO.optString("localPort", ClientConfig.DEFAULT_LOCAL_PORT),
                isRawMode = cliO.optBoolean("isRawMode", false),
                rawCommand = cliO.optString("rawCommand"),
                tcpForward = cliO.optBoolean("tcpForward", false),
                bond = cliO.optBoolean("bond", false),

                debugMode = cliO.optBoolean("debugMode", false),
                useCarrierDns = cliO.optBoolean("useCarrierDns", true),
                dnsMode = cliO.optString("dnsMode", DnsMode.AUTO).let {
                    if (it in DnsMode.VALUES) it else DnsMode.AUTO
                },
                customDns = cliO.optString("customDns"),
                syncServerSwitches = cliO.optBoolean("syncServerSwitches", true),
                magicSwitch = cliO.optBoolean("magicSwitch", false),
                magicTurn = cliO.optString("magicTurn"),
                tunnelTransport = cliO.optString("tunnelTransport", TunnelTransport.NONE).let {
                    if (it in TunnelTransport.VALUES) it else TunnelTransport.NONE
                },
                wireGuardConfig = cliO.optString("wireGuardConfig"),
                vlessUri = cliO.optString("vlessUri"),
                bypassRulesEnabled = cliO.optBoolean("bypassRulesEnabled", true),
                bypassRuleSets = cliO.optJSONArray("bypassRuleSets")?.let { rules ->
                    require(rules.length() <= BypassRuleSet.MAX_FILES)
                    val decoded = (0 until rules.length()).map { i -> rules.getJSONObject(i).let {
                        if (it.getString("format") == BypassRuleSet.BUILTIN_RU) {
                            require(it.getString("name") == "ru-aggregated.zone" && it.getString("content").isEmpty())
                            BypassRuleSet.defaults().single()
                        } else {
                            val content = it.getString("content")
                            require(content.length <= BypassRuleSet.MAX_BYTES * 4L / 3 + 4)
                            val format = it.getString("format")
                            require(format in setOf(BypassRuleSet.BINARY, BypassRuleSet.CIDR))
                            // Full content validation happens at import and tunnel start, not on every UI update.
                            BypassRuleSet(it.getString("name"), format, content)
                        }
                    } }
                    require(decoded.sumOf { it.content.length.toLong() } <= BypassRuleSet.MAX_TOTAL_BYTES * 4L / 3)
                    decoded
                } ?: BypassRuleSet.defaults(),
                wireGuardTunnelName = cliO.optString("wireGuardTunnelName").ifBlank { TunnelTransport.DEFAULT_TUNNEL_NAME },
                splitTunnelMode = cliO.optString("splitTunnelMode", SplitTunnelMode.EXCLUDE).let {
                    if (it in SplitTunnelMode.VALUES) it else SplitTunnelMode.EXCLUDE
                },
                splitTunnelApps = cliO.optString("splitTunnelApps"),
                splitTunnelUseDefaults = cliO.optBoolean("splitTunnelUseDefaults", cliO.optString("splitTunnelApps").isBlank()),
                logsEnabled = cliO.optBoolean("logsEnabled", true),
                clientId = cliO.optString("clientId")
            ),
            proxyListen = o.optString("proxyListen").ifBlank { "0.0.0.0:56000" },
            proxyConnect = o.optString("proxyConnect").ifBlank { "127.0.0.1:40537" },
            opts = ServerOpts(
                obfProfile = optsO.optString("obfProfile", ObfProfile.NONE).let {
                    if (it in ObfProfile.VALUES) it else ObfProfile.NONE
                },
                obfKey = optsO.optString("obfKey", "")
            )
        )
    }
}
