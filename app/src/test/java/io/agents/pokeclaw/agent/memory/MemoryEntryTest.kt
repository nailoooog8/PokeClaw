// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MemoryEntry]'s defensive parsing and [MemoryKind]'s contract.
 *
 * `memories.json` is hand-editable over adb and is also pushed from a computer,
 * so [MemoryEntry.parseList] is the only thing standing between a hand-typed
 * typo and losing the whole bank. Its job is to degrade **per field**: one bad
 * entry must not take the others down with it, and a wrong-typed or missing key
 * must land on a sane default rather than throwing.
 *
 * Note the sibling suites already cover the ranking-side logic:
 * `MemorySimilarityTest` (31 cases) and `MemoryRetentionTest` (22 cases). What
 * was untested here was the boundary between those helpers and the store —
 * the parsing and the weight table they both read from.
 */
class MemoryEntryTest {

    // ── parseList: shape ──────────────────────────────────────────────────────

    @Test
    fun `a blank file yields an empty bank instead of throwing`() {
        assertTrue(MemoryEntry.parseList("").isEmpty())
        assertTrue(MemoryEntry.parseList("   \n  ").isEmpty())
    }

    @Test
    fun `valid json that is not an array yields an empty bank`() {
        // A user who pastes a single object, or wraps the array in a key.
        assertTrue(MemoryEntry.parseList("""{"content":"x"}""").isEmpty())
        assertTrue(MemoryEntry.parseList("""["not","an","object"]""").isEmpty())
    }

    @Test
    fun `malformed json yields an empty bank instead of throwing`() {
        assertTrue(MemoryEntry.parseList("{not json at all").isEmpty())
        assertTrue(MemoryEntry.parseList("[[[").isEmpty())
    }

    @Test
    fun `a fully specified entry round-trips every field`() {
        val entries = MemoryEntry.parseList(
            """
            [{
              "id": "abc123",
              "content": "用户每周跑3次。",
              "kind": "preference",
              "tags": ["health", "running"],
              "pinned": true,
              "createdAt": 1000,
              "updatedAt": 2000,
              "hits": 7,
              "lastUsedAt": 3000,
              "conflictsWith": ["def456"]
            }]
            """.trimIndent()
        )
        val e = entries.single()
        assertEquals("abc123", e.id)
        assertEquals("用户每周跑3次。", e.content)
        assertEquals(MemoryKind.PREFERENCE, e.kind)
        assertEquals(listOf("health", "running"), e.tags)
        assertTrue(e.pinned)
        assertEquals(1000L, e.createdAt)
        assertEquals(2000L, e.updatedAt)
        assertEquals(7, e.hits)
        assertEquals(3000L, e.lastUsedAt)
        assertEquals(listOf("def456"), e.conflictsWith)
    }

    // ── parseList: per-field degradation ──────────────────────────────────────

    @Test
    fun `an entry without content is dropped but its siblings survive`() {
        // The one unrecoverable field: no content means there is nothing to
        // recall or inject, so the entry is meaningless. It must be dropped
        // *alone* — losing the whole bank over one bad row is the failure mode
        // this file exists to prevent.
        val entries = MemoryEntry.parseList(
            """
            [
              { "content": "第一条记忆。" },
              { "id": "no-content" },
              { "content": "   " },
              { "content": "第三条记忆。" }
            ]
            """.trimIndent()
        )
        assertEquals(listOf("第一条记忆。", "第三条记忆。"), entries.map { it.content })
    }

    @Test
    fun `missing id falls back to a content hash rather than a blank one`() {
        val entries = MemoryEntry.parseList("""[{"content":"用户的通勤是地铁。"}]""")
        val e = entries.single()
        // findById() looks entries up by this, so a blank id would make the
        // entry unreachable by every code path that uses an id.
        assertTrue("id should be derived, was '${e.id}'", e.id.isNotBlank())
    }

    @Test
    fun `a wrong-typed field degrades to its default instead of dropping the entry`() {
        // The whole point: hands, not types, go wrong in this file.
        val entries = MemoryEntry.parseList(
            """
            [{
              "content": "用户喜欢喝美式。",
              "kind": 42,
              "pinned": "yes-please",
              "hits": "many",
              "updatedAt": [1, 2, 3],
              "tags": "not-an-array"
            }]
            """.trimIndent()
        )
        val e = entries.single()
        assertEquals("用户喜欢喝美式。", e.content)
        assertEquals(MemoryKind.FACT, e.kind)
        assertEquals(false, e.pinned)
        assertEquals(0, e.hits)
        assertEquals(emptyList<String>(), e.tags)
        // updatedAt fell back to "now", so it must be positive and recent-ish.
        assertTrue("updatedAt should be defaulted, was ${e.updatedAt}", e.updatedAt > 0L)
    }

    @Test
    fun `an unknown kind falls back to fact and is still retrievable`() {
        val e = MemoryEntry.parseList(
            """[{"content":"x","kind":"nonsense"}]"""
        ).single()
        assertEquals(MemoryKind.FACT, e.kind)
        // kindLabel must not be null/blank, or the management UI shows a gap.
        assertTrue(e.kindLabel.isNotBlank())
    }

    @Test
    fun `kind matching is case-insensitive and normalised`() {
        val e = MemoryEntry.parseList("""[{"content":"x","kind":"  PREFERENCE  "}]""").single()
        assertEquals(MemoryKind.PREFERENCE, e.kind)
    }

    @Test
    fun `tags are lowercased, trimmed, de-duplicated and empties dropped`() {
        val e = MemoryEntry.parseList(
            """[{"content":"x","tags":[" Health ","health","RUNNING",""]}]"""
        ).single()
        assertEquals(listOf("health", "running"), e.tags)
    }

    @Test
    fun `a non-string tag is coerced to its text form rather than dropped`() {
        // Gson's asString() on a number yields "7", so a hand-typed numeric
        // tag survives as text instead of vanishing. Harmless: tags only feed
        // grouping and retrieval, and the next write normalises it away.
        val e = MemoryEntry.parseList("""[{"content":"x","tags":[7,true]}]""").single()
        assertEquals(listOf("7", "true"), e.tags)
    }

    @Test
    fun `conflictsWith is parsed and de-duplicated like tags`() {
        val e = MemoryEntry.parseList(
            """[{"content":"x","conflictsWith":["a"," a ","b",""]}]"""
        ).single()
        assertEquals(listOf("a", "b"), e.conflictsWith)
    }

    @Test
    fun `a zero or negative timestamp is replaced rather than trusted`() {
        val e = MemoryEntry.parseList(
            """[{"content":"x","createdAt":0,"updatedAt":-5}]"""
        ).single()
        // A zero updatedAt would make ageMs huge and the entry look ancient on
        // first load, so the parser substitutes a real clock reading.
        assertTrue("createdAt should be positive", e.createdAt > 0L)
        assertTrue("updatedAt should be positive", e.updatedAt > 0L)
    }

    // ── MemoryKind ────────────────────────────────────────────────────────────

    @Test
    fun `every kind has a label and a positive weight`() {
        MemoryKind.all.forEach { kind ->
            assertTrue("label for $kind", MemoryKind.labelOf(kind).isNotBlank())
            assertTrue("weight for $kind", MemoryKind.weight(kind) > 0.0)
        }
    }

    @Test
    fun `ranking weight orders identity above preference above fact above event`() {
        // This ordering is the whole reason `kind` exists: identity and
        // preferences stay true longer than one-off events, so they should win
        // the tie on otherwise equal entries.
        assertTrue(MemoryKind.weight(MemoryKind.PROFILE) > MemoryKind.weight(MemoryKind.PREFERENCE))
        assertTrue(MemoryKind.weight(MemoryKind.PREFERENCE) > MemoryKind.weight(MemoryKind.FACT))
        assertTrue(MemoryKind.weight(MemoryKind.FACT) > MemoryKind.weight(MemoryKind.EVENT))
    }

    @Test
    fun `an unknown kind ranks between fact and event and still gets a label`() {
        // Fail-safe rather than fail-hard: an unrecognised kind must not be
        // weighted 0 (it would sink below everything, including one-off
        // events) and must still render a label for the UI.
        val w = MemoryKind.weight("nonsense")
        assertEquals(0.5, w, 1e-9)
        assertTrue("$w should sit above an event", w > MemoryKind.weight(MemoryKind.EVENT))
        assertTrue("$w should sit below a fact", w < MemoryKind.weight(MemoryKind.FACT))
        assertEquals("记忆", MemoryKind.labelOf("nonsense"))
    }

    @Test
    fun `isValid only accepts the declared kinds`() {
        MemoryKind.all.forEach { assertTrue(it, MemoryKind.isValid(it)) }
        assertEquals(false, MemoryKind.isValid("nonsense"))
        assertEquals(false, MemoryKind.isValid(""))
    }

    @Test
    fun `labels are distinct so the management UI can group by them`() {
        val labels = MemoryKind.all.map { MemoryKind.labelOf(it) }
        assertEquals(labels.size, labels.distinct().size)
    }
}
