package io.agents.pokeclaw.ui.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the `ChatSink` adapter — specifically the typing-indicator lifecycle,
 * which is the only real logic that moved out of `TaskFlowController`.
 *
 * The indicator is identified by `content == "..."`, a magic string shared with
 * `ChatScreen`, `ChatSessionController` and `CloudContextHandoffFormatter`. These
 * tests pin the *ordering* rules, which are what actually break in practice: the
 * event stream can deliver a terminal event after a `Response` has already
 * filled the indicator, and both must be no-ops rather than corrupting the feed.
 */
class TaskFlowUiStateTest {

    private fun sink(
        initial: List<ChatMessage> = emptyList(),
    ): Pair<TaskFlowUiState, SnapshotStateList<ChatMessage>> {
        val messages = initial.toMutableStateList()
        return TaskFlowUiState(
            messages = messages,
            modelStatusState = mutableStateOf("● Test · Cloud"),
            awaitingReplyState = mutableStateOf(false),
            taskRunningState = mutableStateOf(false),
        ) to messages
    }

    private fun typing() =
        ChatMessage(ChatMessage.Role.ASSISTANT, ChatSink.TYPING_INDICATOR)

    private fun contents(messages: List<ChatMessage>) = messages.map { it.content }

    @Test
    fun `flags round-trip through the compose state`() {
        val (s, _) = sink()
        s.awaitingReply = true
        s.taskRunning = true
        s.modelStatus = "● Changed"
        assertTrue(s.awaitingReply)
        assertTrue(s.taskRunning)
        assertEquals("● Changed", s.modelStatus)
    }

    @Test
    fun `replace fills the pending indicator in place`() {
        val (s, messages) = sink(listOf(ChatMessage(ChatMessage.Role.USER, "hi"), typing()))
        s.replaceTypingIndicator("done", "gpt")
        // Same slot, not appended — the indicator is a placeholder, not a message.
        assertEquals(listOf("hi", "done"), contents(messages))
        assertEquals("gpt", messages.last().modelName)
    }

    @Test
    fun `replace appends when no indicator is pending`() {
        val (s, messages) = sink(listOf(ChatMessage(ChatMessage.Role.USER, "hi")))
        s.replaceTypingIndicator("answer", null)
        assertEquals(listOf("hi", "answer"), contents(messages))
    }

    @Test
    fun `replace targets the most recent indicator only`() {
        val (s, messages) = sink(listOf(typing(), typing()))
        s.replaceTypingIndicator("second", null)
        // The earlier indicator must survive — tool calls can interleave.
        assertEquals(listOf(ChatSink.TYPING_INDICATOR, "second"), contents(messages))
    }

    @Test
    fun `remove drops the indicator and is a no-op when absent`() {
        val (s, messages) = sink(listOf(ChatMessage(ChatMessage.Role.USER, "hi"), typing()))
        s.removeTypingIndicator()
        assertEquals(listOf("hi"), contents(messages))
        // Second call must not remove the user's message.
        s.removeTypingIndicator()
        assertEquals(listOf("hi"), contents(messages))
    }

    @Test
    fun `replace after remove appends instead of failing`() {
        // The real event order: ToolAction removes the indicator, then a late
        // terminal event still calls replace. Must not throw or corrupt.
        val (s, messages) = sink(listOf(typing()))
        s.removeTypingIndicator()
        s.replaceTypingIndicator("final", null)
        assertEquals(listOf("final"), contents(messages))
    }

    @Test
    fun `a user message with ellipsis content is never treated as the indicator`() {
        // The indicator must be role-scoped: "..." is legitimate user input.
        val (s, messages) = sink(listOf(ChatMessage(ChatMessage.Role.USER, "..."), typing()))
        s.replaceTypingIndicator("answer", null)
        assertEquals(listOf("...", "answer"), contents(messages))
    }

    @Test
    fun `remove ignores a user message whose content is the ellipsis`() {
        val (s, messages) = sink(listOf(ChatMessage(ChatMessage.Role.USER, "..."), typing()))
        s.removeTypingIndicator()
        assertEquals(listOf("..."), contents(messages))
    }

    @Test
    fun `snapshot returns an immutable copy`() {
        val (s, messages) = sink(listOf(ChatMessage(ChatMessage.Role.USER, "hi")))
        val snap = s.snapshotMessages()
        messages.add(ChatMessage(ChatMessage.Role.USER, "added later"))
        assertEquals(1, snap.size)
        assertEquals(2, messages.size)
        assertFalse(snap === messages.toList())
    }
}
