package com.freeturn.app.data.config

import com.freeturn.app.data.server.Server
import com.freeturn.app.data.server.ServerJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DefaultBypassAppsTest {
    @Test fun `all ru segments are selected without matching unrelated words`() {
        val installed = listOf("ru.new.service", "org.example.ru.app", "com.mail.ru",
            "com.truecaller", "org.crunchyroll", "com.rubank.app", "org.example.russia")
        val selected = splitTunnelSelection(SplitTunnelMode.EXCLUDE, "", installed)
        assertTrue(selected.containsAll(installed.take(3)))
        assertFalse(selected.any { it in installed.drop(3) })
        assertTrue(selected.contains("com.tinkoff.itinkoff"))
    }

    @Test fun `manual selection and explicitly empty selection stay unchanged`() {
        val installed = listOf("ru.new.service")
        assertEquals(setOf("org.custom.app"), splitTunnelSelection(SplitTunnelMode.EXCLUDE, "org.custom.app", installed))
        assertTrue(splitTunnelSelection(SplitTunnelMode.EXCLUDE, "", installed, useDefaults = false).isEmpty())
        assertTrue(splitTunnelSelection(SplitTunnelMode.INCLUDE, "", installed).isEmpty())
        assertTrue(splitTunnelSelection(SplitTunnelMode.ALL, "", installed).isEmpty())
    }

    @Test fun `empty manual choice survives saved profiles and backup serialization`() {
        val cfg = ClientConfig(splitTunnelApps = "", splitTunnelUseDefaults = false)
        assertEquals(cfg, ServerJson.decodeList(ServerJson.encodeList(listOf(Server(name = "test", client = cfg)))).single().client)
    }

    @Test fun `legacy default profiles gain RU rules and preserve custom selection`() {
        fun decode(apps: String) = ServerJson.decodeList(JSONArray().put(JSONObject().put("client",
            JSONObject().put("splitTunnelApps", apps))).toString()).single().client
        val defaults = decode("")
        assertTrue(defaults.splitTunnelUseDefaults)
        assertTrue(defaults.bypassRulesEnabled)
        assertEquals(BypassRuleSet.defaults(), defaults.bypassRuleSets)
        assertTrue(splitTunnelSelection(defaults.splitTunnelMode, defaults.splitTunnelApps,
            listOf("ru.new.service"), defaults.splitTunnelUseDefaults).contains("ru.new.service"))
        val custom = decode("org.custom.app")
        assertFalse(custom.splitTunnelUseDefaults)
        assertEquals(setOf("org.custom.app"), splitTunnelSelection(custom.splitTunnelMode, custom.splitTunnelApps,
            listOf("ru.new.service"), custom.splitTunnelUseDefaults))
    }
}
