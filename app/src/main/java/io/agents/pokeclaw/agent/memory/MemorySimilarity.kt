// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.memory

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.security.MessageDigest

/**
 * Text-similarity helpers behind two behaviours of the memory bank:
 *
 *  - **suppression fingerprints** — a forgotten memory keeps a trace so the
 *    agent cannot quietly re-learn it later ("forgetting is inhibition, not
 *    deletion", the same call BetterAIChat2 makes);
 *  - **contradiction detection** — a new fact that keeps the sentence frame but
 *    swaps the value should be surfaced, not silently stored next to the old
 *    one where the model will pick whichever it happens to read first.
 *
 * All matching is lexical (CJK bigrams + Latin/number tokens). That misses
 * paraphrases and pure-CJK value swaps; it is a cheap guard rail, not NLU.
 */
object MemorySimilarity {

    /** Two traces at or above this Dice coefficient count as the same thing. */
    const val SUPPRESS_THRESHOLD = 0.70

    /** Sentences need this much shared skeleton before a value swap counts. */
    private const val CONFLICT_SKELETON_DICE = 0.60

    private const val MIN_SLOT_PREFIX = 5
    private const val MAX_SLOT_TAIL = 6

    private val NEGATIONS = setOf(
        "不", "别", "勿", "无", "非", "未", "没有", "别再", "不要", "不用",
        "不能", "不是", "不喜欢", "讨厌", "拒绝", "禁止", "取消", "停止"
    )

    /**
     * Numbers, versions, dates and quoted spans — the slots a sentence leaves
     * open for a value to change.
     *
     * Latin words are deliberately *not* in here: they are part of the frame
     * ("Pixel"/"小米" in "用户的手机是 ___"), so stripping them would empty the
     * skeleton of an English sentence and silently disable detection.
     */
    private val VALUE_REGEX = Regex(
        "\\d+(?:[.:/-]\\d+)*" +        // numbers, dates, versions
            "|[「『\"“”][^」』\"“”]*[」』\"“”]" // quoted spans
    )

    private val GSON = Gson()

    // ── Similarity ────────────────────────────────────────────────────────────

    /** Dice coefficient over character bigrams; the usual choice for CJK. */
    fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x == y) return 1.0
        if (x.length < 4 || y.length < 4) return 0.0
        return dice(bigrams(x), bigrams(y))
    }

    fun dice(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val shared = a.count { it in b }
        return 2.0 * shared / (a.size + b.size)
    }

    private fun normalize(text: String): String =
        text.lowercase().filter { !it.isWhitespace() && it !in "，。；：、！？,.;:!?（）()" }

    fun bigrams(text: String): Set<String> {
        val s = normalize(text)
        if (s.isEmpty()) return emptySet()
        if (s.length == 1) return setOf(s)
        return (0 until s.length - 1).map { s.substring(it, it + 2) }.toSet()
    }

    // ── Contradiction signals ─────────────────────────────────────────────────

    /** Numbers, dates, versions and quoted spans found in a sentence. */
    fun valuesOf(text: String): Set<String> =
        VALUE_REGEX.findAll(text).map { it.value.lowercase() }.toSet()

    /** The sentence with its values and polarity words removed. */
    fun skeletonOf(text: String): String {
        val stripped = VALUE_REGEX.replace(text, " ")
        val noPolarity = NEGATIONS.fold(stripped) { acc, w -> acc.replace(w, " ") }
        return normalize(noPolarity)
    }

    fun negationsOf(text: String): Set<String> =
        NEGATIONS.filterTo(LinkedHashSet()) { it in text }

    /** True when exactly one side carries a negation — i.e. one says the opposite. */
    fun polarityFlips(a: String, b: String): Boolean {
        val na = negationsOf(a)
        val nb = negationsOf(b)
        // Both plain statements are not a flip, and neither is a shared negation.
        return na.isEmpty() != nb.isEmpty()
    }

    /** True when both sides share a long prefix and only a short slot differs. */
    fun slotSwaps(a: String, b: String): Boolean {
        val x = normalize(a)
        val y = normalize(b)
        var i = 0
        while (i < x.length && i < y.length && x[i] == y[i]) i++
        if (i < MIN_SLOT_PREFIX) return false
        val tailA = x.substring(i)
        val tailB = y.substring(i)
        if (tailA.isEmpty() || tailB.isEmpty()) return false
        if (tailA.length > MAX_SLOT_TAIL || tailB.length > MAX_SLOT_TAIL) return false
        // "用微信聊天" vs "用微信和QQ聊天" is an extension, not a contradiction
        if (tailA in tailB || tailB in tailA) return false
        return true
    }

    /**
     * Whether [candidate] contradicts [existing].
     *
     * Fires on three conservative shapes:
     *  1. identical skeleton, different value tokens ("每周跑3次" → "每周跑5次")
     *  2. similar skeleton with a polarity flip ("喜欢" → "不喜欢")
     *  3. same sentence frame with a short value swapped ("用户在杭州" → "用户在上海")
     */
    fun contradicts(existing: String, candidate: String): Boolean {
        val skelExisting = skeletonOf(existing)
        val skelCandidate = skeletonOf(candidate)
        if (skelExisting == skelCandidate && skelExisting.length >= 4) {
            if (valuesOf(existing) != valuesOf(candidate)) return true
            return polarityFlips(existing, candidate)
        }
        if (polarityFlips(existing, candidate) &&
            dice(bigrams(skelExisting), bigrams(skelCandidate)) >= CONFLICT_SKELETON_DICE
        ) return true
        return slotSwaps(existing, candidate)
    }

    /** Short content hash used as a stable id (not a security primitive). */
    fun idOf(normalized: String): String = try {
        MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(12)
    } catch (_: Exception) {
        normalized.hashCode().toString(16)
    }

    // ── Suppression traces ────────────────────────────────────────────────────

    /**
     * Fingerprint left behind when a memory is deleted.
     *
     * The text is kept so the agent can be told what it may not re-learn, and
     * `blocked` counts the writes this trace has turned away.
     */
    data class Trace(
        val id: String,
        val text: String,
        val at: Long,
        val blocked: Int = 0
    )

    object Traces {
        /** Newest traces win; the bank is capped so it cannot grow forever. */
        const val MAX_TRACES = 100

        /** Parse memory_suppressions.json defensively, like MemoryEntry.parseList. */
        fun parse(json: String): List<Trace> {
            if (json.isBlank()) return emptyList()
            return try {
                val root = GSON.fromJson(json, com.google.gson.JsonElement::class.java)
                val arr = root as? JsonArray ?: return emptyList()
                arr.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val text = runCatching { o.get("text")?.asString }.getOrNull().orEmpty().trim()
                    if (text.isEmpty()) return@mapNotNull null
                    Trace(
                        id = runCatching { o.get("id")?.asString }.getOrNull().orEmpty()
                            .ifBlank { idOf(normalize(text)) },
                        text = text,
                        at = runCatching { o.get("at")?.asLong }.getOrNull() ?: 0L,
                        blocked = runCatching { o.get("blocked")?.asInt }.getOrNull() ?: 0
                    )
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}