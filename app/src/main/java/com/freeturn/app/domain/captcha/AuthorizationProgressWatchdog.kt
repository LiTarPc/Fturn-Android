package com.freeturn.app.domain.captcha

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/** A silent resume cannot exempt a core from recovery forever. Explicit user waits stay unbounded. */
class AuthorizationProgressWatchdog(
    scope: CoroutineScope,
    state: StateFlow<CaptchaUiState>,
    generation: String,
    isCurrentProcess: () -> Boolean,
    hasConnection: () -> Boolean,
    onConnected: () -> Unit,
    onStalled: () -> Unit,
    timeoutMillis: Long = 120_000,
) : AutoCloseable {
    private val job: Job = scope.launch {
        state.distinctUntilChangedBy { it.generation to it.phase }.collectLatest { value ->
            if (generation.isBlank() || value.generation != generation || value.phase != CaptchaUiState.Phase.RESUMING) return@collectLatest
            delay(timeoutMillis)
            val latest = state.value
            if (!isCurrentProcess() || latest.generation != generation || latest.phase != CaptchaUiState.Phase.RESUMING) return@collectLatest
            if (hasConnection()) onConnected() else onStalled()
        }
    }
    override fun close() { job.cancel() }
}
