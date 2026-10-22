// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

/**
 * Where a task-flow run publishes its messages and flags.
 *
 * ## Why this exists
 *
 * [TaskFlowController] used to hold a `TaskFlowUiState`, whose members are all
 * Compose runtime types — `SnapshotStateList<ChatMessage>` and `MutableState<T>`.
 * That made the orchestration logic unusable from anywhere but a Compose screen,
 * so the WebView preview had exactly two options: re-implement the flow, or
 * render hardcoded HTML. CLAUDE.md forbids the former ("duplicate code paths
 * that should be unified"), so the state contract is extracted here instead.
 *
 * [TaskFlowUiState] implements this for the Compose chat screen — that adapter is
 * behaviour-preserving, the Compose path is unchanged. A WebView surface
 * implements it by forwarding to `evaluateJavascript`.
 *
 * ## Scope
 *
 * Deliberately *not* a general "chat UI" abstraction. It carries only what
 * [TaskFlowController] actually writes, so there is no speculative API to keep
 * in sync. If a future surface needs more, add the method when the second
 * caller exists — not before.
 *
 * All methods may be called from the UI thread; implementations that bridge to
 * another thread must marshal themselves.
 */
interface ChatSink {

    /** Model/status line shown to the user, e.g. `● 本地模型 · LiteRT-LM`. */
    var modelStatus: String

    /** True while waiting for the first model response. */
    var awaitingReply: Boolean

    /** True between a tool/action event and task completion. */
    var taskRunning: Boolean

    /**
     * Append a message.
     *
     * [message] carries the typing indicator as `content == "..."` — a magic
     * string already shared with `ChatScreen`, `ChatSessionController` and
     * `CloudContextHandoffFormatter`. Replaced by [TYPING_INDICATOR] below so the
     * convention has one name.
     */
    fun append(message: ChatMessage)

    /**
     * Replace the most recent typing indicator with real text.
     *
     * Must be a no-op when there is no indicator. Implementations must not
     * throw if it was already consumed by [removeTypingIndicator] — the event
     * stream can deliver a terminal event after a `Response` already filled it.
     */
    fun replaceTypingIndicator(text: String, modelName: String?)

    /**
     * Publish the terminal assistant text for the current task.
     *
     * Replaces the pending indicator when present, or appends the answer when
     * an earlier tool action already removed it. The controller calls this
     * once per task; it filters duplicate terminal events.
     */
    fun completeAssistantTurn(text: String, modelName: String?)

    /** Drop the typing indicator if present; no-op otherwise. */
    fun removeTypingIndicator()

    /**
     * Record one tool call and its outcome as a [ChatMessage.Role.TOOL_GROUP]
     * message carrying [ChatMessage.toolSteps].
     *
     * ## Why a new message rather than mutating the previous one
     *
     * The event stream delivers `ToolAction(toolName)` and then
     * `ToolResult(toolName, success, detail)` as two separate events. Merging
     * them into the last assistant message would need an "update the previous
     * message" operation on this interface, which none of the three
     * implementations has and which nothing else needs.
     *
     * Appending one message per tool call instead gives each call its own row —
     * `ChatScreen` already renders `TOOL_GROUP` via `ToolGroup()`, and the
     * WebView surface renders `.tool-card`. Both read the same field.
     *
     * The `Finish` pseudo-tool is skipped by the caller: it is the agent
     * loop's own bookkeeping, not a device action, and surfacing it would put
     * "Finish..." in front of the user as though something had happened.
     */
    fun appendToolStep(step: ToolStep)

    /** Snapshot of all messages, oldest first. */
    fun snapshotMessages(): List<ChatMessage>

    companion object {
        /**
         * Placeholder content of the in-flight assistant turn.
         *
         * Matching on a literal string is pre-existing (four files already do it),
         * but the name is new — a follow-up should introduce a proper `ChatMessage`
         * flag rather than keep widening the string comparison.
         */
        const val TYPING_INDICATOR = "..."
    }
}
