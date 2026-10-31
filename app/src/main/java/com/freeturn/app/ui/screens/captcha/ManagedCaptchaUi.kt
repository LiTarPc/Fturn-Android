@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.freeturn.app.ui.screens.captcha

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.freeturn.app.R
import com.freeturn.app.domain.captcha.CaptchaUiState
import com.freeturn.app.service.CaptchaPresentation

@Composable
fun CaptchaStatusBanner(state: CaptchaUiState) {
    if (!state.isWaiting) return
    val stats by com.freeturn.app.domain.proxy.ProxyServiceState.connectionStats.collectAsStateWithLifecycle()
    val title = when (state.phase) {
        CaptchaUiState.Phase.BROWSER -> R.string.auth_browser_title
        CaptchaUiState.Phase.MANUAL -> R.string.auth_manual_title
        CaptchaUiState.Phase.WAITING_USER -> R.string.auth_waiting_title
        else -> R.string.auth_resuming_title
    }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.bodyMedium)
                if (stats.total > 0) Text(stringResource(R.string.auth_channels, stats.active, stats.total), style = MaterialTheme.typography.bodySmall)
                if (state.retryFailed) Text(stringResource(R.string.auth_page_unavailable), style = MaterialTheme.typography.bodySmall)
            }
            when (state.phase) {
                CaptchaUiState.Phase.BROWSER -> TextButton(onClick = CaptchaPresentation::requestManual) {
                    Text(stringResource(R.string.auth_browser_manual))
                }
                CaptchaUiState.Phase.MANUAL -> TextButton(onClick = CaptchaPresentation::showManual) {
                    Text(stringResource(R.string.auth_manual_open))
                }
                CaptchaUiState.Phase.WAITING_USER -> TextButton(onClick = { CaptchaPresentation.resume(state.generation) }) {
                    Text(stringResource(R.string.auth_retry))
                }
                else -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
fun ManagedCaptchaDialog(state: CaptchaUiState) {
    if (state.phase != CaptchaUiState.Phase.MANUAL || !state.manualOpen) return
    Dialog(onDismissRequest = CaptchaPresentation::dismissManual,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(R.string.auth_manual_title)) }, actions = {
                TextButton(onClick = CaptchaPresentation::dismissManual) { Text(stringResource(R.string.captcha_close)) }
            })
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
                // The container owns only its parent relationship. Releasing an old
                // Compose host must never detach the WebView from a newer Activity.
                key(state.generation, state.requestId, state.hostVersion) {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { FrameLayout(it) }, update = { container ->
                        val view = CaptchaPresentation.manualView(state.generation, state.requestId)
                        if (view != null && view.parent !== container) {
                            (view.parent as? ViewGroup)?.removeView(view)
                            container.removeAllViews()
                            container.addView(view, FrameLayout.LayoutParams(-1, -1))
                        }
                    }, onRelease = { it.removeAllViews() })
                }
            }
        }
    }
}
