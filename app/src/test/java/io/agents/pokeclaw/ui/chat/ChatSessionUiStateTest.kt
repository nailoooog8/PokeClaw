package io.agents.pokeclaw.ui.chat

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the `ChatSessionSink` adapter — the Compose delegation plus the two
 * pieces of real logic that moved out of `ChatSessionController`: typing-indicator
 * identification, and the "append when the indicator is gone" fallback.
 *
 * The fallback is the reason this sink is not `ChatSink`. Read the counterpart
 * test in `TaskFlowUiStateTest`: the task-flow sink must *not* invent a message
 * when its indicator is missing, while this one must. If someone ever merges the
 * two interfaces, exactly one of those two test files has to break — that is the
 * intended alarm.
 */
class ChatSessionUiStateTest {

    private fun sink(
        initial: List<ChatMessage> = emptyList(),
    ): Pair<ChatSessionUiState, SnapshotStateList<ChatMessage>> {
        val messages = initial.toMutableStateList()
        return ChatSessionUiState(
            messages = messages,
            modelStatusState = mutableStateOf("● Test · Cloud"),
            awaitingReplyState = mutableStateOf(false),
            inputEnabledState = mutableStateOf(true),
            downloadingState = mutableStateOf(false),
            downloadProgressState = mutableStateOf(0),
            sessionTokensState = mutableStateOf(0),
            sessionCostState = mutableStateOf(0.0),
        ) to messages
    }

    private fun typing() =
        ChatMessage(ChatMessage.Role.ASSISTANT, ChatSink.TYPING_INDICATOR)

    private fun user(text: String) = ChatMessage(ChatMessage.Role.USER, text)

    private fun contents(messages: List<ChatMessage>) = messages.map { it.content }

    @Test
    fun `every scalar round-trips through the compose state`() {
        val (s, _) = sink()
        s.modelStatus = "Downloading: 42%"
        s.awaitingReply = true
        s.inputEnabled = false
        s.downloading = true
        s.downloadProgress = 42
        s.sessionTokens = 1234
        s.sessionCost = 0.25

        assertEquals("Downloading: 42%", s.modelStatus)
        assertTrue(s.awaitingReply)
        assertEquals(false, s.inputEnabled)
        assertTrue(s.downloading)
        assertEquals(42, s.downloadProgress)
        assertEquals(1234, s.sessionTokens)
        assertEquals(0.25, s.sessionCost, 0.0)
    }

    @Test
    fun `token and cost accumulate through read-modify-write`() {
        // The controller does `sink.sessionTokens += n` on every completed turn.
        // That is get-then-set, exactly like the `MutableState` path it replaced —
        // pin it so nobody "optimises" it into a delta method and changes when
        // the meter refreshes.
        val (s, _) = sink()
        repeat(3) { s.sessionTokens += 100 }
        repeat(2) { s.sessionCost += 0.5 }
        assertEquals(300, s.sessionTokens)
        assertEquals(1.0, s.sessionCost, 0.0)
    }

    @Test
    fun `replace fills the pending indicator in place`() {
        val (s, messages) = sink(listOf(user("hi"), typing()))
        s.replaceTypingIndicator("done", "gpt")
        assertEquals(listOf("hi", "done"), contents(messages))
        assertEquals("gpt", messages.last().modelName)
    }

    @Test
    fun `replace appends when no indicator is pending`() {
        // The divergent contract: TaskFlowUiState would no-op here.
        val (s, messages) = sink(listOf(user("hi")))
        s.replaceTypingIndicator("answer", null)
        assertEquals(listOf("hi", "answer"), contents(messages))
    }

    @Test
    fun `replace targets the most recent indicator only`() {
        val (s, messages) = sink(listOf(typing(), typing()))
        s.replaceTypingIndicator("second", null)
        assertEquals(listOf(ChatSink.TYPING_INDICATOR, "second"), contents(messages))
    }

    @Test
    fun `a user message with ellipsis content is never treated as the indicator`() {
        val (s, messages) = sink(listOf(user("..."), typing()))
        s.replaceTypingIndicator("answer", null)
        assertEquals(listOf("...", "answer"), contents(messages))
    }

    @Test
    fun `a system message with ellipsis content is never treated as the indicator`() {
        val (s, messages) = sink(
            listOf(ChatMessage(ChatMessage.Role.SYSTEM, "..."), typing())
        )
        s.replaceTypingIndicator("answer", null)
        assertEquals(listOf("...", "answer"), contents(messages))
    }

    @Test
    fun `replace with a null model name leaves the tag null`() {
        // The adapter must not substitute a placeholder — `ChatScreen` renders
        // `modelName == null` as "no model tag", which is the honest answer.
        val (s, messages) = sink(listOf(typing()))
        s.replaceTypingIndicator("done", null)
        assertNull(messages.last().modelName)
    }

    @Test
    fun `append keeps insertion order`() {
        val (s, messages) = sink()
        s.append(user("one"))
        s.append(ChatMessage(ChatMessage.Role.SYSTEM, "note"))
        s.append(ChatMessage(ChatMessage.Role.ASSISTANT, "two"))
        assertEquals(listOf("one", "note", "two"), contents(messages))
    }

    @Test
    fun `lastMessage returns the tail and null when empty`() {
        val (s, _) = sink()
        assertNull(s.lastMessage())
        s.append(user("first"))
        s.append(user("second"))
        assertEquals("second", s.lastMessage()?.content)
    }

    @Test
    fun `lastMessage is not a live view`() {
        val (s, _) = sink(listOf(user("only")))
        val last = s.lastMessage()
        s.append(user("newer"))
        assertEquals("only", last?.content)
    }

    @Test
    fun `snapshot returns an immutable copy`() {
        // `ConversationCompactor.compact` receives this on a background thread,
        // so it must not be a window onto the live list.
        val (s, messages) = sink(listOf(user("hi")))
        val snap = s.snapshotMessages()
        messages.add(user("added later"))
        assertEquals(1, snap.size)
        assertEquals(2, messages.size)
    }

    @Test
    fun `snapshot preserves role and ordering for cloud history rebuild`() {
        // `rebuildCloudHistoryFromVisibleMessages` filters on role while walking
        // the snapshot, so both must survive the copy.
        val (s, _) = sink(
            listOf(
                ChatMessage(ChatMessage.Role.SYSTEM, "sys"),
                user("q"),
                ChatMessage(ChatMessage.Role.ASSISTANT, "a", modelName = "gpt"),
                ChatMessage(ChatMessage.Role.TOOL_GROUP, "tools"),
            )
        )
        val snap = s.snapshotMessages()
        assertEquals(
            listOf(
                ChatMessage.Role.SYSTEM,
                ChatMessage.Role.USER,
                ChatMessage.Role.ASSISTANT,
                ChatMessage.Role.TOOL_GROUP,
            ),
            snap.map { it.role }
        )
        assertEquals("gpt", snap[2].modelName)
    }
}
