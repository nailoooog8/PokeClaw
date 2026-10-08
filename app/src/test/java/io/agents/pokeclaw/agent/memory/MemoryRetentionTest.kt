// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the access-weighted retention curve.
 *
 * The properties worth locking down are the ones that would quietly rot: that
 * use lengthens a half-life but only logarithmically (so an entry cannot buy
 * permanence by being retrieved in a loop), that the curve has no floor, and
 * that the decay clock is anchored to the last *recall* rather than to the
 * creation date.
 */
class MemoryRetentionTest {

    private val now = 1_788_000_000_000L
    private val day = 86_400_000L

    private fun entry(
        hits: Int = 0,
        lastUsedAt: Long = 0L,
        updatedAt: Long = now,
        pinned: Boolean = false,
        id: String = "m1"
    ) = MemoryEntry(
        id = id,
        content = "测试记忆",
        hits = hits,
        lastUsedAt = lastUsedAt,
        updatedAt = updatedAt,
        pinned = pinned
    )

    private fun daysAgo(days: Double) = now - (days * day).toLong()

    // ── Half-life curve ───────────────────────────────────────────────────────

    @Test
    fun `an unused memory starts at the base half-life`() {
        assertEquals(14.0, MemoryRetention.halfLifeDays(0), 1e-9)
    }

    @Test
    fun `each doubling of hits adds a constant number of days`() {
        // log2(1 + hits): the halving points are 1, 2, 4, 8 — that is hits of
        // 0, 1, 3, 7. Each step is worth the same 14 days, which is what makes
        // the curve logarithmic rather than merely monotonic.
        val steps = (0..3).map { MemoryRetention.halfLifeDays((1 shl it) - 1) }
        val deltas = steps.zipWithNext { a, b -> b - a }
        deltas.forEach { assertEquals(14.0, it, 1e-9) }
        assertEquals(56.0, steps.last(), 1e-9)
    }

    @Test
    fun `the tenth hit is worth less than the first`() {
        // Diminishing returns are the whole point of the log; a linear curve
        // would hand out the same reward for every recall and let a hot-looped
        // memory dominate the bank forever.
        val first = MemoryRetention.halfLifeDays(1) - MemoryRetention.halfLifeDays(0)
        val tenth = MemoryRetention.halfLifeDays(10) - MemoryRetention.halfLifeDays(9)
        assertTrue("tenth hit ($tenth) should buy less than the first ($first)", tenth < first)
    }

    @Test
    fun `half-life is capped so no entry becomes permanent`() {
        assertEquals(
            MemoryRetention.MAX_HALF_LIFE_DAYS,
            MemoryRetention.halfLifeDays(Int.MAX_VALUE),
            1e-9
        )
    }

    @Test
    fun `negative hits are treated as none`() {
        assertEquals(
            MemoryRetention.halfLifeDays(0),
            MemoryRetention.halfLifeDays(-5),
            1e-9
        )
    }

    // ── The curve itself ──────────────────────────────────────────────────────

    @Test
    fun `a fresh memory is at full strength`() {
        assertEquals(1.0, MemoryRetention.retention(entry(), now), 1e-9)
    }

    @Test
    fun `retention halves at exactly one half-life`() {
        val halfLife = MemoryRetention.halfLifeDays(3)
        val atHalfLife = MemoryRetention.retention(
            entry(hits = 3, lastUsedAt = now),
            now + (halfLife * day).toLong()
        )
        assertEquals(0.3678, atHalfLife, 0.01) // exp(-1)
    }

    @Test
    fun `the curve never reaches zero`() {
        // The linear term it replaced hit exactly 0 at 37.5 days and stayed
        // there, so a well-used memory and a dead one became indistinguishable.
        val longDead = MemoryRetention.retention(
            entry(hits = 0, lastUsedAt = daysAgo(3650.0)),
            now
        )
        assertTrue("a decade-old memory should still claim something", longDead > 0.0)
        assertTrue(longDead < 1e-9)
    }

    @Test
    fun `retention falls monotonically with age`() {
        var last = 1.0
        for (d in 0..200 step 5) {
            val r = MemoryRetention.retention(
                entry(lastUsedAt = now - d * day),
                now
            )
            assertTrue("retention rose at day $d", r <= last)
            last = r
        }
    }

    @Test
    fun `use beats recency at equal age`() {
        // The property the access weighting exists for: same age, different
        // demonstrated usefulness, and the used one keeps its claim.
        val used = MemoryRetention.retention(entry(hits = 30, lastUsedAt = daysAgo(30.0)), now)
        val unused = MemoryRetention.retention(entry(hits = 0, lastUsedAt = daysAgo(30.0)), now)
        assertTrue("used=$used should beat unused=$unused", used > unused)
    }

    @Test
    fun `use can outrun a much fresher but unused memory`() {
        val used = MemoryRetention.retention(entry(hits = 31, lastUsedAt = daysAgo(40.0)), now)
        val unused = MemoryRetention.retention(entry(hits = 0, lastUsedAt = daysAgo(10.0)), now)
        assertTrue("used=$used should beat unused=$unused", used > unused)
    }

    @Test
    fun `retention distinguishes what a hit count alone conflates`() {
        // Both have the same raw hits; only one was actually used recently.
        val hot = MemoryRetention.retention(entry(hits = 8, lastUsedAt = daysAgo(2.0)), now)
        val cold = MemoryRetention.retention(entry(hits = 8, lastUsedAt = daysAgo(90.0)), now)
        assertTrue("hot=$hot should beat cold=$cold", hot > cold * 2)
    }

    // ── Anchoring the clock ───────────────────────────────────────────────────

    @Test
    fun `a never-recalled memory ages from its last edit`() {
        val e = entry(hits = 0, lastUsedAt = 0L, updatedAt = daysAgo(20.0))
        assertEquals(20.0, MemoryRetention.ageDays(e, now), 0.01)
    }

    @Test
    fun `a recalled memory ages from the recall, not the edit`() {
        val e = entry(hits = 4, lastUsedAt = daysAgo(3.0), updatedAt = daysAgo(200.0))
        assertEquals(3.0, MemoryRetention.ageDays(e, now), 0.01)
    }

    @Test
    fun `a future timestamp does not make a memory immortal`() {
        // lastUsedAt is hand-editable over adb; a clock-skewed file must not
        // hand out free retention.
        val e = entry(hits = 0, lastUsedAt = now + 10 * day)
        assertEquals(0L, MemoryRetention.ageMs(e, now))
        assertEquals(1.0, MemoryRetention.retention(e, now), 1e-9)
    }

    @Test
    fun `pinned entries never decay`() {
        val ancient = entry(pinned = true, lastUsedAt = daysAgo(10_000.0))
        assertEquals(1.0, MemoryRetention.retention(ancient, now), 1e-9)
    }

    // ── Reporting ─────────────────────────────────────────────────────────────

    @Test
    fun `the score term is retention scaled`() {
        val e = entry(hits = 2, lastUsedAt = daysAgo(10.0))
        assertEquals(
            MemoryRetention.WEIGHT * MemoryRetention.retention(e, now),
            MemoryRetention.scoreTerm(e, now),
            1e-9
        )
    }

    @Test
    fun `days until fading round-trips back to the threshold`() {
        val e = entry(hits = 5, lastUsedAt = now)
        val ms = MemoryRetention.daysUntil(e, MemoryRetention.STRONG, now)
        val there = MemoryRetention.retention(e, now + ms)
        assertEquals(MemoryRetention.STRONG, there, 0.01)
    }

    @Test
    fun `days until fading is longer for a used memory`() {
        val used = MemoryRetention.daysUntil(entry(hits = 20, lastUsedAt = now), MemoryRetention.STRONG, now)
        val unused = MemoryRetention.daysUntil(entry(hits = 0, lastUsedAt = now), MemoryRetention.STRONG, now)
        assertTrue("used=$used should outlast unused=$unused", used > unused)
    }

    @Test
    fun `pinned entries never report as fading`() {
        val e = entry(pinned = true, lastUsedAt = daysAgo(10_000.0))
        assertFalse(MemoryRetention.isFading(e, now))
        assertEquals("置顶", MemoryRetention.labelOf(e, now))
    }

    @Test
    fun `labels follow the retention bands`() {
        fun labelAt(days: Double) =
            MemoryRetention.labelOf(entry(lastUsedAt = daysAgo(days)), now)

        assertEquals("稳固", labelAt(1.0))
        assertEquals("渐淡", labelAt(10.0))
        assertEquals("几乎遗忘", labelAt(100.0))
    }

    @Test
    fun `only entries past the strong band are reported as fading`() {
        // The UI marks the fading tail only; tagging every steady entry would
        // bury the signal in noise.
        assertFalse(MemoryRetention.isFading(entry(lastUsedAt = daysAgo(1.0)), now))
        assertTrue(MemoryRetention.isFading(entry(lastUsedAt = daysAgo(10.0)), now))
    }
}