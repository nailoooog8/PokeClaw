// Copyright 2026 PokeClaw (agents-io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-logic tests: no Context, no disk, no Android framework. */
class BruteForceGuardTest {

    private fun withClock(): () -> Long {
        var now = 1_000_000L
        BruteForceGuard.clock = { now }
        return { now }
    }

    @Test
    fun `locks out after five failures`() {
        val clock = withClock()
        try {
            val key = "test-lockout"
            repeat(4) { BruteForceGuard.onFailure(key) }
            assertFalse("must not lock before the threshold", BruteForceGuard.isLocked(key))

            val lockout = BruteForceGuard.onFailure(key)
            assertTrue("fifth failure must lock", lockout > 0)
            assertTrue(BruteForceGuard.isLocked(key))
            assertEquals(30_000L, BruteForceGuard.lockoutRemainingMs(key))
        } finally {
            clock()
        }
    }

    @Test
    fun `lockout expires after the backoff window`() {
        val clock = withClock()
        try {
            var now = 1_000_000L
            BruteForceGuard.clock = { now }
            val key = "test-expiry"
            repeat(5) { BruteForceGuard.onFailure(key) }
            assertTrue(BruteForceGuard.isLocked(key))

            now += 30_001L
            assertFalse("lockout must lift once the window passes", BruteForceGuard.isLocked(key))
            assertEquals(0L, BruteForceGuard.lockoutRemainingMs(key))
        } finally {
            clock()
        }
    }

    @Test
    fun `lockout doubles on repeated offences`() {
        val clock = withClock()
        try {
            var now = 1_000_000L
            BruteForceGuard.clock = { now }
            val key = "test-backoff"

            repeat(5) { BruteForceGuard.onFailure(key) }
            assertEquals(30_000L, BruteForceGuard.lockoutRemainingMs(key))

            // Wait out the first lockout, then fail five more times.
            now += 30_001L
            repeat(5) { BruteForceGuard.onFailure(key) }
            assertEquals(
                "second lockout must be longer than the first",
                60_000L,
                BruteForceGuard.lockoutRemainingMs(key)
            )
        } finally {
            clock()
        }
    }

    @Test
    fun `success clears the failure history`() {
        val clock = withClock()
        try {
            val key = "test-success"
            repeat(4) { BruteForceGuard.onFailure(key) }
            BruteForceGuard.onSuccess(key)
            // The counter is gone, so five more failures still must not lock.
            repeat(4) { BruteForceGuard.onFailure(key) }
            assertFalse(BruteForceGuard.isLocked(key))
        } finally {
            clock()
        }
    }

    @Test
    fun `keys are independent`() {
        val clock = withClock()
        try {
            repeat(5) { BruteForceGuard.onFailure("attacker-a") }
            assertTrue(BruteForceGuard.isLocked("attacker-a"))
            assertFalse("locking one key must not lock another", BruteForceGuard.isLocked("attacker-b"))
        } finally {
            clock()
        }
    }

    @Test
    fun `constant time equals matches string semantics`() {
        assertTrue(BruteForceGuard.constantTimeEquals("123456", "123456"))
        assertFalse(BruteForceGuard.constantTimeEquals("123456", "123457"))
        assertFalse(BruteForceGuard.constantTimeEquals("123456", "12345"))
        assertFalse(BruteForceGuard.constantTimeEquals("", "1"))
    }
}
