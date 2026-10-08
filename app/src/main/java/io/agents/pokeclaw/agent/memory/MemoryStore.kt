// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.memory

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.agents.pokeclaw.utils.KVUtils
import io.agents.pokeclaw.utils.XLog
import java.io.File
import java.security.MessageDigest
import kotlin.math.min

/**
 * Long-term memory bank — what the agent remembers between sessions.
 *
 * Storage: `memories.json` (JSON array of [MemoryEntry]) in the app's external
 * files dir, falling back to the internal one. Same adb-pushable layout as
 * mcp_servers.json and skills/, so the bank can be inspected, hand-edited or
 * seeded from a computer.
 *
 * Three ways it is used:
 *  - [briefing] prepends the most relevant entries to the system prompt, so the
 *    agent starts each task already knowing who the user is and what they like.
 *  - the `memory_save` / `memory_recall` / `memory_forget` tools let the agent
 *    and the user curate it from inside a conversation.
 *  - the memory manager UI (ui/settings/MemoryActivity) for direct editing.
 *
 * Ranking is deliberately cheap and local (no embeddings): term overlap with
 * the current task, kind weight, and the access-weighted retention curve in
 * [MemoryRetention] — which is where "how often this proved useful" lives.
 * Pinned entries bypass decay and are always injected.
 */
object MemoryStore {

    private const val TAG = "MemoryStore"
    private const val FILE_NAME = "memories.json"
    private const val SUPPRESSION_FILE = "memory_suppressions.json"

    /** Hard cap on stored entries; prompt budget and disk both stay bounded. */
    const val MAX_ENTRIES = 200

    /** Entries injected into the system prompt per task. */
    const val BRIEFING_LIMIT = 12

    /** Character budget for the injected briefing section. */
    private const val BRIEFING_CHAR_BUDGET = 1400

    private val gson: Gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val lock = Any()

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cache: MutableList<MemoryEntry>? = null

    data class Scored(val entry: MemoryEntry, val score: Double)

    /** Outcome of a write attempt, so callers can tell "stored" from "refused". */
    sealed interface RememberResult {
        /** [conflicts] holds entries the new fact appears to contradict. */
        data class Stored(
            val entry: MemoryEntry,
            val merged: Boolean = false,
            val conflicts: List<MemoryEntry> = emptyList()
        ) : RememberResult

        /** Blocked by a suppression fingerprint left behind when the user forgot it. */
        data class Suppressed(val trace: MemorySimilarity.Trace) : RememberResult
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Called from ClawApplication.onCreate and from the manager UI. */
    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    @JvmStatic
    fun isEnabled(): Boolean = KVUtils.isMemoryEnabled()

    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        KVUtils.setMemoryEnabled(enabled)
        XLog.i(TAG, "Memory bank ${if (enabled) "enabled" else "disabled"}")
    }

    /** Drops the in-memory cache; the next read re-parses the files. */
    @JvmStatic
    fun invalidate() {
        synchronized(lock) {
            cache = null
            suppressions = null
        }
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    /** All entries, newest first. Loads from disk on first call. */
    @JvmStatic
    fun all(): List<MemoryEntry> = synchronized(lock) {
        loadLocked().sortedByDescending { it.updatedAt }
    }

    @JvmStatic
    fun count(): Int = synchronized(lock) { loadLocked().size }

    @JvmStatic
    fun findById(id: String): MemoryEntry? =
        synchronized(lock) { loadLocked().firstOrNull { it.id == id } }

    /**
     * Ranked retrieval. A blank [query] falls back to pinned + most recently
     * used entries, which is what the model wants when it asks "what do you
     * remember" without naming a topic.
     *
     * Matching entries are reinforced (hits+1, lastUsedAt=now) and the bank is
     * rewritten, so the ranking improves with use.
     */
    @JvmStatic
    fun recall(query: String, limit: Int = 5, kind: String? = null): List<Scored> {
        val qTerms = terms(query)
        val now = System.currentTimeMillis()
        return synchronized(lock) {
            val entries = loadLocked()
            val pool = if (kind.isNullOrBlank() || !MemoryKind.isValid(kind)) {
                entries
            } else {
                entries.filter { it.kind == kind }
            }
            val hits = pool.filter { matches(it, qTerms) || qTerms.isEmpty() }
                .map { score(it, qTerms, now) to it }
                .sortedByDescending { it.first }
                .take(min(limit.coerceIn(1, 25), BRIEFING_LIMIT))
                .map { it.second }

            if (hits.isNotEmpty()) {
                val ids = hits.map { it.id }.toSet()
                val merged = entries.map { e ->
                    if (e.id in ids) e.copy(hits = e.hits + 1, lastUsedAt = now) else e
                }
                cache = merged.toMutableList()
                persistLocked()
            }
            hits.map { Scored(it, score(it, qTerms, now)) }
        }
    }

    // ── Writes ────────────────────────────────────────────────────────────────

    /**
     * Adds a memory, or merges into an existing one when the content overlaps.
     *
     * Merging matters because the same fact gets re-learned in slightly
     * different words across sessions; without it the briefing fills up with
     * near-duplicates and the model starts contradicting itself.
     *
     * Two guards sit in front of the write:
     *  - [fromAgent] writes are refused when they resemble something the user
     *    deleted (a suppression fingerprint), so the agent cannot re-learn a
     *    fact that was asked to be forgotten. Manual UI edits are exempt — an
     *    explicit action by the user outranks the fingerprint.
     *  - writes that contradict what is already stored are still stored, but
     *    the conflict is reported and both sides are flagged, so the model
     *    asks instead of silently believing whichever it reads first.
     */
    @JvmStatic
    @JvmOverloads
    fun remember(
        content: String,
        kind: String = MemoryKind.FACT,
        tags: List<String> = emptyList(),
        pinned: Boolean = false,
        id: String? = null,
        fromAgent: Boolean = false
    ): RememberResult {
        val text = content.trim()
        require(text.isNotEmpty()) { "content is empty" }
        val now = System.currentTimeMillis()
        val cleanTags = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()
        val cleanKind = kind.lowercase().let { if (MemoryKind.isValid(it)) it else MemoryKind.FACT }
        val norm = normalize(text)

        return synchronized(lock) {
            val entries = loadLocked().toMutableList()

            // Explicit edit of an existing entry: overwrite it.
            if (id != null) {
                val idx = entries.indexOfFirst { it.id == id }
                if (idx >= 0) {
                    val old = entries[idx]
                    entries[idx] = old.copy(
                        content = text,
                        kind = cleanKind,
                        tags = (old.tags + cleanTags).distinct(),
                        pinned = pinned || old.pinned,
                        updatedAt = now
                    )
                    cache = entries
                    persistLocked()
                    return RememberResult.Stored(entries[idx])
                }
            }

            // Suppression: refuse to let the agent re-learn what was forgotten.
            // Runs *before* the merge check below — otherwise a forgotten fact
            // that happens to overlap a live one is quietly merged back in
            // instead of being refused, which is the one outcome suppression
            // exists to prevent.
            if (fromAgent) {
                val trace = suppressionLocked(text)
                if (trace != null) {
                    bumpSuppressionLocked(trace)
                    XLog.i(TAG, "memory_save refused — resembles a forgotten memory (${trace.id})")
                    return RememberResult.Suppressed(trace)
                }
            }

            // Duplicate / near-duplicate: merge into the existing entry.
            val dup = entries.firstOrNull { overlaps(norm, normalize(it.content)) }
            if (dup != null) {
                val merged = dup.copy(
                    content = if (text.length > dup.content.length) text else dup.content,
                    kind = if (MemoryKind.weight(cleanKind) > MemoryKind.weight(dup.kind)) cleanKind else dup.kind,
                    tags = (dup.tags + cleanTags).distinct(),
                    pinned = dup.pinned || pinned,
                    hits = dup.hits + 1,
                    lastUsedAt = now,
                    updatedAt = now
                )
                entries[entries.indexOf(dup)] = merged
                cache = entries
                persistLocked()
                XLog.d(TAG, "memory_save merged into ${merged.id} (${merged.content.take(40)})")
                return RememberResult.Stored(merged, merged = true)
            }

            // Contradiction: keep the new fact, but flag both sides so the model asks.
            val conflicts = entries.filter { MemorySimilarity.contradicts(it.content, text) }
            val entry = MemoryEntry(
                id = idOf(norm),
                content = text,
                kind = cleanKind,
                tags = cleanTags,
                pinned = pinned,
                createdAt = now,
                updatedAt = now,
                conflictsWith = conflicts.map { it.id }
            )
            if (conflicts.isNotEmpty()) {
                // The older side keeps pointing at the new one.
                for (i in entries.indices) {
                    val old = entries[i]
                    if (old.id in entry.conflictsWith) {
                        entries[i] = old.copy(conflictsWith = (old.conflictsWith + entry.id).distinct())
                    }
                }
                XLog.w(TAG, "memory_save: ${conflicts.size} possible conflict(s) with \"${text.take(40)}\"")
            }
            entries.add(entry)
            evictLocked(entries, now)
            cache = entries
            persistLocked()
            XLog.i(TAG, "memory_save: ${entry.kind} \"${entry.content.take(40)}\" (${entries.size} total)")
            RememberResult.Stored(entry, conflicts = conflicts)
        }
    }

    /**
     * Deletes one entry and leaves a suppression fingerprint behind.
     *
     * The fingerprint is what makes forgetting stick: without it the agent
     * re-learns the same fact a few conversations later and the user gets it
     * back without ever asking for it.
     */
    @JvmStatic
    fun delete(id: String): Boolean = synchronized(lock) {
        val entries = loadLocked()
        val victim = entries.firstOrNull { it.id == id } ?: return false
        val kept = entries.filterNot { it.id == id }
        addSuppressionLocked(victim.content, victim.kind)
        // Point the other side of a conflict back into thin air.
        val repaired = kept.map { e ->
            if (e.conflictsWith.contains(id)) {
                e.copy(conflictsWith = e.conflictsWith - id)
            } else e
        }
        cache = repaired.toMutableList()
        persistLocked()
        true
    }

    /** Deletes every entry whose content contains [query]. Returns how many went. */
    @JvmStatic
    fun deleteByContent(query: String): Int = synchronized(lock) {
        val q = query.trim()
        if (q.isEmpty()) return 0
        val entries = loadLocked()
        val victims = entries.filter {
            it.content.contains(q, ignoreCase = true) ||
                (it.tags.any { tag -> tag.contains(q, ignoreCase = true) } && q.length >= 3)
        }
        if (victims.isEmpty()) return 0
        for (v in victims) addSuppressionLocked(v.content, v.kind)
        val kept = entries.filterNot { it in victims }
        cache = kept.toMutableList()
        persistLocked()
        victims.size
    }

    @JvmStatic
    fun setPinned(id: String, pinned: Boolean): Boolean = synchronized(lock) {
        val entries = loadLocked().toMutableList()
        val idx = entries.indexOfFirst { it.id == id }
        if (idx < 0) return false
        entries[idx] = entries[idx].copy(pinned = pinned, updatedAt = System.currentTimeMillis())
        cache = entries
        persistLocked()
        true
    }

    @JvmStatic
    fun clear(): Int = synchronized(lock) {
        val n = loadLocked().size
        cache = mutableListOf()
        suppressions = mutableListOf()
        persistLocked()
        persistSuppressionsLocked()
        n
    }

    // ── Suppression fingerprints ──────────────────────────────────────────────

    @Volatile
    private var suppressions: MutableList<MemorySimilarity.Trace>? = null

    @JvmStatic
    fun suppressionCount(): Int = synchronized(lock) { loadSuppressionsLocked().size }

    @JvmStatic
    @Suppress("unused")
    fun suppressions(): List<MemorySimilarity.Trace> =
        synchronized(lock) { loadSuppressionsLocked().toList() }

    /** Lifts every fingerprint — after this the agent may learn those facts again. */
    @JvmStatic
    fun clearSuppressions(): Int = synchronized(lock) {
        val n = loadSuppressionsLocked().size
        suppressions = mutableListOf()
        persistSuppressionsLocked()
        n
    }

    /**
     * Lifts one fingerprint, leaving the rest in force.
     *
     * This is the way back from a deletion done by mistake, without having to
     * give the agent the whole suppression bank again.
     */
    @JvmStatic
    fun clearSuppression(id: String): Boolean = synchronized(lock) {
        val list = loadSuppressionsLocked()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return false
        list.removeAt(idx)
        persistSuppressionsLocked()
        XLog.i(TAG, "Suppression lifted for ${list.size} remaining")
        true
    }

    /**
     * Undoes a deletion: the forgotten memory goes back into the bank and its
     * fingerprint goes away with it.
     *
     * Written as a pair of options on purpose — [clearSuppression] is for "I did
     * not mean to delete that", this is for "I deleted it and I want it back".
     */
    @JvmStatic
    fun restoreSuppression(id: String): Boolean {
        val trace = synchronized(lock) {
            val list = loadSuppressionsLocked()
            val idx = list.indexOfFirst { it.id == id }
            if (idx < 0) return false
            list.removeAt(idx).also { persistSuppressionsLocked() }
        }
        // Not fromAgent: the user just asked for this one back, and restoring it
        // must not be re-checked against the fingerprint we are lifting.
        remember(trace.text, trace.kind, emptyList(), fromAgent = false)
        XLog.i(TAG, "Restored a forgotten memory (${MemoryStore.count()} total)")
        return true
    }

    private fun loadSuppressionsLocked(): MutableList<MemorySimilarity.Trace> {
        suppressions?.let { return it }
        val loaded = try {
            val file = suppressionFile()
            if (file.exists()) MemorySimilarity.Traces.parse(file.readText()) else emptyList()
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to read $SUPPRESSION_FILE", e)
            emptyList()
        }
        suppressions = loaded.toMutableList()
        return suppressions!!
    }

    private fun persistSuppressionsLocked(): Boolean {
        return try {
            suppressionFile().writeText(gson.toJson(suppressions ?: mutableListOf<MemorySimilarity.Trace>()))
            true
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to persist $SUPPRESSION_FILE", e)
            false
        }
    }

    /** First fingerprint that [text] falls under, or null. */
    private fun suppressionLocked(text: String): MemorySimilarity.Trace? =
        loadSuppressionsLocked().firstOrNull {
            MemorySimilarity.isSuppressedBy(it.text, text)
        }

    private fun addSuppressionLocked(text: String, kind: String = MemoryKind.FACT) {
        val list = loadSuppressionsLocked()
        val clean = text.trim()
        if (clean.isEmpty()) return
        if (list.any { MemorySimilarity.isSuppressedBy(it.text, clean) }) {
            return
        }
        list.add(0, MemorySimilarity.Trace(
            id = MemorySimilarity.idOf(normalize(clean)),
            text = clean,
            at = System.currentTimeMillis(),
            kind = if (MemoryKind.isValid(kind)) kind else MemoryKind.FACT
        ))
        while (list.size > MemorySimilarity.Traces.MAX_TRACES) list.removeAt(list.size - 1)
        persistSuppressionsLocked()
        XLog.i(TAG, "Suppression recorded for a forgotten memory (${list.size} total)")
    }

    private fun bumpSuppressionLocked(trace: MemorySimilarity.Trace) {
        val list = loadSuppressionsLocked()
        val idx = list.indexOfFirst { it.id == trace.id }
        if (idx < 0) return
        list[idx] = trace.copy(blocked = trace.blocked + 1)
        persistSuppressionsLocked()
    }

    /** Writes the bank as markdown, for sharing or archiving. Returns the file. */
    @JvmStatic
    fun exportMarkdown(): File? {
        val entries = all()
        if (entries.isEmpty()) return null
        return try {
            val file = File(storageDir(), "memories-export.md")
            val sb = StringBuilder("# 记忆库导出\n\n> 共 ${entries.size} 条，导出于 ${file.name}\n\n")
            for (kind in MemoryKind.all) {
                val group = entries.filter { it.kind == kind }
                if (group.isEmpty()) continue
                sb.append("## ${MemoryKind.labelOf(kind)}\n\n")
                for (e in group) {
                    val tags = if (e.tags.isEmpty()) "" else "  #${e.tags.joinToString(" #")}"
                    val pin = if (e.pinned) " 📌" else ""
                    sb.append("- ${e.content}$pin$tags\n")
                }
                sb.append('\n')
            }
            file.writeText(sb.toString())
            XLog.i(TAG, "Exported ${entries.size} memories to ${file.absolutePath}")
            file
        } catch (e: Exception) {
            XLog.e(TAG, "Export failed", e)
            null
        }
    }

    // ── Prompt integration ────────────────────────────────────────────────────

    /**
     * System-prompt section describing the memory bank and the entries relevant
     * to [query]. Returns "" when the bank is disabled and empty.
     *
     * The injected text also teaches the model the three memory tools, so a
     * cold start (no memories yet) still gets the behaviour we want.
     */
    @JvmStatic
    fun briefing(query: String): String {
        if (!isEnabled()) return ""
        val entries = try {
            synchronized(lock) { loadLocked().toList() }
        } catch (e: Exception) {
            XLog.e(TAG, "briefing failed", e)
            emptyList()
        }

        val sb = StringBuilder()
        sb.append("\n\n## 长期记忆（Memory Bank）\n")
        if (entries.isEmpty()) {
            sb.append("记忆库目前是空的。\n")
        } else {
            val picked = pick(query, entries)
            sb.append("关于用户和过往任务的长期记忆，按与当前任务的相关性排序：\n")
            for (scored in picked) {
                val e = scored.entry
                val tags = if (e.tags.isEmpty()) "" else "（${e.tags.joinToString("、")}）"
                val warn = if (e.conflictsWith.isEmpty()) "" else " ⚠可能与另一条记忆矛盾"
                sb.append("- [${e.kindLabel}] ${e.content}$tags$warn\n")
            }
            sb.append("共 ${entries.size} 条。\n")
        }
        sb.append(
            "\n记忆使用规则：\n" +
                "1. 记忆可能已经过时或与用户当前的说法矛盾——冲突时以用户当前的表述为准。\n" +
                "2. 标了 ⚠ 的条目彼此矛盾，不能只凭其中一条回答——应当直接问用户哪个是对的。\n" +
                "3. 值得跨会话记住的事实时调用 memory_save（身份、偏好、设备/账号事实、\n" +
                "   踩过的坑、完成过的重要任务的结果）。一次一条，别把临时状态也存进来。\n" +
                "4. 需要更多历史时用 memory_recall 按主题检索；记忆确实作废时用 memory_forget 删除。\n" +
                "   被用户删掉的记忆留有抑制指纹，memory_save 会拒绝写回相似内容——措辞相近或\n" +
                "   同一个说法换了说法（次数变了、型号变了）都算。除非用户明确要求你重新记住，\n" +
                "   此时请让用户在 设置 → 工具 → 记忆库 → 遗忘抑制 里自行恢复，不要反复重试。\n"
        )
        return sb.toString()
    }

    /** Pinned entries first, then the best-scoring others, within the char budget. */
    private fun pick(query: String, entries: List<MemoryEntry>): List<Scored> {
        val qTerms = terms(query)
        val scored = entries.map { Scored(it, score(it, qTerms, System.currentTimeMillis())) }
            .sortedByDescending { it.score }
        val out = mutableListOf<Scored>()
        var chars = 0
        for (s in scored) {
            val pinned = s.entry.pinned
            if (!pinned && out.size >= BRIEFING_LIMIT) break
            val line = s.entry.content.length
            if (chars + line > BRIEFING_CHAR_BUDGET && out.isNotEmpty()) continue
            out.add(s)
            chars += line
        }
        return out
    }

    // ── Storage ───────────────────────────────────────────────────────────────

    private fun storageDir(): File {
        val ctx = appContext
        val dir = ctx?.getExternalFilesDir(null) ?: ctx?.filesDir
            ?: throw IllegalStateException("MemoryStore.init(context) was never called")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun storageFile(): File = File(storageDir(), FILE_NAME)

    private fun suppressionFile(): File = File(storageDir(), SUPPRESSION_FILE)

    private fun loadLocked(): MutableList<MemoryEntry> {
        cache?.let { return it }
        val loaded = try {
            val file = storageFile()
            if (file.exists()) MemoryEntry.parseList(file.readText()) else emptyList()
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to read $FILE_NAME — starting from an empty bank", e)
            emptyList()
        }
        cache = loaded.toMutableList()
        XLog.i(TAG, "Loaded ${loaded.size} memories from $FILE_NAME")
        return cache!!
    }

    private fun persistLocked(): Boolean {
        return try {
            val file = storageFile()
            val tmp = File(storageDir(), "$FILE_NAME.tmp")
            tmp.writeText(gson.toJson(cache ?: mutableListOf<MemoryEntry>()))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
            true
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to persist $FILE_NAME", e)
            false
        }
    }

    /**
     * Drops the weakest unpinned entries once the bank exceeds [MAX_ENTRIES].
     *
     * Ordered by retention rather than raw [MemoryEntry.hits]: the entries that
     * lose their claim first are the ones nobody has recalled in a long time,
     * which a hit count alone cannot tell apart from a frequently-recalled entry
     * that was written recently.
     *
     * Deliberately leaves **no suppression fingerprint**. Running out of room is
     * not evidence the user stopped wanting the memory — [delete] is what does
     * that. If eviction ever gains a fingerprint, the agent would be forbidden
     * from re-learning facts nobody asked it to forget, and the two cases would
     * have become indistinguishable on disk.
     */
    private fun evictLocked(entries: MutableList<MemoryEntry>, now: Long) {
        if (entries.size <= MAX_ENTRIES) return
        entries.sortWith(
            compareByDescending<MemoryEntry> { it.pinned }
                .thenByDescending { MemoryRetention.retention(it, now) }
                .thenBy { it.updatedAt }
        )
        while (entries.size > MAX_ENTRIES) {
            val victim = entries.lastOrNull { !it.pinned } ?: break
            entries.remove(victim)
            XLog.d(TAG, "Evicted ${victim.id} (bank over $MAX_ENTRIES entries)")
        }
        entries.sortByDescending { it.updatedAt }
    }

    // ── Matching helpers ──────────────────────────────────────────────────────

    /**
     * Ranking for one entry against the current task.
     *
     * Recency is handled by [MemoryRetention], not here: an entry's usefulness
     * is an access-weighted curve over its own age, and that deserves to be
     * stated once ([MemoryRetention]) rather than re-derived per ranking term.
     * There is deliberately no separate `hits` bonus — [recall] bumps `hits`
     * and `lastUsedAt` together, so counting hits twice would reward the same
     * signal twice and cap it at seven recalls besides.
     */
    private fun score(e: MemoryEntry, qTerms: Set<String>, now: Long): Double {
        var s = MemoryKind.weight(e.kind)
        if (e.pinned) s += 3.0
        if (qTerms.isNotEmpty() && matches(e, qTerms)) {
            s += 2.0
            val tagHits = e.tags.count { tag -> qTerms.any { it in tag } }
            s += min(1.5, 0.5 * tagHits)
        }
        // Slow decay so stale facts drift out of the briefing on their own.
        s += MemoryRetention.scoreTerm(e, now)
        return s
    }

    private fun matches(e: MemoryEntry, qTerms: Set<String>): Boolean {
        if (qTerms.isEmpty()) return false
        val content = e.content.lowercase()
        for (t in qTerms) {
            if (content.contains(t)) return true
            if (e.tags.any { tag -> t in tag }) return true
        }
        return false
    }

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("\\s+"), " ").trim()

    /** True when one normalized string contains the other — near-duplicate guard. */
    private fun overlaps(a: String, b: String): Boolean =
        a == b || (a.length >= 4 && b.contains(a)) || (b.length >= 4 && a.contains(b))

    private fun idOf(normalized: String): String = MemorySimilarity.idOf(normalized)

    /**
     * Query/content terms. Latin runs must be ≥2 chars; CJK runs are indexed as
     * bigrams, which is what makes recall work for Chinese without a segmenter.
     */
    private fun terms(text: String): Set<String> {
        val out = LinkedHashSet<String>()
        val latin = StringBuilder()
        val cjk = StringBuilder()

        fun flushLatin() {
            if (latin.length >= 2) out.add(latin.toString())
            latin.setLength(0)
        }

        fun flushCjk() {
            val s = cjk.toString()
            if (s.length == 1) out.add(s) else if (s.length > 1) {
                for (i in 0 until s.length - 1) out.add(s.substring(i, i + 2))
            }
            cjk.setLength(0)
        }

        for (c in text.lowercase()) {
            when {
                isCjk(c) -> { flushLatin(); cjk.append(c) }
                c.isLetterOrDigit() -> { flushCjk(); latin.append(c) }
                else -> { flushLatin(); flushCjk() }
            }
        }
        flushLatin()
        flushCjk()
        return out
    }

    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF
    }
}