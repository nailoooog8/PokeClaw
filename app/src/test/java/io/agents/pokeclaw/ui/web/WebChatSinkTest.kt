package io.agents.pokeclaw.ui.web

import io.agents.pokeclaw.ui.chat.ChatMessage
import io.agents.pokeclaw.ui.chat.ChatSink
import io.agents.pokeclaw.ui.chat.ToolStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [WebChatSink] — the third `ChatSink` implementation, and the one that
 * renders into a WebView instead of Compose state.
 *
 * The class keeps a plain list and pushes snapshots through a callback rather
 * than touching a WebView, which is what makes it testable on the JVM without
 * Robolectric. These cases pin the parts that would otherwise only be visible
 * as a wrong-looking page on a device.
 */
class WebChatSinkTest {

    private var last: List<WebChatSink.WebMessage> = emptyList()
    private var renders = 0

    private fun sink() = WebChatSink { messages ->
        last = messages
        renders++
    }

    private fun typing() =
        ChatMessage(ChatMessage.Role.ASSISTANT, ChatSink.TYPING_INDICATOR)

    private fun user(text: String) = ChatMessage(ChatMessage.Role.USER, text)

    private fun assistant(text: String, model: String? = null) =
        ChatMessage(ChatMessage.Role.ASSISTANT, text, modelName = model)

    @Test
    fun `an emitted snapshot is immutable`() {
        val s = sink()
        s.append(user("a"))
        val captured = last
        s.append(user("b"))
        // The callback handed out a view of the list, not the live one. If it
        // aliased, every previously delivered frame would silently mutate.
        assertEquals(1, captured.size)
        assertEquals(2, last.size)
    }

    @Test
    fun `appends preserve order and role`() {
        val s = sink()
        s.append(user("first"))
        s.append(assistant("second"))
        assertEquals(listOf("USER", "ASSISTANT"), last.map { it.role })
    }

    @Test
    fun `the typing indicator is flagged, not inferred from content`() {
        val s = sink()
        s.append(assistant("..."))
        assertTrue(last.single().isTypingIndicator)

        // Same text, different role: a user who literally types "..." must not
        // be treated as an in-flight assistant turn.
        val s2 = sink()
        s2.append(user("..."))
        assertFalse(last.single().isTypingIndicator)
    }

    @Test
    fun `replaceTypingIndicator fills the indicator in place`() {
        val s = sink()
        s.append(user("q"))
        s.append(typing())
        s.replaceTypingIndicator("the answer", "gpt-x")

        assertEquals(2, last.size)
        assertEquals("the answer", last[1].content)
        assertEquals("gpt-x", last[1].modelName)
        assertFalse(last[1].isTypingIndicator)
    }

    @Test
    fun `replaceTypingIndicator with no indicator is a no-op`() {
        val s = sink()
        s.append(user("q"))
        val before = renders
        s.replaceTypingIndicator("late reply", null)
        // Terminal TaskEvents can arrive after a Response already filled the
        // indicator; inventing a message there would duplicate the reply.
        assertEquals(1, last.size)
        assertEquals(before, renders)
    }

    @Test
    fun `a second replace after the first is a no-op`() {
        val s = sink()
        s.append(typing())
        s.replaceTypingIndicator("first", null)
        s.replaceTypingIndicator("second", null)
        assertEquals("first", last.single().content)
    }

    @Test
    fun `removeTypingIndicator drops it and shifts the index`() {
        val s = sink()
        s.append(user("q"))
        s.append(typing())
        s.removeTypingIndicator()
        assertEquals(1, last.size)
        assertFalse(last.single().isTypingIndicator)

        // The next indicator must land on the new tail, not the stale index.
        s.append(typing())
        s.replaceTypingIndicator("after", null)
        assertEquals(2, last.size)
        assertEquals("after", last[1].content)
    }

    @Test
    fun `removeTypingIndicator twice is a no-op`() {
        val s = sink()
        s.append(typing())
        s.removeTypingIndicator()
        val before = renders
        s.removeTypingIndicator()
        assertEquals(0, last.size)
        assertEquals(before, renders)
    }

    @Test
    fun `scalar changes re-emit`() {
        val s = sink()
        val before = renders
        s.modelStatus = "● doubao · Cloud"
        s.awaitingReply = true
        s.taskRunning = true
        assertEquals(before + 3, renders)
        assertEquals("● doubao · Cloud", s.modelStatus)
        assertTrue(s.awaitingReply)
        assertTrue(s.taskRunning)
    }

    @Test
    fun `snapshotMessages rebuilds real ChatMessages`() {
        val s = sink()
        s.append(user("hi"))
        s.append(typing())
        s.replaceTypingIndicator("hello", "cloud-1")

        val rebuilt = s.snapshotMessages()
        assertEquals(2, rebuilt.size)
        assertEquals(ChatMessage.Role.USER, rebuilt[0].role)
        assertEquals("hello", rebuilt[1].content)
        assertEquals("cloud-1", rebuilt[1].modelName)
        assertNull(rebuilt[0].toolSteps)
    }

    @Test
    fun `tool steps survive the trip through the web view model`() {
        val s = sink()
        s.append(
            ChatMessage(
                ChatMessage.Role.ASSISTANT,
                "done",
                toolSteps = listOf(
                    ToolStep("accessibility.open_app", "com.MobileTicket", true),
                    ToolStep("tap", "btn_send", false),
                ),
            )
        )
        assertEquals(2, last.single().toolSteps.size)

        val rebuilt = s.snapshotMessages().single()
        assertEquals(2, rebuilt.toolSteps?.size)
        assertEquals("accessibility.open_app", rebuilt.toolSteps?.get(0)?.toolName)
        assertFalse(rebuilt.toolSteps?.get(1)?.success ?: true)
    }

    @Test
    fun `clear drops messages and any pending indicator`() {
        val s = sink()
        s.append(user("q"))
        s.append(typing())
        s.clear()
        assertEquals(0, last.size)

        // A stale index here would make the next replace rewrite an old message.
        s.append(typing())
        s.replaceTypingIndicator("fresh", null)
        assertEquals("fresh", last.single().content)
        assertEquals(1, last.size)
    }

    @Test
    fun `model name is kept when replace does not supply one`() {
        val s = sink()
        s.append(assistant("partial", "gpt-4"))
        s.append(typing())
        s.replaceTypingIndicator("done", null)
        // The indicator carries no model, so it must not blank the earlier one.
        assertEquals("gpt-4", last[0].modelName)
        assertNull(last[1].modelName)
    }

    // ---------------------------------------------------------------------
    // restore(): reloading a transcript that a killed process left on disk.
    // ---------------------------------------------------------------------

    @Test
    fun `restore replaces whatever is on screen`() {
        val s = sink()
        s.append(user("stale"))
        s.restore(listOf(user("persisted q"), assistant("persisted a")))
        assertEquals(listOf("persisted q", "persisted a"), last.map { it.content })
    }

    @Test
    fun `restore drops a typing indicator left behind by a killed task`() {
        val s = sink()
        // A task killed mid-flight persists its indicator. Reloading it would
        // show a permanent "thinking" bubble with no task left to resolve it.
        s.restore(listOf(user("q"), typing()))
        assertEquals(listOf("q"), last.map { it.content })
        assertFalse(last.any { it.isTypingIndicator })
    }

    @Test
    fun `restore of an empty history clears the screen`() {
        val s = sink()
        s.append(user("old"))
        s.restore(emptyList())
        assertTrue(last.isEmpty())
    }

    @Test
    fun `restore leaves the sink ready to accept new messages`() {
        val s = sink()
        s.restore(listOf(user("persisted")))
        s.append(typing())
        s.replaceTypingIndicator("fresh", null)
        // The restored list must not leave a stale indicator index behind, or
        // the replace would rewrite an older message instead.
        assertEquals(listOf("persisted", "fresh"), last.map { it.content })
    }

    @Test
    fun `restore keeps tool steps from the saved transcript`() {
        val s = sink()
        s.restore(
            listOf(
                ChatMessage(
                    ChatMessage.Role.ASSISTANT,
                    "done",
                    toolSteps = listOf(ToolStep("tap", "btn_send", true)),
                )
            )
        )
        assertEquals(1, last.single().toolSteps.size)
        assertEquals("tap", last.single().toolSteps.single().name)
    }
}
