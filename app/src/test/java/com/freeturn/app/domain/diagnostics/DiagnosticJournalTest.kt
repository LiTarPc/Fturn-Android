package com.freeturn.app.domain.diagnostics

import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticJournalTest {
    @get:Rule val temp = TemporaryFolder()
    private fun events(archive: File) = ZipFile(archive).use { zip ->
        zip.getInputStream(zip.getEntry("events.log")).bufferedReader().use { it.readText() }
    }

    @Test fun rotationKeepsNewestAndSurvivesReopen() {
        val dir = temp.newFolder()
        RotatingLogStore(dir, 1024, 256).use { store ->
            repeat(80) { store.append("record-$it " + "x".repeat(55), it.toLong()) }
            assertTrue(store.sizeBytes() <= 1024)
            assertEquals(store.sizeBytes(), dir.listFiles()!!.sumOf { it.length() })
        }
        RotatingLogStore(dir, 1024, 256).use { store ->
            store.append("after-restart")
            val text = events(store.exportArchive(File(temp.root, "export.zip"), "test"))
            assertFalse(text.contains("record-0 "))
            assertTrue(text.contains("record-79 "))
            assertTrue(text.indexOf("record-79 ") < text.indexOf("after-restart"))
            store.clear()
            assertEquals(0L, store.sizeBytes())
            store.append("after-clear")
            val cleared = events(store.exportArchive(File(temp.root, "export.zip"), "test"))
            assertFalse(cleared.contains("after-restart"))
            assertTrue(cleared.contains("after-clear"))
        }
    }

    @Test fun productionCapacityAndUtf8AreBounded() {
        val dir = temp.newFolder()
        RotatingLogStore(dir).use { store ->
            repeat(1000) { store.append("entry-$it " + "Я".repeat(40000)) }
            assertTrue(store.sizeBytes() in (40L * 1024 * 1024)..(50L * 1024 * 1024))
            assertTrue(dir.listFiles()!!.size <= 10)
            assertTrue(dir.listFiles()!!.all { it.length() <= 5L * 1024 * 1024 })
            val text = events(store.exportArchive(File(temp.root, "large.zip"), "capacity test"))
            assertFalse(text.contains('\uFFFD'))
            assertTrue(text.contains("entry-999 "))
            assertFalse(text.contains("entry-0 "))
        }
    }

    @Test fun asynchronousExportIsBarrierAndClearIsExplicit() = runBlocking {
        withTimeout(10000) {
            val dir = temp.newFolder()
            val export = temp.newFolder()
            val recorder = DiagnosticRecorder(dir, export, "test-session", 4096, 1024)
            recorder.registerSecrets(listOf("hidden-real-password"))
            recorder.append("first password=hidden-real-password EOF 192.0.2.1:1080")
            var text = events(recorder.exportArchive())
            assertTrue(text.contains("first"))
            assertFalse(text.contains("hidden-real-password"))
            assertTrue(text.contains("EOF 192.0.2.1:1080"))
            recorder.close()
            val reopened = DiagnosticRecorder(dir, export, "next-session", 4096, 1024)
            text = events(reopened.exportArchive())
            assertTrue(text.contains("first"))
            assertTrue(text.contains("next-session"))
            reopened.clear()
            assertFalse(File(export, "fturn-diagnostics.zip").exists())
            reopened.append("new-history")
            text = events(reopened.exportArchive())
            assertFalse(text.contains("first"))
            assertTrue(text.contains("new-history"))
            reopened.close()
        }
    }

    @Test fun storageInitializationFailureDoesNotHangExport() = runBlocking {
        withTimeout(5000) {
            val recorder = DiagnosticRecorder(temp.newFile(), temp.newFolder(), "test")
            try {
                recorder.exportArchive()
                fail("Export must report an unavailable directory")
            } catch (_: IllegalStateException) {
                assertTrue(recorder.status.value.writeError)
            } finally { recorder.close() }
        }
    }


    @Test fun exportRedactsOldCaptchaPayloadsWithoutRemovingHistory() {
        val dir = temp.newFolder()
        RotatingLogStore(dir, 4096, 1024).use { store ->
            store.append("[Captcha] we sent /method/check data: session_id=old-secret&hash=old-proof")
            store.append("[Captcha Proxy] real browser sent /method/check data: old-browser-secret")
            store.append("[Captcha] pow envelope: {hash:old-pow-secret}")
            store.append("[Captcha] http POST check status=200 duration_ms=120")
            val text = events(store.exportArchive(File(temp.root, "old.zip"), "old history"))
            listOf("old-secret", "old-proof", "old-browser-secret", "old-pow-secret").forEach { assertFalse(text.contains(it)) }
            assertTrue(text.contains("status=200 duration_ms=120"))
            assertTrue(text.contains("[Captcha] we sent /method/check data: <redacted>"))
        }
    }

    @Test fun redactorRemovesCredentialsAndPreservesDiagnosticContext() {
        val samples = listOf(
            "--obf-key secret-value --client-id client-value --links https://vk.com/call/join/call-value",
            "{\"password\":\"secret-value\",\"access_token\":\"token-value\"}",
            "Authorization: Bearer bearer-value",
            "https://user:pass@example.com/path?token=query-value#fragment",
            "vless://11111111-2222-3333-4444-555555555555@example.com:443?secret",
            "error request_params=map[auth:request-value]",
            "registered-value timeout EOF 192.0.2.1:1080 120ms"
        )
        val text = samples.joinToString("\n") { DiagnosticRedactor.redact(it, listOf("registered-value")) }
        listOf("secret-value", "client-value", "call-value", "token-value", "bearer-value", "user:pass",
            "query-value", "fragment", "11111111", "request-value", "registered-value").forEach {
            assertFalse("Leaked $it in $text", text.contains(it))
        }
        assertTrue(text.contains("timeout EOF 192.0.2.1:1080 120ms"))
        assertFalse(DiagnosticRedactor.redact("--links opaque-call-value").contains("opaque-call-value"))
        assertTrue(DiagnosticRedactor.redact("x".repeat(70000)).startsWith("[oversized"))
    }

    @Test fun sensitiveHeadersAndNestedTokensAreRedactedWithoutLeakingCookieFragments() {
        val samples = listOf(
            "2026-10-31 WARN [Captcha Proxy] header (/check): Cookie = first=cookie-first; second=cookie-second",
            "Set-Cookie: session=cookie-session; Path=/; HttpOnly",
            "Authorization: Bearer auth-first with trailing auth-second",
            "proxy-authorization = Basic proxy-auth-value",
            """{"outer":{"success_token":"success-value","session_token":"session-value","session_id":"session-id-value"}}""",
            """payload="{\"nested\":{\"success_token\":\"escaped-success-value\"}}" status=503""",
            """WARN session_token="broken secret with spaces""",
            "[Captcha Proxy] real browser pow: {opaque:pow-secret}",
            "[Captcha] loading http://127.0.0.1:8765/secret-path?key=challenge-value",
            "[Captcha] GET https://api.vk.ru/not_robot_captcha/path-secret?token=page-value",
            "[Captcha] http POST check status=503 duration_ms=120 outcome=unavailable"
        )
        val text = DiagnosticRedactor.redact(samples.joinToString("\n"))
        listOf("cookie-first", "cookie-second", "cookie-session", "auth-first", "auth-second",
            "proxy-auth-value", "success-value", "session-value", "session-id-value",
            "escaped-success-value", "broken secret with spaces", "pow-secret", "secret-path",
            "challenge-value", "path-secret", "page-value").forEach {
            assertFalse("Leaked synthetic value $it", text.contains(it))
        }
        assertTrue(text.contains("2026-10-31 WARN [Captcha Proxy] header (/check)"))
        assertTrue(text.contains("status=503 duration_ms=120 outcome=unavailable"))
        assertEquals(text, DiagnosticRedactor.redact(text))
    }

    @Test fun exportReappliesCurrentPolicyToLegacyRecordsAndMetadata() {
        val dir = temp.newFolder()
        RotatingLogStore(dir, 8192, 4096).use { store ->
            // Seed raw records as a previous app version would have stored them.
            store.append("[Captcha Proxy] header: Cookie = one=legacy-one; two=legacy-two")
            store.append("[Captcha Proxy] real browser pow: {opaque:legacy-proof}")
            store.append("""legacy={\"session_token\":\"legacy-escaped\"}""")
            store.append("""{"nested":{"success_token":"legacy-success"}}""")
            store.append("WARN RAW stream 7 closed: reason=timeout duration_ms=35000")
            val archive = store.exportArchive(File(temp.root, "legacy-private.zip"), "Authorization: Bearer legacy-auth")
            ZipFile(archive).use { zip ->
                val all = zip.entries().asSequence().joinToString("\n") {
                    zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() }
                }
                listOf("legacy-one", "legacy-two", "legacy-proof", "legacy-escaped", "legacy-success", "legacy-auth").forEach {
                    assertFalse("Export leaked synthetic value $it", all.contains(it))
                }
                assertTrue(all.contains("reason=timeout duration_ms=35000"))
            }
        }
    }

    @Test fun embeddedFlagSuffixDoesNotConsumeFollowingDiagnosticFields() {
        val text = DiagnosticRedactor.redact("session_token=display-token reason=timeout duration_ms=35000")
        assertFalse(text.contains("display-token"))
        assertTrue(text.contains("reason=timeout duration_ms=35000"))
        val command = DiagnosticRedactor.redact("core --token flag-secret --links opaque-call-value")
        assertFalse(command.contains("flag-secret"))
        assertFalse(command.contains("opaque-call-value"))
    }

}
