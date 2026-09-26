package com.freeturn.app.data

import com.freeturn.app.data.config.ClientConfig
import com.freeturn.app.data.config.ObfProfile
import com.freeturn.app.data.server.ServerOpts
import org.json.JSONArray
import org.json.JSONObject

/** The JSON schema consumed by the gomobile library (v4.1.2). */
object CoreConfig {
    fun client(
        cfg: ClientConfig,
        srv: ServerOpts,
        carrierDns: String?,
        ownClientId: String,
    ): String {
        if (cfg.isRawMode) {
            val raw = cfg.rawCommand.trim()
            require(raw.startsWith("{")) {
                "Для встроенного ядра raw-режим принимает JSON-конфигурацию, а не команду CLI"
            }
            return JSONObject(raw).toString()
        }
        val dns = DnsList.normalize(cfg.customDns).ifBlank {
            if (cfg.useCarrierDns) DnsList.normalize(carrierDns.orEmpty()) else ""
        }
        val clientId = cfg.clientId.ifBlank { ownClientId }
        return JSONObject().apply {
            put("peer", cfg.serverAddress)
            put("clientId", clientId)
            put("provider", cfg.provider)
            put("turn", JSONObject().apply {
                put("n", cfg.threads)
                put("transport", if (cfg.useUdp) "udp" else "tcp")
                if (cfg.magicSwitch && cfg.magicTurn.isNotBlank()) put("host", cfg.magicTurn.trim())
            })
            put("proxy", JSONObject().apply {
                put("mode", if (cfg.tcpForward) "tcp" else "udp")
                put("listen", cfg.localPort)
            })
            put("vk", JSONObject().apply {
                put("links", JSONArray().put(cfg.vkLink))
                put("streamsPerCred", cfg.streamsPerCred)
                put("manualCaptcha", cfg.manualCaptcha)
                put("platform", "mobile")
            })
            if (srv.obfEnabled && ObfProfile.isValidKey(srv.obfKey)) {
                put("obf", JSONObject().apply {
                    put("profile", srv.obfProfile)
                    put("key", srv.obfKey)
                })
            }
            put("dns", JSONObject().apply {
                put("mode", cfg.dnsMode)
                if (dns.isNotBlank()) put("servers", JSONArray(dns.split(",")))
            })
            put("log", JSONObject().put("debug", cfg.debugMode))
        }.toString()
    }
}
