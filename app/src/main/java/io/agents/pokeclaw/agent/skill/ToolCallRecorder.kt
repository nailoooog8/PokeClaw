// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.skill

import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.XLog

/**
 * Records tool calls made by the agent loop so a successful task can be
 * saved as a reusable skill recipe ("save as skill").
 *
 * Lifecycle: [begin] is called at the top of DefaultAgentService.runAgentLoop;
 * ToolRegistry.executeTool feeds every call into [record] while armed;
 * on task success [markSuccess] snapshots the buffer into [lastCapture].
 * The recorder only arms during agent-loop tasks — direct-tool and
 * skill-executor routes never record.
 */
object ToolCallRecorder {

    private const val TAG = "ToolCallRecorder"

    data class RecordedCall(
        val toolName: String,
        val params: Map<String, Any>,
        val success: Boolean
    )

    data class Capture(
        val task: String,
        val calls: List<RecordedCall>,
        val timestamp: Long
    )

    @Volatile
    private var armed = false

    private var taskPrompt = ""
    private val buffer = mutableListOf<RecordedCall>()

    @Volatile
    var lastCapture: Capture? = null
        private set

    /** Start a fresh recording for a new agent-loop task. */
    fun begin(task: String) {
        synchronized(buffer) {
            taskPrompt = task
            buffer.clear()
            armed = true
        }
        XLog.d(TAG, "Recording started for task: $task")
    }

    /** Called from ToolRegistry.executeTool for every tool call. */
    fun record(toolName: String, params: Map<String, Any>, result: ToolResult) {
        if (!armed) return
        synchronized(buffer) {
            buffer.add(RecordedCall(toolName, params, result.isSuccess))
        }
    }

    /** Snapshot the buffer as a successful-task capture and disarm. */
    fun markSuccess() {
        synchronized(buffer) {
            armed = false
            if (buffer.isNotEmpty()) {
                lastCapture = Capture(taskPrompt, buffer.toList(), System.currentTimeMillis())
                XLog.i(TAG, "Captured ${buffer.size} tool call(s) from task: $taskPrompt")
            }
        }
    }

    fun clearCapture() {
        lastCapture = null
    }

    /**
     * Disarm and drop the buffer without producing a capture. Call on every
     * task exit path that is not a success — without this the recorder stays
     * armed after a failure/cancel and keeps recording every later tool call
     * in the app (including ones triggered over HTTP) under the finished task.
     */
    fun markEnded() {
        synchronized(buffer) {
            if (armed && buffer.isNotEmpty()) {
                XLog.d(TAG, "Discarding ${buffer.size} recorded call(s) from unfinished task")
            }
            armed = false
            buffer.clear()
            taskPrompt = ""
        }
    }
}
