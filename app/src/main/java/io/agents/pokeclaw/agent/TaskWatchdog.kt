// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent

import io.agents.pokeclaw.utils.XLog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pure state machine tracking activity and deadline timeouts.
 * Decoupled from threads/schedulers to be 100% unit testable without Android mocks.
 */
class TaskWatchdogState(
    val inactivityTimeoutMs: Long = DEFAULT_INACTIVITY_TIMEOUT_MS,
    val maxDurationMs: Long = DEFAULT_MAX_DURATION_MS,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    enum class Status {
        OK,
        INACTIVITY_TIMEOUT,
        MAX_DURATION_TIMEOUT,
    }

    @Volatile
    private var startedAtMs: Long = clock()
    @Volatile
    private var lastProgressAtMs: Long = startedAtMs
    @Volatile
    private var isDisarmed: Boolean = false

    fun recordProgress() {
        if (isDisarmed) return
        lastProgressAtMs = clock()
    }

    fun disarm() {
        isDisarmed = true
    }

    val isArmed: Boolean
        get() = !isDisarmed

    fun checkStatus(): Status {
        if (isDisarmed) return Status.OK
        val now = clock()
        if (maxDurationMs > 0 && now - startedAtMs >= maxDurationMs) {
            return Status.MAX_DURATION_TIMEOUT
        }
        if (inactivityTimeoutMs > 0 && now - lastProgressAtMs >= inactivityTimeoutMs) {
            return Status.INACTIVITY_TIMEOUT
        }
        return Status.OK
    }

    companion object {
        const val DEFAULT_INACTIVITY_TIMEOUT_MS = 120_000L // 2 minutes without progress
        const val DEFAULT_MAX_DURATION_MS = 600_000L // 10 minutes max total duration
    }
}

/**
 * Timer-backed watchdog for task execution.
 * Detects tool hangs, deadlocked LLM requests, and stalled agent loops.
 */
class TaskWatchdog(
    inactivityTimeoutMs: Long = TaskWatchdogState.DEFAULT_INACTIVITY_TIMEOUT_MS,
    maxDurationMs: Long = TaskWatchdogState.DEFAULT_MAX_DURATION_MS,
    private val checkIntervalMs: Long = DEFAULT_CHECK_INTERVAL_MS,
    private val executor: ScheduledExecutorService = defaultExecutor,
    private val onTimeout: (reason: TaskWatchdogState.Status) -> Unit,
) {
    companion object {
        private const val TAG = "TaskWatchdog"
        const val DEFAULT_CHECK_INTERVAL_MS = 5_000L // Check every 5 seconds

        private val defaultExecutor: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "task-watchdog").apply { isDaemon = true }
            }
        }
    }

    private val state = TaskWatchdogState(
        inactivityTimeoutMs = inactivityTimeoutMs,
        maxDurationMs = maxDurationMs,
    )

    private val timeoutFired = AtomicBoolean(false)
    private var scheduledTask: ScheduledFuture<*>? = null

    init {
        scheduledTask = executor.scheduleWithFixedDelay(
            { check() },
            checkIntervalMs,
            checkIntervalMs,
            TimeUnit.MILLISECONDS
        )
    }

    fun recordProgress() {
        state.recordProgress()
    }

    fun disarm() {
        state.disarm()
        scheduledTask?.cancel(false)
    }

    private fun check() {
        val status = state.checkStatus()
        if (status != TaskWatchdogState.Status.OK) {
            if (timeoutFired.compareAndSet(false, true)) {
                disarm()
                XLog.w(TAG, "TaskWatchdog triggered timeout: $status")
                try {
                    onTimeout(status)
                } catch (e: Exception) {
                    XLog.e(TAG, "Error in onTimeout callback", e)
                }
            }
        }
    }
}
