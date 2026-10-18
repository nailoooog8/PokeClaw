// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

/**
 * Where a chat *session* publishes its model state, message feed and token meter.
 *
 * ## Why this exists
 *
 * Second layer of the same extraction as [ChatSink], and for the same reason:
 * [ChatSessionController] held a `ChatSessionUiState` built entirely from Compose
 * runtime types — one `SnapshotStateList<ChatMessage>` and seven `MutableState<T>`
 * wrappers, written in ~45 places inside `loadModelIfReady` and its neighbours.
 * That is the flagship chat path (model loading, download progress, session
 * compaction, billing), so anything that is not Compose cannot drive it.
 *
 * [ChatSessionUiState] implements this for the Compose chat screen, delegating to
 * exactly the same state as before — behaviour-preserving by construction.
 *
 * ## Why this is NOT [ChatSink]
 *
 * The two controllers overlap on four members (`modelStatus`, `awaitingReply`,
 * `append`, `snapshotMessages`) but **disagree on `replaceTypingIndicator`**:
 *
 * - [ChatSink] — no-op when no indicator is pending. A late terminal `TaskEvent`
 *   arriving after a `Response` already filled the placeholder must not invent a
 *   message.
 * - here — append when no indicator is pending. The controller appends the
 *   indicator in `sendChat` and every completion path replaces it; if the
 *   indicator is gone the user still has to see the reply, because
 *   `awaitingReply` is already cleared by then.
 *
 * One interface with one signature would have to pick a winner, and picking
 * either silently changes the other screen. So the two are separate types with
 * separate contracts. Do not merge them without changing one caller's behaviour
 * on purpose.
 *
 * ## Scope
 *
 * Carries only what [ChatSessionController] actually writes. `removeTypingIndicator`
 * and `taskRunning` are absent because this controller never touches them.
 *
 * All methods may be called from the UI thread; implementations that bridge to
 * another thread must marshal themselves.
 */
interface ChatSessionSink {

    /** Model/status line, e.g. `● Qwen · Cloud`, `Downloading: 42%`, `⚠ Load failed — tap model to retry`. */
    var modelStatus: String

    /** True between `sendChat` and the first real assistant text. */
    var awaitingReply: Boolean

    /** Whether the composer accepts input. False while the model is not ready. */
    var inputEnabled: Boolean

    /** True while a local model download is in flight. */
    var downloading: Boolean

    /** Download completion, 0–100. Only meaningful while [downloading]. */
    var downloadProgress: Int

    /**
     * Cumulative session token count.
     *
     * Incremented with `+=`, i.e. read-modify-write. Implementations must not
     * assume the increment is atomic — the caller posts one `+=` per completed
     * turn, which is what the Compose `MutableState` path already did.
     */
    var sessionTokens: Int

    /** Cumulative session cost in USD. Same read-modify-write contract as [sessionTokens]. */
    var sessionCost: Double

    /** Append [message] to the end of the feed. */
    fun append(message: ChatMessage)

    /**
     * Fill the in-flight assistant turn with [text], tagged with [modelName].
     *
     * [modelName] may be null; the adapter renders it as-is.
     *
     * Unlike [ChatSink.replaceTypingIndicator] this **appends** when no indicator
     * is pending — see the interface KDoc for why the two must differ.
     */
    fun replaceTypingIndicator(text: String, modelName: String?)

    /** The most recent message, or null when the feed is empty. */
    fun lastMessage(): ChatMessage?

    /**
     * Immutable copy of the feed, oldest first.
     *
     * Used for cloud-history rebuilds and for `ConversationCompactor`. Callers on
     * a background thread must prefer this over the live list.
     */
    fun snapshotMessages(): List<ChatMessage>
}
