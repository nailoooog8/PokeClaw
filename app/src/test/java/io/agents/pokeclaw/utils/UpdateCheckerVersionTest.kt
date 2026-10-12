// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers [UpdateChecker.isNewer] and the release URL the app polls.
 *
 * Written in Kotlin because this project has no Java unit tests: `javac`
 * resolves a stub `org.junit.Assert` that lacks `assertTrue`/`assertFalse`,
 * while the Kotlin test source set resolves real JUnit 4.13.2 correctly.
 */
class UpdateCheckerVersionTest {

    @Test
    fun `same version is not newer`() {
        assertFalse(UpdateChecker.isNewer("0.8.0", "0.8.0"))
    }

    @Test
    fun `higher major minor or patch is newer`() {
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.8.0"))
        assertTrue(UpdateChecker.isNewer("0.9.0", "0.8.0"))
        assertTrue(UpdateChecker.isNewer("0.8.1", "0.8.0"))
    }

    @Test
    fun `lower remote is not newer`() {
        assertFalse(UpdateChecker.isNewer("0.7.1", "0.8.0"))
        assertFalse(UpdateChecker.isNewer("0.8.0", "0.8.1"))
    }

    @Test
    fun `segments compare numerically not lexicographically`() {
        // The classic bug: "0.10.0" must beat "0.9.0", not lose to it.
        assertTrue(UpdateChecker.isNewer("0.10.0", "0.9.0"))
        assertFalse(UpdateChecker.isNewer("0.9.0", "0.10.0"))
    }

    @Test
    fun `missing segments count as zero`() {
        assertTrue(UpdateChecker.isNewer("0.8", "0.7.9"))
        assertTrue(UpdateChecker.isNewer("0.8.1", "0.8"))
        assertFalse(UpdateChecker.isNewer("0.8", "0.8.0"))
    }

    @Test
    fun `unparseable version never nags the user`() {
        assertFalse(UpdateChecker.isNewer("unknown", "0.8.0"))
        assertFalse(UpdateChecker.isNewer("0.8.0-beta", "0.8.0"))
    }

    @Test
    fun `fork users are never offered the older upstream release`() {
        // The exact regression this fixes: the checker polled agents-io/PokeClaw,
        // whose latest release is 0.7.1, while this fork ships 0.8.0+.
        assertFalse(UpdateChecker.isNewer("0.7.1", "0.8.0"))
    }

    @Test
    fun `update checker polls this fork not upstream`() {
        val url = io.agents.pokeclaw.BuildConfig.UPDATE_CHECK_URL
        assertTrue("update checker must point at this fork, was: $url", url.contains("nailoooog8/PokeClaw"))
        assertFalse("update checker must not poll upstream, was: $url", url.contains("agents-io/PokeClaw"))
    }
}
