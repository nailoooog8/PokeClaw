// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.utils

import java.security.MessageDigest

/**
 * Shared brute-force protection for the app's short secrets.
 *
 * Both the LAN [io.agents.pokeclaw.server.ConfigServer] console PIN and the chat
 * channel pairing PIN are 6 digits, so 10^6 guesses is trivial for an attacker
 * who can drive requests (same Wi-Fi, or a scripted bot). This guard adds the
 * three things that were missing everywhere the PIN was compared:
 *
 * 1. a failure counter per key (remote IP / sender id)
 * 2. an escalating lockout after repeated failures
 * 3. constant-time comparison, so the comparison itself leaks nothing by timing
 *
 * All state is in memory: a restart clears it, which is the intended behavior
 * (a lockout should not survive a reboot and lock the owner out of their own
 * phone).
 */
object BruteForceGuard {

    private const val MAX_FAILURES = 5
    private const val BASE_LOCKOUT_MS = 30_000L
    private const val MAX_LOCKOUT_MS = 30 * 60_000L
    private const val ENTRY_TTL_MS = 30 * 60_000L

    private class Entry(
        /** Failures since the last lockout was served. Reset when a lockout fires. */
        var failures: Int = 0,
        /** Consecutive lockouts served. Drives the backoff length. */
        var strikes: Int = 0,
        var lockedUntil: Long = 0L,
        var lastSeen: Long = 0L,
    )

    private val entries = HashMap<String, Entry>()

    /** Wall-clock now, injectable so tests do not have to sleep. */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    /** True when [key] is currently locked out and must not be challenged. */
    @Synchronized
    fun isLocked(key: String): Boolean {
        val now = clock()
        prune(now)
        val e = entries[key] ?: return false
        if (e.lockedUntil <= now) return false
        XLog.w("BruteForceGuard", "Locked out key=${redactKey(key)} until_in=${(e.lockedUntil - now) / 1000}s")
        return true
    }

    /** Remaining lockout in ms, or 0 when the key is usable. */
    @Synchronized
    fun lockoutRemainingMs(key: String): Long {
        val now = clock()
        prune(now)
        val e = entries[key] ?: return 0L
        return (e.lockedUntil - now).coerceAtLeast(0L)
    }

    /** Record one failed attempt. Returns the lockout now in effect (0 if none). */
    @Synchronized
    fun onFailure(key: String): Long {
        val now = clock()
        prune(now)
        val e = entries.getOrPut(key) { Entry() }
        e.lastSeen = now
        e.failures++
        if (e.failures >= MAX_FAILURES) {
            // Back off by how many lockouts this key has already served, so a
            // persistent attacker climbs toward the ceiling instead of getting
            // a flat 30s forever. The failure counter resets here so the next
            // lockout costs a fresh MAX_FAILURES guesses, not one.
            val backoff = BASE_LOCKOUT_MS shl e.strikes.coerceAtMost(5)
            e.lockedUntil = now + backoff.coerceAtMost(MAX_LOCKOUT_MS)
            e.strikes++
            e.failures = 0
            XLog.w(
                "BruteForceGuard",
                "Lockout triggered key=${redactKey(key)} strikes=${e.strikes} lockout_ms=${e.lockedUntil - now}"
            )
            return e.lockedUntil - now
        }
        return 0L
    }

    /** Clear the failure history for [key] after a successful authentication. */
    @Synchronized
    fun onSuccess(key: String) {
        entries.remove(key)
    }

    /**
     * Constant-time comparison of two secrets.
     *
     * Uses MessageDigest.isEqual so the comparison time does not depend on how
     * many leading characters matched. Length still leaks, which is acceptable
     * here because every secret compared is a fixed 6-digit PIN.
     */
    fun constantTimeEquals(a: String, b: String): Boolean {
        return MessageDigest.isEqual(
            a.toByteArray(Charsets.UTF_8),
            b.toByteArray(Charsets.UTF_8),
        )
    }

    private fun prune(now: Long) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val e = iterator.next().value
            if (now - e.lastSeen > ENTRY_TTL_MS && e.lockedUntil <= now) {
                iterator.remove()
            }
        }
    }

    /** Never log a full IP; keep enough to correlate events. */
    private fun redactKey(key: String): String {
        if (key.length <= 4) return "***"
        return key.takeLast(4)
    }
}
