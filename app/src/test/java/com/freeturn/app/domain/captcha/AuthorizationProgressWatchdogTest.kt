package com.freeturn.app.domain.captcha

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AuthorizationProgressWatchdogTest {
    private val generation = "a".repeat(32)
    private fun state(phase: CaptchaUiState.Phase) = CaptchaUiState(phase, generation)

    @Test fun silentResumeTimesOutEvenWhenPresentationMetadataKeepsChanging() = runTest {
        val state = MutableStateFlow(state(CaptchaUiState.Phase.RESUMING))
        var stalled = 0
        val guard = AuthorizationProgressWatchdog(backgroundScope, state, generation, { true }, { false }, { fail("No channel") }, { stalled++ })
        runCurrent(); advanceTimeBy(60_000); state.value = state.value.copy(hostVersion = 2, retryFailed = true)
        runCurrent(); advanceTimeBy(59_999); runCurrent(); assertEquals(0, stalled)
        advanceTimeBy(1); runCurrent(); assertEquals(1, stalled)
        advanceTimeBy(600_000); runCurrent(); assertEquals(1, stalled)
        guard.close()
    }
    @Test fun realChallengeOrManualWaitCancelsTheResumeDeadline() = runTest {
        val state = MutableStateFlow(state(CaptchaUiState.Phase.RESUMING));var stalled = 0
        val guard = AuthorizationProgressWatchdog(backgroundScope, state, generation, { true }, { false }, {}, { stalled++ })
        runCurrent(); advanceTimeBy(60_000)
        for (phase in listOf(CaptchaUiState.Phase.BROWSER, CaptchaUiState.Phase.MANUAL, CaptchaUiState.Phase.WAITING_USER)) {
            state.value = state(phase);runCurrent();advanceTimeBy(600_000);runCurrent();assertEquals(0, stalled)
        }
        state.value = state(CaptchaUiState.Phase.RESUMING);runCurrent();advanceTimeBy(120_000);runCurrent();assertEquals(1, stalled)
        guard.close()
    }
    @Test fun aWorkingChannelClearsStalePresentationWithoutRestartingCore() = runTest {
        val state = MutableStateFlow(state(CaptchaUiState.Phase.RESUMING));var completed = 0
        val guard = AuthorizationProgressWatchdog(backgroundScope, state, generation, { true }, { true }, { completed++ }, { fail("Working core restarted") })
        runCurrent();advanceTimeBy(120_000);runCurrent();assertEquals(1, completed);guard.close()
    }
    @Test fun replacedProcessAndStaleGenerationCannotTriggerRecovery() = runTest {
        val state = MutableStateFlow(CaptchaUiState(CaptchaUiState.Phase.RESUMING,"old"));var current = true;var stalled = 0
        val guard = AuthorizationProgressWatchdog(backgroundScope, state, generation, { current }, { false }, {}, { stalled++ })
        runCurrent();advanceTimeBy(120_000);runCurrent();assertEquals(0, stalled)
        state.value = state(CaptchaUiState.Phase.RESUMING);runCurrent();current=false
        advanceTimeBy(120_000);runCurrent();assertEquals(0, stalled);guard.close()
    }
    @Test fun stoppedGuardAndCompletedAuthorizationNeverRestartAnything() = runTest {
        val state = MutableStateFlow(state(CaptchaUiState.Phase.RESUMING));var stalled = 0
        val guard = AuthorizationProgressWatchdog(backgroundScope, state, generation, { true }, { false }, {}, { stalled++ })
        runCurrent();advanceTimeBy(50_000);state.value=state(CaptchaUiState.Phase.IDLE);runCurrent()
        advanceTimeBy(120_000);runCurrent();assertEquals(0, stalled)
        state.value=state(CaptchaUiState.Phase.RESUMING);runCurrent();guard.close()
        advanceTimeBy(120_000);runCurrent();assertEquals(0, stalled)
    }
}
