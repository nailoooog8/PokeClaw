// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.agent.memory

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.min

/**
 * Access-weighted retention: how much of an entry is still worth injecting.
 *
 * Kept apart from ranking on purpose. Ranking answers "which entries go into
 * *this* briefing"; retention answers "how much has this entry proved it is
 * worth keeping at all", and that answer should not change shape every time
 * the ranking formula is tuned. Three jobs, none of them the other's:
 *
 *  - the decay term in [MemoryStore]'s score;
 *  - the eviction order once the bank passes [MemoryStore.MAX_ENTRIES];
 *  - a visible label in the management UI, because a decay curve nobody can
 *    see is indistinguishable from one that stopped running.
 *
 * The curve is `exp(-age / halfLife)`. Exponential rather than linear, because
 * the linear term it replaces (`max(0, 1.5 - 0.04 * ageDays)`) reached exactly
 * zero at 37.5 days and stayed there — every entry died on the same clock
 * whether it had been recalled thirty times or never. An exponential has no
 * floor: an entry that proved useful keeps a small but nonzero claim forever,
 * and one that never did fades out quickly.
 *
 * The half-life grows with use: `base * (1 + gain * log2(1 + hits))`.
 * Logarithmic on purpose. The tenth hit should not be worth as much as the
 * first, and an entry must not be able to buy itself permanence by being
 * retrieved in a loop — the ceiling in [MAX_HALF_LIFE_DAYS] is the backstop for
 * that, since a loop *can* otherwise drive the curve arbitrarily high.
 *
 * **Retention never deletes.** A faded entry still sits in the bank, just stops
 * being injected. Deleting on decay would be actively harmful: [MemoryStore]
 * leaves a suppression fingerprint on every delete, so a decayed entry dropped
 * by a cleanup pass would look exactly like a memory the user asked to forget,
 * and the agent would be forbidden from ever learning it again. Retention
 * retires a memory from the prompt; only the user retires it from the bank.
 */
object MemoryRetention {

    /** Days for an entry that has never been recalled. */
    const val BASE_HALF_LIFE_DAYS = 14.0

    /** Extra half-life, in units of [BASE_HALF_LIFE_DAYS], per doubling of hits. */
    const val ACCESS_GAIN = 1.0

    /**
     * Hard ceiling on the half-life. Reaching it takes ~34M hits, so this is not
     * a tuning knob — it is what makes "no entry is permanent" a promise rather
     * than an observation about the current constants.
     */
    const val MAX_HALF_LIFE_DAYS = 365.0

    /** Scale of the retention term inside the ranking score. */
    const val WEIGHT = 2.0

    /** At or above this, the UI calls an entry stable. */
    const val STRONG = 0.60

    /** Below this, the UI calls an entry nearly forgotten. */
    const val FADING = 0.30

    private const val DAY_MS = 86_400_000.0

    /**
     * How long an entry with [hits] recalls keeps half of its weight.
     *
     * Each doubling of hits adds `ACCESS_GAIN * BASE_HALF_LIFE_DAYS` days:
     *
     * ```
     * hits  0    1    3    7    15    31    255
     * days  14   28   42   56    70    84   126
     * ```
     */
    fun halfLifeDays(hits: Int): Double {
        val h = hits.coerceAtLeast(0)
        return min(
            MAX_HALF_LIFE_DAYS,
            BASE_HALF_LIFE_DAYS * (1.0 + ACCESS_GAIN * log2(1.0 + h))
        )
    }

    /**
     * Milliseconds since this entry last proved useful.
     *
     * A recall is the signal, not a creation date: an entry written six months
     * ago and recalled last week is fresh, and one written last week and never
     * touched is not. Never-recalled entries age from their last edit, so a
     * memory the agent re-learns (a merge refreshes both `hits` and
     * `lastUsedAt`) is treated as re-confirmed rather than stale.
     *
     * Clamped at zero: `lastUsedAt` comes off a hand-editable JSON file, and a
     * timestamp in the future must not make an entry immortal.
     */
    fun ageMs(entry: MemoryEntry, now: Long): Long {
        val ref = if (entry.lastUsedAt > 0) entry.lastUsedAt else entry.updatedAt
        return (now - ref).coerceAtLeast(0L)
    }

    fun ageDays(entry: MemoryEntry, now: Long): Double = ageMs(entry, now) / DAY_MS

    /**
     * Remaining weight in `(0, 1]`. Pinned entries are pinned precisely because
     * the user overrides decay, so they hold at 1.0 regardless of age.
     */
    fun retention(entry: MemoryEntry, now: Long): Double {
        if (entry.pinned) return 1.0
        val halfLife = halfLifeDays(entry.hits)
        if (halfLife <= 0.0) return 0.0
        return exp(-ageDays(entry, now) / halfLife)
    }

    /** The retention term as it enters [MemoryStore]'s score. */
    fun scoreTerm(entry: MemoryEntry, now: Long): Double = WEIGHT * retention(entry, now)

    /** How long until retention falls to [STRONG] — the UI's "when does this fade" line. */
    fun daysUntil(entry: MemoryEntry, threshold: Double, now: Long): Long {
        if (entry.pinned || threshold <= 0.0 || threshold >= 1.0) return Long.MAX_VALUE
        val halfLife = halfLifeDays(entry.hits)
        // retention = exp(-age / halfLife) = threshold  →  age = -halfLife * ln(threshold)
        val days = -halfLife * ln(threshold)
        return (days * DAY_MS).toLong().coerceAtLeast(0L)
    }

    /** Chinese retention state for the management UI. */
    fun labelOf(entry: MemoryEntry, now: Long): String {
        if (entry.pinned) return "置顶"
        return when {
            retention(entry, now) >= STRONG -> "稳固"
            retention(entry, now) >= FADING -> "渐淡"
            else -> "几乎遗忘"
        }
    }

    /**
     * True once an entry has dropped below [STRONG] — i.e. once the UI has
     * something worth saying about it. Marking the steady majority as well
     * would be noise; the point is to make the *fading* visible, because a
     * retention curve that quietly stops running looks identical to one that
     * is working.
     */
    fun isFading(entry: MemoryEntry, now: Long): Boolean =
        !entry.pinned && retention(entry, now) < STRONG
}