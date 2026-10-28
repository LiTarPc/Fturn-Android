package com.freeturn.app.ui.util

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.freeturn.app.R
import com.freeturn.app.domain.diagnostics.DiagnosticLogs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class JournalExportAction(val busy: Boolean, val save: () -> Unit)

/** Available in ordinary Settings as well as the developer log screen. */
@Composable
fun rememberJournalExport(): JournalExportAction {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) {
            busy = false
        } else {
            scope.launch {
                try {
                    val archive = DiagnosticLogs.exportArchive()
                    withContext(Dispatchers.IO) {
                        checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use { output ->
                            archive.inputStream().use { it.copyTo(output) }
                        }
                    }
                    Toast.makeText(context, R.string.logs_export_saved, Toast.LENGTH_LONG).show()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Toast.makeText(context, R.string.logs_export_failed, Toast.LENGTH_LONG).show()
                } finally { busy = false }
            }
        }
    }
    return JournalExportAction(busy) {
        if (!busy) {
            busy = true
            try {
                launcher.launch("fturn-diagnostics-${System.currentTimeMillis()}.zip")
            } catch (_: Exception) {
                busy = false
                Toast.makeText(context, R.string.logs_export_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
}
