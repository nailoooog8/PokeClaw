// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.ui.chat

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves and loads chat conversations as markdown files.
 *
 * Storage: /storage/emulated/0/PokeClaw/chats/
 * Format: 2026-04-04-send-hi-to-mom.md
 *
 * Each file:
 * ---
 * title: Send hi to Mom
 * created: 2026-04-04T15:30:00
 * model: FunctionGemma-270M
 * ---
 *
 * ## User
 * Open WhatsApp and send hi to Mom
 *
 * ## 🦞 Assistant
 * On it. Checking your screen.
 *
 * ## System
 * Task completed.
 */
object ChatHistoryManager {

    private const val MESSAGE_TIMESTAMP_PREFIX = "<!-- pokeclaw:timestamp="
    private const val MESSAGE_TIMESTAMP_SUFFIX = " -->"

    /** The "- ✓ name → summary" line layout written for tool groups. */
    private val TOOL_LINE = Regex("^-\\s*([✓○])\\s*(.+)$")
    private const val TOOL_ARROW = "→"
    private const val TOOL_ICON_OK = "✓"

    /**
     * Markers that fence a message body which the line-oriented reader would
     * otherwise misread as structure. See [appendBody].
     */
    private const val BODY_OPEN = "<<< pokeclaw-body"
    private const val BODY_CLOSE = ">>> pokeclaw-body"
    private val frontmatterDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    data class ConversationSummary(
        val id: String,
        val title: String,
        val created: Long,
        val file: File
    )

    private fun getChatDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "chats")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Save a conversation to markdown file.
     */
    fun save(context: Context, conversationId: String, messages: List<ChatMessage>, model: String) {
        if (messages.isEmpty()) return

        // Generate title from first user message
        val firstUserMsg = messages.firstOrNull { it.role == ChatMessage.Role.USER }
        val title = firstUserMsg?.content?.take(50)?.replace(Regex("[^a-zA-Z0-9\\s]"), "")?.trim()?.replace("\\s+".toRegex(), "-")?.lowercase() ?: "untitled"
        val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(messages.first().timestamp))
        val fileName = "$dateStr-$title.md"

        val file = File(getChatDir(context), fileName)

        val sb = StringBuilder()
        appendFrontmatter(sb, conversationId, firstUserMsg?.content, messages.first().timestamp, model)

        // Messages
        messages.forEach { msg ->
            when (msg.role) {
                ChatMessage.Role.USER -> {
                    sb.appendLine("## User")
                    sb.appendLine(serializeTimestamp(msg.timestamp))
                    appendBody(sb, msg.content)
                    sb.appendLine()
                }
                ChatMessage.Role.ASSISTANT -> {
                    if (!msg.modelName.isNullOrEmpty()) {
                        sb.appendLine("## 🦞 Assistant [${msg.modelName}]")
                    } else {
                        sb.appendLine("## 🦞 Assistant")
                    }
                    sb.appendLine(serializeTimestamp(msg.timestamp))
                    appendBody(sb, msg.content)
                    sb.appendLine()
                }
                ChatMessage.Role.SYSTEM -> {
                    sb.appendLine("## System")
                    sb.appendLine(serializeTimestamp(msg.timestamp))
                    appendBody(sb, msg.content)
                    sb.appendLine()
                }
                ChatMessage.Role.TOOL_GROUP -> {
                    sb.appendLine("## Tools")
                    sb.appendLine(serializeTimestamp(msg.timestamp))
                    appendBody(sb, toolGroupLines(msg).joinToString("\n"))
                    sb.appendLine()
                }
            }
        }

        file.writeText(sb.toString())

        // Index in SQLite for fast search
        try {
            ChatDatabase(context).indexConversation(
                id = conversationId,
                title = firstUserMsg?.content?.take(80) ?: "Untitled",
                created = messages.first().timestamp,
                model = model,
                filePath = file.absolutePath,
                messages = messages
            )
        } catch (_: Exception) { /* index failure is non-fatal */ }
    }

    /**
     * Load a conversation from markdown file.
     */
    fun load(file: File): List<ChatMessage> {
        if (!file.exists()) return emptyList()

        val messages = mutableListOf<ChatMessage>()
        val lines = file.readLines()

        var inFrontmatter = false
        var fallbackConversationTimestamp = file.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
        var currentRole: ChatMessage.Role? = null
        var currentModelName: String? = null
        var currentTimestamp: Long? = null
        val contentBuilder = StringBuilder()
        // Non-null while inside a fenced body: everything up to the matching
        // close marker is message text, verbatim.
        var bodyClose: String? = null

        for (line in lines) {
            if (bodyClose != null) {
                if (line == bodyClose) bodyClose = null else contentBuilder.appendLine(line)
                continue
            }
            if (line == "---") {
                if (!inFrontmatter) { inFrontmatter = true; continue }
                else { inFrontmatter = false; continue }
            }
            if (inFrontmatter) {
                if (line.startsWith("created: ")) {
                    parseFrontmatterTimestamp(line.removePrefix("created: "))?.let {
                        fallbackConversationTimestamp = it
                    }
                }
                continue
            }

            when {
                line.startsWith("## User") -> {
                    flushMessage(messages, currentRole, contentBuilder, currentModelName, currentTimestamp, fallbackConversationTimestamp)
                    currentRole = ChatMessage.Role.USER
                    currentModelName = null
                    currentTimestamp = null
                }
                line.startsWith("## 🦞 Assistant") -> {
                    flushMessage(messages, currentRole, contentBuilder, currentModelName, currentTimestamp, fallbackConversationTimestamp)
                    currentRole = ChatMessage.Role.ASSISTANT
                    // Extract model name from "## 🦞 Assistant [ModelName]"
                    val bracketMatch = Regex("\\[(.+)]").find(line)
                    currentModelName = bracketMatch?.groupValues?.get(1)
                    currentTimestamp = null
                }
                line.startsWith("## System") -> {
                    flushMessage(messages, currentRole, contentBuilder, currentModelName, currentTimestamp, fallbackConversationTimestamp)
                    currentRole = ChatMessage.Role.SYSTEM
                    currentModelName = null
                    currentTimestamp = null
                }
                line.startsWith("## Tools") -> {
                    flushMessage(messages, currentRole, contentBuilder, currentModelName, currentTimestamp, fallbackConversationTimestamp)
                    currentRole = ChatMessage.Role.TOOL_GROUP
                    currentModelName = null
                    currentTimestamp = null
                }
                currentRole != null && line.startsWith(MESSAGE_TIMESTAMP_PREFIX) -> {
                    currentTimestamp = parseMessageTimestamp(line)
                }
                // A fenced body starts here. Recognised only at the start of a
                // body (nothing accumulated yet) so an ordinary content line
                // that happens to look like the marker is left alone.
                currentRole != null && contentBuilder.isEmpty() && line.startsWith(BODY_OPEN) -> {
                    bodyClose = BODY_CLOSE + line.removePrefix(BODY_OPEN)
                }
                else -> {
                    if (currentRole != null && line.isNotBlank()) {
                        if (contentBuilder.isNotEmpty()) contentBuilder.appendLine()
                        contentBuilder.append(line)
                    }
                }
            }
        }
        flushMessage(messages, currentRole, contentBuilder, currentModelName, currentTimestamp, fallbackConversationTimestamp)

        return messages
    }

    private fun flushMessage(
        messages: MutableList<ChatMessage>,
        role: ChatMessage.Role?,
        content: StringBuilder,
        modelName: String? = null,
        timestamp: Long? = null,
        fallbackConversationTimestamp: Long
    ) {
        if (role == null || content.isEmpty()) return
        val resolvedTimestamp = timestamp ?: (fallbackConversationTimestamp + messages.size * 1000L)
        // A tool group is written as one markdown line per tool call, so
        // loading it back as plain content throws the structure away: the
        // chat screen's ToolGroup() renders `toolSteps` and nothing else
        // (a restored group therefore drew an empty box), and the web
        // surface flattened it into one "- ✓ name → summary" paragraph.
        // Parse the lines back into the same shape the live message has
        // (content empty, toolSteps populated) so a reloaded transcript
        // looks like the one that was saved.
        val steps = if (role == ChatMessage.Role.TOOL_GROUP) parseToolSteps(content.toString()) else null
        messages.add(
            if (steps != null) {
                ChatMessage(
                    role = role,
                    content = "",
                    timestamp = resolvedTimestamp,
                    toolSteps = steps,
                    modelName = modelName
                )
            } else {
                // Unrecognised layout — keep the raw text rather than dropping
                // the tool calls on the floor.
                ChatMessage(
                    role = role,
                    content = content.toString().trim(),
                    timestamp = resolvedTimestamp,
                    modelName = modelName
                )
            }
        )
        content.clear()
    }

    /**
     * Parse the "- ✓ tool → summary" lines written by [save].
     *
     * Returns null when the block does not match that layout, so the caller
     * can keep the raw content instead of guessing. The split is on the FIRST
     * arrow: a summary may itself contain one (a path, a sentence), and the
     * tool name is the short part before it.
     */
    private fun parseToolSteps(content: String): List<ToolStep>? {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        return lines.map { line ->
            val match = TOOL_LINE.matchEntire(line) ?: return null
            val rest = match.groupValues[2]
            val arrow = rest.indexOf(TOOL_ARROW)
            ToolStep(
                toolName = if (arrow >= 0) rest.substring(0, arrow).trim() else rest,
                summary = if (arrow >= 0) rest.substring(arrow + TOOL_ARROW.length).trim() else "",
                success = match.groupValues[1] == TOOL_ICON_OK
            )
        }
    }

    private fun serializeTimestamp(timestamp: Long): String {
        return "$MESSAGE_TIMESTAMP_PREFIX$timestamp$MESSAGE_TIMESTAMP_SUFFIX"
    }

    /**
     * The lines a tool group occupies in the transcript.
     *
     * Two ways a group arrives here: a live one that carries `toolSteps`, and
     * one loaded from an older file whose layout the loader did not
     * recognise — that one keeps the raw text in `content`. Writing only the
     * steps would drop that text on the next save, so the fallback has to be
     * written back too.
     *
     * Internal rather than private so the format can be tested without an
     * Android context: `save()` needs one (it writes to disk and indexes in
     * SQLite), the line layout does not.
     */
    internal fun toolGroupLines(msg: ChatMessage): List<String> {
        val steps = msg.toolSteps ?: return listOf(msg.content)
        return steps.map { step ->
            val icon = if (step.success) "✓" else "○"
            "- $icon ${step.toolName.oneLine()} → ${step.summary.oneLine()}"
        }
    }

    /**
     * The `---` … `---` header block.
     *
     * Every value goes through [oneLine]. The title is the first user message,
     * so it is user- and model-controlled: written raw, a newline in it
     * continues the frontmatter — a `---` line would close the block early,
     * and an `id: …` line would be read back as the conversation id, which is
     * how the preview surface finds its own history. A forged id means that
     * lookup silently stops matching and the history stops restoring.
     *
     * Internal rather than private so it can be exercised without an Android
     * context; only [save] needs one.
     */
    internal fun appendFrontmatter(
        sb: StringBuilder,
        conversationId: String,
        firstUserContent: String?,
        createdMillis: Long,
        model: String,
    ) {
        sb.appendLine("---")
        sb.appendLine("id: ${conversationId.oneLine()}")
        sb.appendLine("title: ${firstUserContent?.take(80)?.oneLine() ?: "Untitled"}")
        sb.appendLine(
            "created: " + SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                .format(Date(createdMillis))
        )
        sb.appendLine("model: ${model.oneLine()}")
        sb.appendLine("---")
        sb.appendLine()
    }

    /**
     * Write a message body, fencing it when the reader would misread it.
     *
     * The transcript is line-oriented: `load()` decides what a line *is* from
     * the line itself — `---` toggles the frontmatter, `## User` / `## 🦞
     * Assistant` / `## System` / `## Tools` start a message, and the
     * `<!-- pokeclaw:timestamp=` prefix carries a timestamp. A model answer
     * containing any of those as its own line was therefore read back as
     * structure: `---` swallowed every following line as frontmatter, and a
     * `## User` line split one message into two and shifted the rest of the
     * transcript. The content is model-generated, so this is reachable rather
     * than theoretical.
     *
     * Bodies that contain none of those lines are written exactly as before —
     * no marker, no change for the ordinary conversation. Only a dangerous
     * body pays for a fence, which keeps existing files readable by humans
     * and keeps the unfenced path exercised on every load.
     *
     * The marker pair is chosen so that neither line occurs in the body: if
     * the content contains the marker itself, a numbered variant is used. A
     * fenced body is read back verbatim, so no escaping is needed and nothing
     * can be lost. Files written before this existed carry no marker, and the
     * reader still accepts them — including their dangerous lines, which is
     * the pre-existing behaviour, not a regression.
     *
     * Internal rather than private so the writer can be exercised without an
     * Android context: only [save] needs one (disk plus a SQLite index), and
     * this writes to a [StringBuilder].
     */
    internal fun appendBody(sb: StringBuilder, content: String) {
        if (!bodyNeedsFence(content)) {
            sb.append(content)
            sb.appendLine()
            return
        }
        val (open, close) = bodyMarkerPair(content)
        sb.appendLine(open)
        sb.append(content)
        sb.appendLine()
        sb.appendLine(close)
    }

    private fun bodyNeedsFence(content: String) = content.lines().any { line ->
        line == "---" ||
            line.startsWith("## User") ||
            line.startsWith("## 🦞 Assistant") ||
            line.startsWith("## System") ||
            line.startsWith("## Tools") ||
            line.startsWith(MESSAGE_TIMESTAMP_PREFIX)
    }

    /**
     * The marker pair to use for a body: the plain one unless the body
     * contains either marker line, in which case a numbered variant it does
     * not contain. Without this the fence would close on the body's own text
     * and the rest of the message would be read as structure again.
     */
    private fun bodyMarkerPair(content: String): Pair<String, String> {
        val bodyLines = content.lines()
        var suffix = ""
        while (bodyLines.any { it == BODY_OPEN + suffix || it == BODY_CLOSE + suffix }) {
            suffix = "-" + ((suffix.removePrefix("-").toIntOrNull() ?: 0) + 1)
        }
        return BODY_OPEN + suffix to BODY_CLOSE + suffix
    }

    /**
     * Collapse a tool name or summary onto one line.
     *
     * The transcript format is line-oriented — one tool call per line — so a
     * raw newline in a summary would split one call into two and the reader
     * would no longer recognise the block. A space keeps the line intact
     * without pretending the line break was never there.
     */
    private fun String.oneLine(): String =
        replace("\r\n", " ").replace("\n", " ").replace("\r", " ").trim()

    private fun parseMessageTimestamp(line: String): Long? {
        return line.removePrefix(MESSAGE_TIMESTAMP_PREFIX)
            .removeSuffix(MESSAGE_TIMESTAMP_SUFFIX)
            .toLongOrNull()
    }

    private fun parseFrontmatterTimestamp(raw: String): Long? {
        return try {
            frontmatterDateFormat.parse(raw.trim())?.time
        } catch (_: Exception) {
            null
        }
    }

    /**
     * List all saved conversations, newest first.
     */
    fun listConversations(context: Context): List<ConversationSummary> {
        val dir = getChatDir(context)
        if (!dir.exists()) return emptyList()

        return dir.listFiles()
            ?.filter { it.extension == "md" }
            ?.map { file ->
                var title = file.nameWithoutExtension
                var id = ""
                // Parse frontmatter for title
                file.useLines { lines ->
                    var inFm = false
                    for (line in lines) {
                        if (line == "---") { if (!inFm) { inFm = true; continue } else break }
                        if (inFm && line.startsWith("title: ")) title = line.removePrefix("title: ")
                        if (inFm && line.startsWith("id: ")) id = line.removePrefix("id: ")
                    }
                }
                ConversationSummary(
                    id = id.ifEmpty { file.nameWithoutExtension },
                    title = title,
                    created = file.lastModified(),
                    file = file
                )
            }
            ?.sortedByDescending { it.created }
            ?: emptyList()
    }

    /**
     * Rename a conversation by updating the title in the markdown frontmatter.
     */
    fun rename(file: File, newTitle: String): Boolean {
        if (!file.exists()) return false
        try {
            val content = file.readText()
            val updated = content.replaceFirst(
                Regex("(?m)^title: .+$"),
                "title: $newTitle"
            )
            file.writeText(updated)
            return true
        } catch (e: Exception) {
            io.agents.pokeclaw.utils.XLog.e("ChatHistoryManager", "Failed to rename conversation", e)
            return false
        }
    }

    /**
     * Delete a conversation.
     */
    fun delete(file: File): Boolean = file.delete()
}
