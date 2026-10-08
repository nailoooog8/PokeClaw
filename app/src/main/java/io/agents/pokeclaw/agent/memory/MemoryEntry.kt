// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.memory

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.Locale

/**
 * One durable item in the long-term memory bank.
 *
 * Unlike the knowledge base (KBManager), which is a user-authored markdown
 * vault, a memory is a small single-sentence fact the agent decided is worth
 * carrying into future sessions: who the user is, how they like things done,
 * facts about their devices and accounts, or outcomes worth not repeating.
 *
 * Persisted as a JSON array in memories.json (see [MemoryStore]).
 */
data class MemoryEntry(
    /** Stable id: short hash of the normalized content (see MemoryStore.remember). */
    val id: String,
    /** The remembered fact, one sentence. Keep it self-contained — it is read without its context. */
    val content: String,
    /** One of [MemoryKind]'s keys. Defaults to "fact". */
    val kind: String = MemoryKind.FACT,
    /** Free-form labels, lowercased, used for retrieval and grouping. */
    val tags: List<String> = emptyList(),
    /** Pinned entries are always injected into the prompt and never decay. */
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** How many times this entry has been surfaced to the model. Drives ranking. */
    val hits: Int = 0,
    /** Last time this entry was surfaced (0 = never recalled). */
    val lastUsedAt: Long = 0L,
    /** Ids of entries this one appears to contradict. Surfaced, never auto-merged. */
    val conflictsWith: List<String> = emptyList(),
) {
    val kindLabel: String get() = MemoryKind.labelOf(kind)

    companion object {
        private val GSON = Gson()

        /**
         * Parses a memories.json payload.
         *
         * Every field is read defensively: the file is hand-editable over adb
         * and a user-edited array may be missing keys or hold wrong types,
         * which must degrade to defaults instead of throwing away the bank.
         */
        fun parseList(json: String): List<MemoryEntry> {
            if (json.isBlank()) return emptyList()
            return try {
                val root = GSON.fromJson(json, com.google.gson.JsonElement::class.java)
                val arr = root as? JsonArray ?: return emptyList()
                arr.mapNotNull { el -> (el as? JsonObject)?.let { obj -> fromJson(obj) } }
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun fromJson(o: JsonObject): MemoryEntry? {
            val content = o.str("content")
            if (content.isBlank()) return null
            val now = System.currentTimeMillis()
            val kind = o.str("kind").lowercase(Locale.US)
                .let { if (MemoryKind.isValid(it)) it else MemoryKind.FACT }
            return MemoryEntry(
                id = o.str("id").ifBlank { content.hashCode().toString(16) },
                content = content,
                kind = kind,
                tags = o.arr("tags"),
                pinned = o.bool("pinned"),
                createdAt = o.num("createdAt").takeIf { it > 0 } ?: now,
                updatedAt = o.num("updatedAt").takeIf { it > 0 } ?: now,
                hits = o.num("hits").toInt(),
                lastUsedAt = o.num("lastUsedAt"),
                conflictsWith = o.arr("conflictsWith"),
            )
        }

        private fun JsonObject.str(key: String): String =
            runCatching { get(key)?.asString }.getOrNull().orEmpty().trim()

        private fun JsonObject.num(key: String): Long =
            runCatching { get(key)?.asLong }.getOrNull() ?: 0L

        private fun JsonObject.bool(key: String): Boolean =
            runCatching { get(key)?.asBoolean }.getOrNull() ?: false

        private fun JsonObject.arr(key: String): List<String> =
            runCatching {
                get(key)?.asJsonArray
                    ?.mapNotNull { it.asString.trim().lowercase(Locale.US) }
                    ?.filter { it.isNotEmpty() }
                    ?.distinct()
                    .orEmpty()
            }.getOrDefault(emptyList())
    }
}

/**
 * The kinds a memory can have. Weight biases retrieval toward identity and
 * preferences, which stay true longer than one-off events.
 */
object MemoryKind {
    const val PROFILE = "profile"
    const val PREFERENCE = "preference"
    const val FACT = "fact"
    const val EVENT = "event"

    /** Chinese labels for the management UI. */
    fun labelOf(kind: String): String = when (kind) {
        PROFILE -> "身份"
        PREFERENCE -> "偏好"
        FACT -> "事实"
        EVENT -> "事件"
        else -> "记忆"
    }

    /** Valid kinds, in the order the UI and tool descriptions list them. */
    val all = listOf(PROFILE, PREFERENCE, FACT, EVENT)

    /** Retrieval weight: identity and preferences outrank one-off events. */
    fun weight(kind: String): Double = when (kind) {
        PROFILE -> 1.2
        PREFERENCE -> 1.0
        FACT -> 0.7
        EVENT -> 0.3
        else -> 0.5
    }

    fun isValid(kind: String): Boolean = kind in all
}