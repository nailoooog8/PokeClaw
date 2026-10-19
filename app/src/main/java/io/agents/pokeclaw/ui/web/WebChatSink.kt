// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.web

import io.agents.pokeclaw.ui.chat.ChatMessage
import io.agents.pokeclaw.ui.chat.ChatSink
import io.agents.pokeclaw.ui.chat.ToolStep

/**
 * [ChatSink] that renders the task flow into the Claude UI design surface.
 *
 * Deliberately free of any `WebView` reference: the sink holds a plain list and
 * pushes immutable snapshots out through [onRender]. That keeps the interesting
 * logic — indicator bookkeeping, ordering, escaping — unit-testable on the JVM
 * without Robolectric, and keeps the WebView plumbing in the Activity where it
 * belongs. `evaluateJavascript` is a side effect, not a state store.
 *
 * ## Why this is not a duplicate chat UI
 *
 * [io.agents.pokeclaw.ui.chat.TaskFlowController] remains the only place task
 * orchestration happens. This class is a second *sink implementation*, the same
 * relationship `TaskFlowUiState` has with the Compose chat screen. CLAUDE.md
 * forbids duplicate code paths for the *flow*, not for the *view*.
 *
 * ## Known data gap
 *
 * `ChatMessage.toolSteps` is never populated by `TaskFlowController` today, so
 * `.tool-card` has no live source and stays as static sample markup in
 * `index.html`. The renderer already handles a tool card when one does arrive;
 * it is the producing side that is missing, not this class.
 */
class WebChatSink(
    private val onRender: (List<WebMessage>) -> Unit,
) : ChatSink {

    /** A message reduced to exactly what the page needs to draw it. */
    data class WebMessage(
        val role: String,
        val content: String,
        val isTypingIndicator: Boolean,
        val modelName: String? = null,
        val toolSteps: List<ToolStepView> = emptyList(),
    )

    data class ToolStepView(
        val name: String,
        val summary: String,
        val success: Boolean,
    )

    private val messages = mutableListOf<WebMessage>()

    override var modelStatus: String = ""
        set(value) {
            field = value
            emit()
        }

    override var awaitingReply: Boolean = false
        set(value) {
            field = value
            emit()
        }

    override var taskRunning: Boolean = false
        set(value) {
            field = value
            emit()
        }

    /**
     * Index of the trailing typing indicator, or -1.
     *
     * Tracked by position rather than by scanning for magic content so that
     * `replaceTypingIndicator` stays O(1) and so a model reply that happens to
     * contain "..." cannot be mistaken for an indicator.
     */
    private var typingIndex: Int = -1

    override fun append(message: ChatMessage) {
        val isTyping = message.role == ChatMessage.Role.ASSISTANT &&
            message.content == ChatSink.TYPING_INDICATOR
        messages += WebMessage(
            role = message.role.name,
            content = message.content,
            isTypingIndicator = isTyping,
            modelName = message.modelName,
            toolSteps = message.toolSteps.orEmpty().map {
                ToolStepView(it.toolName, it.summary, it.success)
            },
        )
        if (isTyping) typingIndex = messages.lastIndex
        emit()
    }

    override fun replaceTypingIndicator(text: String, modelName: String?) {
        // No-op contract, same as TaskFlowUiState: a terminal event can arrive
        // after the indicator was already consumed.
        if (typingIndex < 0 || typingIndex >= messages.size) return
        val old = messages[typingIndex]
        messages[typingIndex] = old.copy(
            content = text,
            isTypingIndicator = false,
            modelName = modelName ?: old.modelName,
        )
        typingIndex = -1
        emit()
    }

    override fun removeTypingIndicator() {
        if (typingIndex < 0 || typingIndex >= messages.size) return
        messages.removeAt(typingIndex)
        typingIndex = -1
        emit()
    }

    override fun appendToolStep(step: ToolStep) {
        messages += WebMessage(
            role = ChatMessage.Role.TOOL_GROUP.name,
            content = "",
            isTypingIndicator = false,
            toolSteps = listOf(ToolStepView(step.toolName, step.summary, step.success)),
        )
        emit()
    }

    override fun snapshotMessages(): List<ChatMessage> = messages.map { web ->
        ChatMessage(
            role = runCatching { ChatMessage.Role.valueOf(web.role) }
                .getOrDefault(ChatMessage.Role.SYSTEM),
            content = web.content,
            toolSteps = web.toolSteps
                .map { ToolStep(it.name, it.summary, it.success) }
                .takeIf { it.isNotEmpty() },
            modelName = web.modelName,
        )
    }

    /** Drop every message. Used when the page is re-entered mid-task. */
    fun clear() {
        messages.clear()
        typingIndex = -1
        emit()
    }

    /**
     * Reload from a persisted history, replacing whatever is on screen.
     *
     * Used on entry so a task interrupted by process death is not lost. A
     * restored typing indicator would otherwise come back as a permanent
     * "thinking" bubble with no task behind it to resolve it — the same
     * reason [ChatSink.replaceTypingIndicator] is a no-op when its indicator is
     * gone. So any indicator in the saved history is dropped.
     */
    fun restore(persisted: List<ChatMessage>) {
        messages.clear()
        typingIndex = -1
        persisted
            .filterNot {
                it.role == ChatMessage.Role.ASSISTANT && it.content == ChatSink.TYPING_INDICATOR
            }
            .forEach { message ->
                messages += WebMessage(
                    role = message.role.name,
                    content = message.content,
                    isTypingIndicator = false,
                    modelName = message.modelName,
                    toolSteps = message.toolSteps.orEmpty().map {
                        ToolStepView(it.toolName, it.summary, it.success)
                    },
                )
            }
        emit()
    }

    private fun emit() {
        onRender(messages.toList())
    }
}
