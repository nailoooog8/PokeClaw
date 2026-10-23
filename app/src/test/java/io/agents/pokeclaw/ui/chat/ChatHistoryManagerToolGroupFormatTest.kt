package io.agents.pokeclaw.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the writer side of the tool-group format.
 *
 * `save()` needs an Android context (it writes to disk and indexes in
 * SQLite), so the line layout is exercised through
 * [ChatHistoryManager.toolGroupLines] instead — the same function the writer
 * uses. What matters is that the lines it produces are lines the reader
 * accepts, and that nothing is dropped on the way out.
 */
class ChatHistoryManagerToolGroupFormatTest {

    private fun group(
        content: String = "",
        steps: List<ToolStep>? = null,
    ) = ChatMessage(
        role = ChatMessage.Role.TOOL_GROUP,
        content = content,
        toolSteps = steps,
    )

    @Test
    fun `a live group writes one line per tool call`() {
        val lines = ChatHistoryManager.toolGroupLines(
            group(
                steps = listOf(
                    ToolStep("截屏", "/tmp/a.png", success = true),
                    ToolStep("send_file", "failed", success = false),
                )
            )
        )

        assertEquals(listOf("- ✓ 截屏 → /tmp/a.png", "- ○ send_file → failed"), lines)
    }

    @Test
    fun `a group the loader could not parse is written back as it was read`() {
        // The loader keeps an unrecognised block as raw text. If the writer
        // only wrote `toolSteps`, that text would be dropped the next time
        // the conversation is saved — and the chat page draws tool groups from
        // `toolSteps` alone, so the tool call would vanish for good.
        val raw = "- ✓ 截屏 → /tmp/a.png"

        val lines = ChatHistoryManager.toolGroupLines(group(content = raw, steps = null))

        assertEquals(listOf(raw), lines)
    }

    @Test
    fun `a summary with a newline stays on one line`() {
        // The format is line-oriented: a raw newline would split one tool
        // call into two lines, and the reader recognises a block only when
        // every line matches.
        val lines = ChatHistoryManager.toolGroupLines(
            group(
                steps = listOf(
                    ToolStep("read_screen", "line one\nline two", success = true)
                )
            )
        )

        assertEquals(1, lines.size)
        assertEquals("- ✓ read_screen → line one line two", lines[0])
    }

    @Test
    fun `what the writer produces is what the reader accepts`() {
        val lines = ChatHistoryManager.toolGroupLines(
            group(
                steps = listOf(
                    ToolStep("截屏", "/tmp/a.png", success = true),
                    ToolStep("send_file", "failed", success = false),
                    ToolStep("read_screen", "line one\nline two", success = true),
                )
            )
        )

        val file = java.io.File.createTempFile("pokeclaw-format", ".md")
        file.writeText(
            buildString {
                appendLine("## Tools")
                lines.forEach { appendLine(it) }
            }
        )

        val restored = ChatHistoryManager.load(file)
            .first { it.role == ChatMessage.Role.TOOL_GROUP }

        assertEquals(3, restored.toolSteps!!.size)
        assertEquals("截屏", restored.toolSteps!![0].toolName)
        assertTrue(restored.toolSteps!![0].success)
        assertEquals("read_screen", restored.toolSteps!![2].toolName)
        assertEquals("line one line two", restored.toolSteps!![2].summary)
        file.delete()
    }
}
