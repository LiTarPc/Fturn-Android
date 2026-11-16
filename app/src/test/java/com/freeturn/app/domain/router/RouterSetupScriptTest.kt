package com.freeturn.app.domain.router

import org.junit.Assert.*
import org.junit.Test

class RouterSetupScriptTest {
    @Test fun `SSH payload accepts shell metacharacters only inside literal assets`() {
        val key = "ab".repeat(32)
        val asset = "#!/bin/sh\nprintf '%s' '\$(touch should-not-run)'\n"
        val script = RouterSetupScript.install(mapOf("install-fturn-router.sh" to asset), key)
        val windows = java.io.File("C:/Program Files/Git/bin/bash.exe")
        val shell = if (windows.exists()) windows.path else "/bin/sh"
        val process = ProcessBuilder(shell, "-n").redirectErrorStream(true).start()
        process.outputStream.bufferedWriter().use { it.write(script) }
        val errors = process.inputStream.bufferedReader().readText()
        assertEquals(errors, 0, process.waitFor())
        assertTrue(script.contains("<<'FTURN_ASSET_"))
        assertTrue(script.contains(asset.trimEnd()))
    }
    @Test fun `reject traversal and nonhex pairing input before building root script`() {
        assertThrows(IllegalArgumentException::class.java) {
            RouterSetupScript.install(mapOf("../etc/config/network" to "bad"), "ab".repeat(32))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RouterSetupScript.install(emptyMap(), "ab".repeat(31) + "';")
        }
    }
}
