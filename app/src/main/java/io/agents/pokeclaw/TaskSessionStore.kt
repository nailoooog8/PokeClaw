// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw

import io.agents.pokeclaw.channel.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class TaskSessionPhase {
    IDLE,
    RUNNING,
    STOPPING,
}

data class TaskSessionState(
    val phase: TaskSessionPhase = TaskSessionPhase.IDLE,
    val messageId: String = "",
    val channel: Channel? = null,
    val taskText: String = "",
    val startedAtMillis: Long = 0L,
    val stopRequested: Boolean = false,
    val autoReturnToChat: Boolean = false,

    /**
     * Fully-qualified class name of the Activity that started the task, or null
     * to return to the chat screen (the historical behaviour).
     *
     * Tasks open other apps, so something has to bring the user back. That used
     * to be hardcoded to [io.agents.pokeclaw.ui.chat.ComposeChatActivity], which
     * is correct for the chat screen and wrong for every other surface that can
     * start a task — the WebView preview page got yanked to the chat screen when
     * its own task finished.
     *
     * Held as a String rather than an Activity reference on purpose: this state
     * outlives the Activity (a task can outlive its own screen), and holding an
     * Activity here would leak it. A String cannot be resolved to a live
     * instance — see [TaskOrchestrator] for the degraded path when the task
     * outlives the process.
     */
    val originActivity: String? = null,
) {
    val isRunning: Boolean
        get() = phase != TaskSessionPhase.IDLE && messageId.isNotEmpty()
}

/**
 * Single authoritative state holder for the currently running task session.
 *
 * The orchestrator mutates this store; UI and service layers can observe it
 * without having to infer task truth from multiple ad-hoc fields.
 */
class TaskSessionStore {

    private val lock = Any()
    private val _state = MutableStateFlow(TaskSessionState())
    val state: StateFlow<TaskSessionState> = _state

    fun snapshot(): TaskSessionState = _state.value

    fun isTaskRunning(): Boolean = _state.value.isRunning

    fun tryAcquire(
        messageId: String,
        channel: Channel,
        taskText: String = "",
        autoReturnToChat: Boolean = channel == Channel.LOCAL,
        originActivity: String? = null,
    ): Boolean {
        synchronized(lock) {
            if (_state.value.isRunning) return false
            _state.value = TaskSessionState(
                phase = TaskSessionPhase.RUNNING,
                messageId = messageId,
                channel = channel,
                taskText = taskText,
                startedAtMillis = System.currentTimeMillis(),
                stopRequested = false,
                autoReturnToChat = autoReturnToChat,
                originActivity = originActivity,
            )
            return true
        }
    }

    fun updateTaskText(taskText: String) {
        synchronized(lock) {
            val current = _state.value
            if (!current.isRunning || current.taskText == taskText) return
            _state.value = current.copy(taskText = taskText)
        }
    }

    fun markStopping(): Boolean {
        synchronized(lock) {
            val current = _state.value
            if (!current.isRunning) return false
            if (current.phase == TaskSessionPhase.STOPPING && current.stopRequested) return false
            _state.value = current.copy(
                phase = TaskSessionPhase.STOPPING,
                stopRequested = true,
            )
            return true
        }
    }

    fun release(): TaskSessionState {
        synchronized(lock) {
            val current = _state.value
            _state.value = TaskSessionState()
            return current
        }
    }
}
