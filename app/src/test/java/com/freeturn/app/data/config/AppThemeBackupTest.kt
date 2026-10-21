package com.freeturn.app.data.config

import com.freeturn.app.data.backup.SettingsBackup
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppThemeBackupTest {
    private fun legacyBackup() = JSONObject().put("v", 3).put("servers", org.json.JSONArray())
        .put("ownClientId", "a".repeat(32)).put("nerdMode", false)

    @Test fun `older backups without an appearance choice use the system default`() {
        val restored = SettingsBackup.decode(legacyBackup().toString())
        assertEquals(AppTheme.SYSTEM, restored.appTheme)
        assertFalse(restored.nerdMode)
    }
    @Test fun `all appearance choices survive exporting and restoring settings`() {
        for (mode in AppTheme.VALUES) {
            val data = SettingsBackup.decode(legacyBackup().put("appTheme", mode).toString())
            val restored = SettingsBackup.decode(SettingsBackup.encode(data))
            assertEquals(mode, restored.appTheme)
            assertEquals(data.ownClientId, restored.ownClientId)
            assertFalse(restored.nerdMode)
        }
    }
    @Test fun `unsupported backup appearance values safely fall back to system`() {
        assertEquals(AppTheme.SYSTEM, SettingsBackup.decode(legacyBackup().put("appTheme", "unknown").toString()).appTheme)
    }
    @Test fun `explicit appearance overrides the system and system mode follows it`() {
        assertFalse(AppTheme.isDark(AppTheme.LIGHT, true))
        assertTrue(AppTheme.isDark(AppTheme.DARK, false))
        assertTrue(AppTheme.isDark(AppTheme.SYSTEM, true))
        assertFalse(AppTheme.isDark(AppTheme.SYSTEM, false))
        assertEquals(AppTheme.SYSTEM, AppTheme.normalize(null))
        assertTrue(AppTheme.isDark("unknown", true))
    }
}
