package io.agents.pokeclaw.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskWatchdogTest {

    @Test
    fun `initial state is OK`() {
        var currentTime = 1000L
        val state = TaskWatchdogState(
            inactivityTimeoutMs = 10_000L,
            maxDurationMs = 60_000L,
            clock = { currentTime }
        )

        assertEquals(TaskWatchdogState.Status.OK, state.checkStatus())
        assertTrue(state.isArmed)
    }

    @Test
    fun `inactivity triggers after inactivityTimeoutMs without progress`() {
        var currentTime = 1000L
        val state = TaskWatchdogState(
            inactivityTimeoutMs = 10_000L,
            maxDurationMs = 60_000L,
            clock = { currentTime }
        )

        currentTime += 5000L
        assertEquals(TaskWatchdogState.Status.OK, state.checkStatus())

        currentTime += 6000L // 11s total inactivity
        assertEquals(TaskWatchdogState.Status.INACTIVITY_TIMEOUT, state.checkStatus())
    }

    @Test
    fun `recordProgress resets inactivity window`() {
        var currentTime = 1000L
        val state = TaskWatchdogState(
            inactivityTimeoutMs = 10_000L,
            maxDurationMs = 60_000L,
            clock = { currentTime }
        )

        currentTime += 8000L
        state.recordProgress()

        currentTime += 5000L // 5s since last progress, total 13s
        assertEquals(TaskWatchdogState.Status.OK, state.checkStatus())

        currentTime += 6000L // 11s since last progress
        assertEquals(TaskWatchdogState.Status.INACTIVITY_TIMEOUT, state.checkStatus())
    }

    @Test
    fun `maxDurationMs overrides periodic progress`() {
        var currentTime = 1000L
        val state = TaskWatchdogState(
            inactivityTimeoutMs = 10_000L,
            maxDurationMs = 30_000L,
            clock = { currentTime }
        )

        currentTime += 8000L
        state.recordProgress()

        currentTime += 8000L
        state.recordProgress()

        currentTime += 8000L
        state.recordProgress()

        // 24s elapsed, still under 30s
        assertEquals(TaskWatchdogState.Status.OK, state.checkStatus())

        currentTime += 8000L // 32s elapsed > 30s
        assertEquals(TaskWatchdogState.Status.MAX_DURATION_TIMEOUT, state.checkStatus())
    }

    @Test
    fun `disarmed watchdog never triggers timeout`() {
        var currentTime = 1000L
        val state = TaskWatchdogState(
            inactivityTimeoutMs = 10_000L,
            maxDurationMs = 30_000L,
            clock = { currentTime }
        )

        state.disarm()
        assertFalse(state.isArmed)

        currentTime += 100_000L
        assertEquals(TaskWatchdogState.Status.OK, state.checkStatus())
    }
}
