package io.agents.pokeclaw.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Covers the fencing that keeps a model answer from being read back as
 * transcript structure.
 *
 * `load()` decides what a line *is* from the line itself, so a body
 * containing `---` or `## User` used to be split apart or swallowed. The
 * writer now fences such a body and the reader takes it verbatim. These tests
 * are written as round trips through the real [ChatHistoryManager.load],
 * because the bug only exists when the two sides disagree.
 */
class ChatHistoryManagerBodyFenceTest {

    private fun transcript(body: String): File {
        val file = File.createTempFile("pokeclaw-fence", ".md")
        file.writeText(
            buildString {
                appendLine("---")
                appendLine("id: test")
                appendLine("title: Convo")
                appendLine("created: 2026-10-22T15:30:00")
                appendLine("model: local")
                appendLine("---")
                appendLine()
                append(body)
            }
        )
        return file
    }

    /** The same shape save() writes: header, timestamp, body. */
    private fun message(role: ChatMessage.Role, content: String, modelName: String? = null): String {
        val sb = StringBuilder()
        sb.appendLine(
            when (role) {
                ChatMessage.Role.USER -> "## User"
                ChatMessage.Role.ASSISTANT -> "## 🦞 Assistant" + (modelName?.let { " [$it]" } ?: "")
                ChatMessage.Role.SYSTEM -> "## System"
                ChatMessage.Role.TOOL_GROUP -> "## Tools"
            }
        )
        sb.appendLine("<!-- pokeclaw:timestamp=1792678558202 -->")
        ChatHistoryManager.appendBody(sb, content)
        return sb.toString()
    }

    @Test
    fun `a horizontal rule in the answer no longer swallows the rest`() {
        // `---` toggles the frontmatter, so everything after it was read as
        // frontmatter and dropped. This is the common case: models emit rules.
        val file = transcript(
            message(ChatMessage.Role.ASSISTANT, "Here is the plan.\n---\nStep one.\nStep two.") +
                message(ChatMessage.Role.USER, "thanks")
        )

        val messages = ChatHistoryManager.load(file)

        assertEquals(2, messages.size)
        assertEquals("Here is the plan.\n---\nStep one.\nStep two.", messages[0].content)
        assertEquals("thanks", messages[1].content)
    }

    @Test
    fun `a header line in the answer no longer splits the transcript`() {
        // `## User` inside an answer used to start a new message, which moved
        // every following message one slot up and shifted their roles.
        val file = transcript(
            message(ChatMessage.Role.ASSISTANT, "## User\nThis was never said by the user") +
                message(ChatMessage.Role.SYSTEM, "Task completed.")
        )

        val messages = ChatHistoryManager.load(file)

        assertEquals(2, messages.size)
        assertEquals(ChatMessage.Role.ASSISTANT, messages[0].role)
        assertEquals("## User\nThis was never said by the user", messages[0].content)
        assertEquals(ChatMessage.Role.SYSTEM, messages[1].role)
    }

    @Test
    fun `a fake timestamp line in the answer is not read as one`() {
        val file = transcript(
            message(ChatMessage.Role.ASSISTANT, "stored at\n<!-- pokeclaw:timestamp=1 -->\nexactly")
        )

        val messages = ChatHistoryManager.load(file)

        assertEquals(1, messages.size)
        assertEquals("stored at\n<!-- pokeclaw:timestamp=1 -->\nexactly", messages[0].content)
        assertEquals(1792678558202L, messages[0].timestamp)
    }

    @Test
    fun `a body that needs no fence is written without one`() {
        val sb = StringBuilder()
        ChatHistoryManager.appendBody(sb, "just an ordinary answer")

        assertEquals("just an ordinary answer\n", sb.toString())
    }

    @Test
    fun `the fence marker itself is escaped by numbering the pair`() {
        // The marker is chosen so neither line occurs in the body, otherwise
        // the body would terminate at its own text. The body also has to need
        // a fence at all — a plain body is written unwrapped, so a marker
        // collision can only arise on top of a dangerous line.
        val content = "---\n<<< pokeclaw-body\ninside\n<<< pokeclaw-body"

        val sb = StringBuilder()
        ChatHistoryManager.appendBody(sb, content)

        val written = sb.toString()
        assertTrue("numbered marker used: $written", written.contains("<<< pokeclaw-body-1"))
        assertTrue("matching close used: $written", written.contains(">>> pokeclaw-body-1"))

        // And the reader gets the whole body back, not the part before its own
        // marker.
        val file = transcript(message(ChatMessage.Role.ASSISTANT, content))
        assertEquals(content, ChatHistoryManager.load(file).single().content)
    }

    @Test
    fun `a legacy file with no fence still loads exactly as before`() {
        // Backward compatibility: transcripts already on users' devices have
        // no marker, and a blank line inside a body is dropped by the legacy
        // path — that behaviour is preserved, not silently changed.
        val file = transcript(
            "## User\n<!-- pokeclaw:timestamp=1792678558202 -->\nhello\n\nworld\n" +
                "## 🦞 Assistant [local]\n<!-- pokeclaw:timestamp=1792678558203 -->\nhi there\n"
        )

        val messages = ChatHistoryManager.load(file)

        assertEquals(2, messages.size)
        assertEquals("hello\nworld", messages[0].content)
        assertEquals("hi there", messages[1].content)
        assertEquals("local", messages[1].modelName)
    }

    @Test
    fun `a tool group whose raw text contains a structural line survives`() {
        // A group the loader could not parse keeps its raw text. If that text
        // contained "## User", re-saving would split it into a new message.
        val group = ChatMessage(
            role = ChatMessage.Role.TOOL_GROUP,
            content = "- ✓ 截屏 → done\n## User\nnot a real header",
            toolSteps = null,
        )

        val sb = StringBuilder()
        sb.appendLine("## Tools")
        sb.appendLine("<!-- pokeclaw:timestamp=1792678558202 -->")
        ChatHistoryManager.appendBody(sb, ChatHistoryManager.toolGroupLines(group).joinToString("\n"))

        val file = transcript(sb.toString())
        val restored = ChatHistoryManager.load(file).first { it.role == ChatMessage.Role.TOOL_GROUP }

        assertFalse("must not be parsed as steps", restored.toolSteps != null)
        assertEquals("- ✓ 截屏 → done\n## User\nnot a real header", restored.content)
    }

    @Test
    fun `a first message that looks like frontmatter does not forge the id`() {
        // The title is the first user message. Written raw, a newline in it
        // continues the frontmatter, and an "id: …" line inside it is read
        // back as the conversation id — which is how the preview surface finds
        // its own history, so a forged id means it silently stops restoring.
        val sb = StringBuilder()
        ChatHistoryManager.appendFrontmatter(
            sb = sb,
            conversationId = "preview_claude_ui",
            firstUserContent = "hello\nid: forged",
            createdMillis = 1792678558202L,
            model = "local",
        )
        sb.append(message(ChatMessage.Role.USER, "hello"))

        val file = File.createTempFile("pokeclaw-fm", ".md")
        file.writeText(sb.toString())
        val lines = file.readLines()

        assertEquals("id: preview_claude_ui", lines.first { it.startsWith("id: ") })
        assertEquals("title: hello id: forged", lines.first { it.startsWith("title: ") })
        assertEquals(2, lines.count { it == "---" })
        assertEquals(1, ChatHistoryManager.load(file).size)
        file.delete()
    }
}
