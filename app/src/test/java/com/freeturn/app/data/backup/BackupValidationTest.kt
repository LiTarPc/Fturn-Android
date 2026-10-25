package com.freeturn.app.data.backup

import com.freeturn.app.data.server.Server
import com.freeturn.app.data.server.ServerJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupValidationTest {
    private fun payload() = JSONObject().put("v", 3).put("ownClientId", "a".repeat(32))
        .put("servers", JSONArray(ServerJson.encodeList(listOf(Server(id = "one", name = "Test")))))
        .put("activeId", "one")

    @Test fun `all supported versions retain profiles and active selection`() {
        for (v in 1..3) {
            val restored = SettingsBackup.decode(payload().put("v", v).toString())
            assertEquals("one", restored.activeId)
            assertEquals("Test", restored.servers.single().name)
        }
    }
    @Test fun `missing profile array cannot replace existing profiles with an empty list`() {
        val data = payload().apply { remove("servers") }
        assertThrows(BackupCrypto.FormatException::class.java) { SettingsBackup.decode(data.toString()) }
    }
    @Test fun `malformed profile does not silently discard healthy profiles on restore`() {
        val data = payload().apply { getJSONArray("servers").put("damaged") }
        assertThrows(BackupCrypto.FormatException::class.java) { SettingsBackup.decode(data.toString()) }
    }
    @Test fun `corrupt rule content rejects the whole restore before applying it`() {
        val data = payload()
        data.getJSONArray("servers").getJSONObject(0).getJSONObject("client")
            .put("bypassRuleSets", JSONArray().put(JSONObject().put("format", "unknown")))
        assertThrows(BackupCrypto.FormatException::class.java) { SettingsBackup.decode(data.toString()) }
    }
    @Test fun `unknown versions and active ids are rejected`() {
        for (v in listOf(-1, 0, 4)) {
            assertThrows(BackupCrypto.FormatException::class.java) { SettingsBackup.decode(payload().put("v", v).toString()) }
        }
        assertThrows(BackupCrypto.FormatException::class.java) { SettingsBackup.decode(payload().put("activeId", "missing").toString()) }
    }
    @Test fun `intentional empty backup is valid`() {
        val data = payload().put("servers", JSONArray()).apply { remove("activeId") }
        assertTrue(SettingsBackup.decode(data.toString()).servers.isEmpty())
    }
    @Test fun `local recovery retains readable profiles but strict import rejects damage`() {
        val array = payload().getJSONArray("servers").put(17)
        assertEquals("one", ServerJson.decodeList(array.toString()).single().id)
        assertThrows(Exception::class.java) { ServerJson.decodeListStrict(array.toString()) }
    }
    @Test fun `duplicate ids are rejected by strict import`() {
        val array = payload().getJSONArray("servers")
        array.put(array.getJSONObject(0))
        assertThrows(Exception::class.java) { ServerJson.decodeListStrict(array.toString()) }
    }
    @Test fun `broken JSON is rejected instead of becoming an empty backup`() {
        assertThrows(Exception::class.java) { ServerJson.decodeListStrict("[") }
        assertThrows(Exception::class.java) { ServerJson.decodeListStrict("") }
    }
}
