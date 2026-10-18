// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.agents.pokeclaw.AppCapabilityCoordinator
import io.agents.pokeclaw.AppViewModel
import io.agents.pokeclaw.ServiceBindingState
import io.agents.pokeclaw.TaskEvent
import io.agents.pokeclaw.agent.DirectDeviceDataGuard
import io.agents.pokeclaw.agent.PipelineRouter
import io.agents.pokeclaw.agent.TaskPromptEnvelope
import io.agents.pokeclaw.agent.llm.ModelConfigRepository
import io.agents.pokeclaw.service.ClawAccessibilityService
import io.agents.pokeclaw.service.ForegroundService
import io.agents.pokeclaw.service.AutoReplyManager
import io.agents.pokeclaw.tool.ToolRegistry
import io.agents.pokeclaw.ui.settings.SettingsActivity
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import java.util.concurrent.ExecutorService

/**
 * Compose adapter for [ChatSink].
 *
 * Every method writes to exactly the same Compose state the controller used
 * before, so the Compose chat screen is behaviour-preserving by construction —
 * this is a type change, not a logic change.
 */
class TaskFlowUiState(
    val messages: SnapshotStateList<ChatMessage>,
    private val modelStatusState: MutableState<String>,
    private val awaitingReplyState: MutableState<Boolean>,
    private val taskRunningState: MutableState<Boolean>,
) : ChatSink {

    // Names deliberately differ from the [ChatSink] members: an `override var
    // modelStatus` next to a `MutableState<String> modelStatus` parameter would
    // be the same identifier twice with different meanings. Hence the `…State`
    // suffix on the constructor params.
    override var modelStatus: String
        get() = modelStatusState.value
        set(value) { modelStatusState.value = value }

    override var awaitingReply: Boolean
        get() = awaitingReplyState.value
        set(value) { awaitingReplyState.value = value }

    override var taskRunning: Boolean
        get() = taskRunningState.value
        set(value) { taskRunningState.value = value }

    override fun append(message: ChatMessage) {
        messages.add(message)
    }

    override fun replaceTypingIndicator(text: String, modelName: String?) {
        val idx = messages.indexOfLast { isTypingIndicator(it) }
        if (idx >= 0) {
            messages[idx] = ChatMessage(ChatMessage.Role.ASSISTANT, text, modelName = modelName)
        } else {
            messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, text, modelName = modelName))
        }
    }

    override fun removeTypingIndicator() {
        val idx = messages.indexOfLast { isTypingIndicator(it) }
        if (idx >= 0) messages.removeAt(idx)
    }

    override fun snapshotMessages(): List<ChatMessage> = messages.toList()

    private fun isTypingIndicator(m: ChatMessage) =
        m.role == ChatMessage.Role.ASSISTANT && m.content == ChatSink.TYPING_INDICATOR
}

/**
 * Owns task-mode send flow, typed TaskEvent rendering, and monitor start wiring.
 *
 * ComposeChatActivity keeps the shell; this controller keeps task-specific behavior.
 */
class TaskFlowController(
    private val activity: ComponentActivity,
    private val executor: ExecutorService,
    private val appViewModel: AppViewModel,
    private val chatSessionController: ChatSessionController,
    private val currentConversationId: () -> String,
    private val sink: ChatSink,
    private val onPersistConversation: () -> Unit,
    private val onTaskSettled: (() -> Unit)? = null,
    private val onTaskTerminal: ((TaskEvent) -> Unit)? = null,
) {

    companion object {
        private const val TAG = "TaskFlowController"
    }

    private var sendTaskRetryCount = 0
    private var lastMonitorStatusNote: String? = null
    private val pipelineRouter = PipelineRouter(activity)

    fun sendTask(text: String) {
        if (appViewModel.isTaskRunning()) {
            addSystem("Another task is still running. Stop it first.")
            onTaskTerminal?.invoke(TaskEvent.Failed("Another task is still running. Stop it first."))
            return
        }

        if (ModelConfigRepository.snapshot().isLocalActive() && isLikelyMonitorRequest(text)) {
            addUser(text)
            addSystem("Local mode starts monitoring from the Background card. Open Background, choose the app/contact, then tap Start Monitoring.")
            onTaskTerminal?.invoke(TaskEvent.Failed("Local mode starts monitoring from the Background card."))
            return
        }

        DirectDeviceDataGuard.deterministicToolCall(text)?.let { directTool ->
            XLog.i(TAG, "sendTask: executing deterministic direct tool before LLM/accessibility gates")
            executeDirectToolTask(text, directTool)
            return
        }

        when (AppCapabilityCoordinator.accessibilityState(activity)) {
            ServiceBindingState.DISABLED -> {
                val directTool = DirectDeviceDataGuard.deterministicToolCall(text)
                if (directTool != null) {
                    XLog.i(TAG, "sendTask: executing non-interactive direct tool without Accessibility")
                    executeDirectToolTask(text, directTool)
                    return
                }
                if (canRunWithoutAccessibility(text)) {
                    XLog.i(TAG, "sendTask: allowing non-interactive task without Accessibility")
                } else {
                Toast.makeText(activity, "Enable Accessibility Service to run tasks", Toast.LENGTH_LONG).show()
                addSystem("⚠️ Task mode needs Accessibility Service enabled. Opening Settings...")
                openSettings()
                sendTaskRetryCount = 0
                onTaskTerminal?.invoke(TaskEvent.Failed("Accessibility Service is required for this task."))
                return
                }
            }
            ServiceBindingState.CONNECTING -> {
                val directTool = DirectDeviceDataGuard.deterministicToolCall(text)
                if (directTool != null) {
                    XLog.i(TAG, "sendTask: executing non-interactive direct tool while Accessibility connects")
                    executeDirectToolTask(text, directTool)
                    return
                }
                if (canRunWithoutAccessibility(text)) {
                    XLog.i(TAG, "sendTask: allowing non-interactive task while Accessibility connects")
                } else {
                if (sendTaskRetryCount >= 1) {
                    Toast.makeText(activity, "Accessibility service not connected. Try toggling it off and on.", Toast.LENGTH_LONG).show()
                    addSystem("Accessibility service didn't connect. Try toggling it off and on in Settings.")
                    openSettings()
                    sendTaskRetryCount = 0
                    onTaskTerminal?.invoke(TaskEvent.Failed("Accessibility service did not connect."))
                    return
                }
                sendTaskRetryCount++
                addSystem("Accessibility service connecting, please wait...")
                executor.submit {
                    val connected = ClawAccessibilityService.awaitRunning(5000)
                    activity.runOnUiThread {
                        if (connected) {
                            sendTask(text)
                        } else {
                            Toast.makeText(activity, "Accessibility service didn't connect", Toast.LENGTH_LONG).show()
                            addSystem("Accessibility service didn't connect. Go to Settings and toggle it off then on.")
                            sendTaskRetryCount = 0
                            onTaskTerminal?.invoke(TaskEvent.Failed("Accessibility service did not connect."))
                        }
                    }
                }
                return
                }
            }
            ServiceBindingState.DEGRADED -> {
                val directTool = DirectDeviceDataGuard.deterministicToolCall(text)
                if (directTool != null) {
                    XLog.i(TAG, "sendTask: executing non-interactive direct tool while Accessibility is degraded")
                    executeDirectToolTask(text, directTool)
                    return
                }
                if (canRunWithoutAccessibility(text)) {
                    XLog.i(TAG, "sendTask: allowing non-interactive task while Accessibility is degraded")
                } else {
                    Toast.makeText(activity, "Accessibility service disconnected. Open Settings and toggle it back on.", Toast.LENGTH_LONG).show()
                    addSystem("Accessibility service disconnected. Open Settings and toggle it off then on.")
                    openSettings()
                    sendTaskRetryCount = 0
                    onTaskTerminal?.invoke(TaskEvent.Failed("Accessibility service is disconnected."))
                    return
                }
            }
            ServiceBindingState.READY -> Unit
        }
        sendTaskRetryCount = 0

        ensureNotificationPermission()
        sink.awaitingReply = false
        sink.taskRunning = false

        if (!KVUtils.hasLlmConfig()) {
            Toast.makeText(activity, "Configure LLM in Settings first", Toast.LENGTH_LONG).show()
            onTaskTerminal?.invoke(TaskEvent.Failed("Configure LLM in Settings first."))
            return
        }

        val agentPromptOverride = buildAgentPromptOverride(text)
        addUser(text)
        sink.awaitingReply = true
        sink.taskRunning = false
        XLog.i(TAG, "sendTask: isProcessing=TRUE")
        sink.append(ChatMessage(ChatMessage.Role.ASSISTANT, ChatSink.TYPING_INDICATOR))

        val taskId = "task_${System.currentTimeMillis()}"

        executor.submit {
            chatSessionController.prepareForTaskStart()

            activity.runOnUiThread {
                try {
                    appViewModel.startTask(
                        task = text,
                        taskId = taskId,
                        agentPromptOverride = agentPromptOverride,
                        originActivity = activity.javaClass.name,
                    ) { event ->
                        activity.runOnUiThread { handleTaskEvent(event) }
                    }                } catch (e: Exception) {
                    XLog.e(TAG, "sendTask failed: ${e.message}", e)
                    addSystem("Error: ${e.message}")
                    cleanupAfterTask()
                }
            }
        }
    }

    private fun executeDirectToolTask(text: String, toolCall: DirectDeviceDataGuard.DeterministicToolCall) {
        ensureNotificationPermission()
        addUser(text)
        sink.awaitingReply = true
        sink.taskRunning = false
        sink.append(ChatMessage(ChatMessage.Role.ASSISTANT, ChatSink.TYPING_INDICATOR))

        executor.submit {
            try {
                val result = ToolRegistry.getInstance().executeTool(toolCall.toolName, toolCall.params)
                activity.runOnUiThread {
                    val answer = result.data ?: result.error ?: "Done."
                    replaceTypingIndicator(answer)
                    onTaskTerminal?.invoke(TaskEvent.Completed(answer))
                    cleanupAfterTask()
                }
            } catch (e: Exception) {
                XLog.e(TAG, "executeDirectToolTask failed: ${e.message}", e)
                activity.runOnUiThread {
                    replaceTypingIndicator("Error: ${e.message}")
                    onTaskTerminal?.invoke(TaskEvent.Failed(e.message ?: "Direct tool failed"))
                    cleanupAfterTask()
                }
            }
        }
    }

    private fun canRunWithoutAccessibility(text: String): Boolean {
        if (DirectDeviceDataGuard.matchesNonInteractiveDeviceDataTask(text)) {
            return true
        }
        return when (pipelineRouter.route(text)) {
            is PipelineRouter.Route.DirectIntent -> true
            else -> false
        }
    }

    fun handleMonitorTask(text: String) {
        val target = MonitorTargetParser.fromTaskText(text)
        if (target == null) {
            addUser(text)
            addSystem("Could not figure out who to monitor. Try: \"Monitor Mom on WhatsApp\"")
            return
        }

        startMonitor(target, typedInput = text)
    }

    fun startMonitor(target: MonitorTargetSpec, typedInput: String? = null) {
        val trimmedLabel = target.label.trim()
        if (trimmedLabel.isEmpty()) {
            addSystem("Could not figure out who to monitor. Try: \"Monitor Mom on WhatsApp\"")
            return
        }

        typedInput?.let { addUser(it) }
        val missing = AppCapabilityCoordinator.missingMonitorRequirements(activity)
        if (missing.isNotEmpty()) {
            Toast.makeText(
                activity,
                "Enable ${missing.joinToString(" & ") { it.label }} in Settings first",
                Toast.LENGTH_LONG
            ).show()
            openSettings()
            onTaskTerminal?.invoke(TaskEvent.Failed("Missing required permissions for monitoring."))
            return
        }

        val contact = trimmedLabel
        val app = target.app
        sink.awaitingReply = false
        sink.taskRunning = false
        addSystem("Setting up auto-reply for $contact on $app...")

        val autoReplyManager = AutoReplyManager.getInstance()
        autoReplyManager.addTarget(contact, app)
        autoReplyManager.setEnabled(true)
        XLog.i(TAG, "startMonitor: enabled auto-reply for '${target.displayLabel}'")

        Handler(Looper.getMainLooper()).postDelayed({
            sink.awaitingReply = false
            sink.taskRunning = false
            addSystem("✓ Auto-reply is now active for ${target.displayLabel}.\nMonitoring in background — you can stop anytime from the bar above.")
            XLog.i(TAG, "startMonitor: monitor active, staying in PokeClaw")
        }, 1500)
    }

    private fun handleTaskEvent(event: TaskEvent) {
        try {
            when (event) {
                is TaskEvent.Completed -> {
                    replaceTypingIndicator(event.answer, event.modelName)
                    onTaskTerminal?.invoke(event)
                    cleanupAfterTask()
                    checkAutoReplyConfirmation()
                }
                is TaskEvent.Failed -> {
                    replaceTypingIndicator("Error: ${event.error}")
                    onTaskTerminal?.invoke(event)
                    cleanupAfterTask()
                }
                is TaskEvent.Cancelled -> {
                    sink.removeTypingIndicator()
                    onTaskTerminal?.invoke(event)
                    cleanupAfterTask()
                }
                is TaskEvent.Blocked -> {
                    replaceTypingIndicator("Blocked by system dialog.")
                    onTaskTerminal?.invoke(event)
                    cleanupAfterTask()
                }
                is TaskEvent.ToolAction -> {
                    sink.awaitingReply = false
                    sink.taskRunning = true
                    if (!event.toolName.contains("Finish", ignoreCase = true)) {
                        sink.removeTypingIndicator()
                        addSystem("${event.toolName}...")
                    }
                }
                is TaskEvent.ToolResult -> {
                    sink.awaitingReply = false
                    sink.taskRunning = true
                    if (!event.success) addSystem("${event.toolName} failed")
                }
                is TaskEvent.Response -> {
                    sink.awaitingReply = false
                    replaceTypingIndicator(event.text)
                }
                is TaskEvent.Progress -> {
                    sink.awaitingReply = false
                    sink.taskRunning = true
                    addSystem(event.description)
                }
                is TaskEvent.LoopStart -> {
                    sink.awaitingReply = false
                    sink.taskRunning = true
                }
                is TaskEvent.TokenUpdate, is TaskEvent.Thinking -> Unit
            }
        } catch (e: Exception) {
            XLog.w(TAG, "handleTaskEvent error", e)
        }
    }

    private fun replaceTypingIndicator(text: String, actualModelName: String? = null) {
        val modelTag = actualModelName
            ?: sink.modelStatus.removePrefix("● ").split(" ·").firstOrNull()?.trim()
            ?: ""
        sink.replaceTypingIndicator(text, modelTag)
        onPersistConversation()
    }

    private fun removeTypingIndicator() {
        sink.removeTypingIndicator()
    }

    private fun cleanupAfterTask() {
        XLog.i(TAG, "cleanupAfterTask: isProcessing=FALSE")
        sink.awaitingReply = false
        sink.taskRunning = false
        appViewModel.clearTaskCallback()
        onTaskSettled?.invoke()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                chatSessionController.loadModelIfReady(
                    conversationId = currentConversationId(),
                    visibleMessages = sink.snapshotMessages(),
                )
            } catch (e: Exception) {
                XLog.e(TAG, "cleanupAfterTask: loadModel error", e)
            }
        }, 500)
    }

    private fun checkAutoReplyConfirmation() {
        val autoReplyManager = AutoReplyManager.getInstance()
        if (!autoReplyManager.isEnabled) {
            lastMonitorStatusNote = null
            return
        }
        val contacts = autoReplyManager.monitoredContacts.joinToString(", ")
        if (contacts.isBlank()) {
            lastMonitorStatusNote = null
            return
        }
        val note = "✓ Auto-reply active for $contacts.\nMonitoring in background — stop from bar above."
        if (note == lastMonitorStatusNote) return
        addSystem(note)
        lastMonitorStatusNote = note
        XLog.i(TAG, "checkAutoReplyConfirmation: monitor active, staying in PokeClaw")
    }

    private fun ensureNotificationPermission() {
        if (!AppCapabilityCoordinator.isNotificationPermissionGranted(activity)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun addUser(text: String) {
        sink.append(ChatMessage(ChatMessage.Role.USER, text))
    }

    private fun addSystem(text: String) {
        sink.append(ChatMessage(ChatMessage.Role.SYSTEM, text))
    }

    private fun openSettings() {
        activity.startActivity(Intent(activity, SettingsActivity::class.java))
    }

    private fun buildAgentPromptOverride(rawTask: String): String? {
        if (ModelConfigRepository.snapshot().isLocalActive()) {
            return null
        }

        val historyLines = CloudContextHandoffFormatter.conversationLines(sink.snapshotMessages())
        val backgroundStatus = buildBackgroundStatusContext()

        return TaskPromptEnvelope.build(
            chatHistoryLines = historyLines,
            currentRequest = rawTask,
            backgroundState = backgroundStatus,
        )
    }

    private fun buildBackgroundStatusContext(): String? {
        val autoReplyManager = AutoReplyManager.getInstance()
        if (!autoReplyManager.isEnabled) return null

        val contacts = autoReplyManager.monitoredContacts.toList()
        if (contacts.isEmpty()) return null

        return buildString {
            append("Background monitor active for: ")
            append(contacts.joinToString(", "))
            append('.')
        }
    }

    private fun isLikelyMonitorRequest(text: String): Boolean {
        val lower = text.lowercase()
        val mentionsMonitor = lower.contains("monitor") ||
            lower.contains("auto-reply") ||
            lower.contains("auto reply") ||
            lower.contains("autoreply")
        val looksLikeWatchMessages = lower.contains("watch") &&
            (lower.contains("message") || lower.contains("messages") || lower.contains("reply"))
        return mentionsMonitor || looksLikeWatchMessages
    }
}
