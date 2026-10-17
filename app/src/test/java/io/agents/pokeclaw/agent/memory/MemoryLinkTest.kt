// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.memory

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Tests for the write-time relation layer — `MemoryEntry.relatedIds`.
 *
 * Links are proposed by the agent (which sees ids in the briefing and in
 * `memory_recall` output), so the job of this class is to make a wrong link
 * impossible rather than merely unlikely.
 *
 * What each case protects, and why it matters:
 *  - **contradiction and relation are different axes** — measured over real
 *    sentence pairs, contradicted pairs score *higher* on lexical similarity
 *    than merely-related ones (0.50-0.59 vs 0.11-0.42 Dice). If a link may sit
 *    alongside a conflict, the model reads one claim as corroborating the other.
 *  - **unknown ids are dropped, not stored** — a hallucinated id that reaches
 *    disk is a dangling reference the agent will keep citing.
 *  - **every entry-removal path prunes** — delete, deleteByContent and eviction.
 *    Pruning wired into only some of them is the same bug waiting to return.
 */
class MemoryLinkTest {

    private lateinit var dir: File
    private var now = 1_788_000_000_000L

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("memory-link-test").toFile()
        MemoryStore.storageDirOverride = dir
        MemoryStore.clock = { now }
        MemoryStore.invalidate()
    }

    @After
    fun tearDown() {
        MemoryStore.storageDirOverride = null
        MemoryStore.clock = { System.currentTimeMillis() }
        MemoryStore.invalidate()
        dir.deleteRecursively()
    }

    private fun remember(
        content: String,
        relatedIds: List<String> = emptyList(),
        pinned: Boolean = false
    ): MemoryStore.RememberResult.Stored {
        val r = MemoryStore.remember(
            content, MemoryKind.FACT, emptyList(), pinned, relatedIds = relatedIds
        )
        assertTrue("expected Stored but was $r", r is MemoryStore.RememberResult.Stored)
        return r as MemoryStore.RememberResult.Stored
    }

    private fun bank() = MemoryStore.all()

    private fun byId(id: String) = MemoryStore.findById(id)

    // ── The happy path ───────────────────────────────────────────────────────

    @Test
    fun `an agent link to an existing memory is stored`() {
        val a = remember("用户的相机是富士")
        val b = remember("用户用 Lightroom 修富士照片", relatedIds = listOf(a.entry.id))
        assertEquals(listOf(a.entry.id), b.entry.relatedIds)
    }

    @Test
    fun `links survive a bank reload`() {
        val a = remember("用户的相机是富士")
        remember("用户用 Lightroom 修富士照片", relatedIds = listOf(a.entry.id))

        MemoryStore.invalidate()
        val reloaded = MemoryStore.all()
        val b = reloaded.first { it.content.contains("Lightroom") }
        assertEquals(listOf(a.entry.id), b.relatedIds)
    }

    @Test
    fun `merging into an existing entry keeps its links`() {
        val anchor = remember("用户喜欢用微信聊天")
        val linked = remember("用户每周跑步3次", relatedIds = listOf(anchor.entry.id))
        // "用户喜欢用微信" is a substring of the anchor, so this merges into it.
        val merged = remember("用户喜欢用微信")

        assertTrue("expected a merge", merged.merged)
        assertEquals(anchor.entry.id, merged.entry.id)
        // The link lives on the entry the agent wrote, pointing back at the
        // older one — it is one-sided, so the anchor does not gain one back.
        assertEquals(
            "the merge must not drop the link it inherited",
            listOf(anchor.entry.id),
            byId(linked.entry.id)!!.relatedIds
        )
        assertTrue(
            "links point forward-in-time only; the anchor must not gain one",
            byId(anchor.entry.id)!!.relatedIds.isEmpty()
        )
    }

    // ── A wrong link must degrade to no link ─────────────────────────────────

    @Test
    fun `a hallucinated id is dropped rather than stored`() {
        val b = remember("用户用 Lightroom 修图", relatedIds = listOf("deadbeef1234"))
        assertTrue("hallucinated ids must not reach disk", b.entry.relatedIds.isEmpty())
    }

    @Test
    fun `an entry cannot link to itself`() {
        // The id is a content hash, so the agent cannot normally know the id of
        // the memory it is still writing — but the guard is what makes that
        // true rather than merely likely.
        val text = "用户的相机是富士"
        val ownId = MemorySimilarity.idOf(text.lowercase().trim())
        val e = remember(text, relatedIds = listOf(ownId))
        assertTrue("self-reference must be dropped", e.entry.relatedIds.isEmpty())
        assertEquals(ownId, e.entry.id)
    }

    @Test
    fun `a contradicting memory is never linked`() {
        // The measured fact: contradicted pairs are lexically *more* similar
        // than related ones, so nothing about similarity could have caught this.
        val old = remember("用户每周跑3次。")
        val fresh = remember("用户每周跑5次。", relatedIds = listOf(old.entry.id))

        assertTrue(
            "sanity: this pair really does contradict",
            fresh.conflicts.isNotEmpty()
        )
        assertTrue(
            "a contradicted pair must stay out of relatedIds",
            fresh.entry.relatedIds.isEmpty()
        )
    }

    @Test
    fun `a valid link survives alongside the rejection of a bad one`() {
        val good = remember("用户的相机是富士")
        val conflict = remember("用户每周跑3次。")
        remember("用户每周跑5次。", relatedIds = listOf(conflict.entry.id)) // rejected

        val e = remember("用户用 Lightroom 修富士照片", relatedIds = listOf(good.entry.id, "nope1234"))
        assertEquals(listOf(good.entry.id), e.entry.relatedIds)
    }

    // ── Every removal path has to prune ──────────────────────────────────────

    @Test
    fun `deleting the target prunes the link`() {
        val a = remember("用户的相机是富士")
        val b = remember("用户用 Lightroom 修富士照片", relatedIds = listOf(a.entry.id))

        assertTrue(MemoryStore.delete(a.entry.id))
        assertEquals("link to a deleted memory must be pruned", emptyList<String>(), byId(b.entry.id)!!.relatedIds)
    }

    @Test
    fun `deleteByContent prunes links`() {
        val a = remember("用户的相机是富士")
        val b = remember("用户用 Lightroom 修富士照片", relatedIds = listOf(a.entry.id))

        // The query must be 3+ characters; a shorter one is refused outright.
        assertEquals(1, MemoryStore.deleteByContent("用户的相机"))
        assertEquals("link to a deleted memory must be pruned", emptyList<String>(), byId(b.entry.id)!!.relatedIds)
    }

    @Test
    fun `deleteByContent also prunes conflicts`() {
        // Regression: delete(id) repaired the surviving side of a conflict, but
        // deleteByContent did not, leaving the bank pointing at nothing.
        val c = remember("用户每周跑3次。")
        val d = remember("用户每周跑5次。")
        assertTrue(
            "sanity: both sides point at each other",
            byId(c.entry.id)!!.conflictsWith.contains(d.entry.id) &&
                byId(d.entry.id)!!.conflictsWith.contains(c.entry.id)
        )

        assertEquals(1, MemoryStore.deleteByContent("跑3次"))
        assertEquals(
            "surviving side must not keep a conflict with a deleted memory",
            emptyList<String>(),
            byId(d.entry.id)!!.conflictsWith
        )
    }

    @Test
    fun `eviction prunes dangling links`() {
        // The target is left unpinned and old so it is the one that gets
        // evicted; every filler is pinned so it survives and would otherwise be
        // left holding links to a memory that is gone.
        //
        // Fillers are fixed-width on purpose: "填充记忆编号1" is a substring of
        // "填充记忆编号10", and the near-duplicate merge would fold them
        // together, leaving the bank under the cap and eviction never running.
        val target = remember("用户的相机是富士")
        repeat(MemoryStore.MAX_ENTRIES + 20) { i ->
            remember("填充记忆编号%03d".format(i), relatedIds = listOf(target.entry.id), pinned = true)
        }

        assertEquals("the unpinned target should have been evicted", null, byId(target.entry.id))

        val live = bank().map { it.id }.toSet()
        val dangling = bank().flatMap { e ->
            (e.relatedIds + e.conflictsWith).filterNot { it in live }
        }
        assertTrue("eviction left dangling references: $dangling", dangling.isEmpty())
    }

    // ── Editing re-checks links instead of trusting them ─────────────────────

    @Test
    fun `editing an entry into a contradiction drops its link`() {
        val a = remember("用户喜欢富士相机")
        val b = remember("用户的相机是富士X100", relatedIds = listOf(a.entry.id))
        assertEquals(listOf(a.entry.id), b.entry.relatedIds)

        // Rewrite B into a claim that contradicts A.
        MemoryStore.remember(
            "用户不喜欢富士相机", MemoryKind.FACT, emptyList(), false, id = b.entry.id
        )
        assertEquals(
            "a link that became a contradiction must be dropped on edit",
            emptyList<String>(),
            byId(b.entry.id)!!.relatedIds
        )
    }

    @Test
    fun `editing keeps a link that is still valid`() {
        val a = remember("用户喜欢富士相机")
        val b = remember("用户用富士X100拍照", relatedIds = listOf(a.entry.id))

        MemoryStore.remember(
            "用户用富士X100拍风景照", MemoryKind.FACT, emptyList(), false, id = b.entry.id
        )
        assertEquals(listOf(a.entry.id), byId(b.entry.id)!!.relatedIds)
    }

    // ── The file is hand-editable over adb ───────────────────────────────────

    @Test
    fun `relatedIds survive a hand-edited JSON round trip`() {
        val json = """
            [{"id":"aaa111","content":"用户的相机是富士","relatedIds":["BBB222","CCC333"]}]
        """.trimIndent()
        val parsed = MemoryEntry.parseList(json)
        assertEquals(1, parsed.size)
        assertEquals(listOf("bbb222", "ccc333"), parsed.single().relatedIds)
    }

    @Test
    fun `a malformed relatedIds field degrades without losing the entry`() {
        val json = """
            [
              {"id":"aaa111","content":"一条正常的记忆","relatedIds":"not-an-array"},
              {"id":"bbb222","content":"另一条记忆","relatedIds":[1,2,3]}
            ]
        """.trimIndent()
        val parsed = MemoryEntry.parseList(json)
        assertEquals("both entries must survive a bad field", 2, parsed.size)
        assertEquals(
            "a non-array field falls back to empty",
            emptyList<String>(),
            parsed[0].relatedIds
        )
        // Not empty: GSON's asString() coerces a JSON number to its text form,
        // so [1,2,3] becomes ["1","2","3"] rather than being dropped. Same
        // coercion as tags and conflictsWith — wrong-typed ids are harmless
        // because they match nothing and get pruned on the next removal.
        assertEquals(listOf("1", "2", "3"), parsed[1].relatedIds)
    }

    @Test
    fun `a removal repairs dangling refs introduced by hand-editing the file`() {
        // memories.json is meant to be editable over adb, so the on-disk file
        // can contain ids that no longer exist. Reading does not rewrite it;
        // the next removal is what puts the bank back in order.
        File(dir, "memories.json").writeText(
            """
            [
              {"id":"aaa111","content":"一条记忆","relatedIds":["bbb222","gone0001"]},
              {"id":"bbb222","content":"另一条记忆","relatedIds":["gone0002"]}
            ]
            """.trimIndent(),
            Charsets.UTF_8
        )
        MemoryStore.invalidate()

        assertTrue("the live entry really does carry a stale id", byId("aaa111")!!.relatedIds.contains("gone0001"))
        assertTrue(MemoryStore.delete("bbb222"))

        assertEquals(
            "the removal must clear every ref to the deleted entry, including stale ones",
            emptyList<String>(),
            byId("aaa111")!!.relatedIds
        )
    }
}
