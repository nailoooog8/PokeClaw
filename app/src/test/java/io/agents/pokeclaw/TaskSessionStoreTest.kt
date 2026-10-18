// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw

import io.agents.pokeclaw.channel.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSessionStoreTest {

    @Test
    fun `initial state is idle`() {
        val store = TaskSessionStore()
        assertFalse(store.isTaskRunning())
        assertEquals(TaskSessionPhase.IDLE, store.snapshot().phase)
    }

    @Test
    fun `tryAcquire succeeds when idle and rejects when already running`() {
        val store = TaskSessionStore()

        assertTrue(store.tryAcquire("msg1", Channel.LOCAL, "Task 1"))
        assertTrue(store.isTaskRunning())
        assertEquals("msg1", store.snapshot().messageId)
        assertEquals(TaskSessionPhase.RUNNING, store.snapshot().phase)

        // Second acquisition must fail
        assertFalse(store.tryAcquire("msg2", Channel.TELEGRAM, "Task 2"))
        assertEquals("msg1", store.snapshot().messageId)
    }

    @Test
    fun `release clears state and double release returns idle state`() {
        val store = TaskSessionStore()
        store.tryAcquire("msg1", Channel.LOCAL, "Task 1")

        val released = store.release()
        assertTrue(released.isRunning)
        assertEquals("msg1", released.messageId)
        assertFalse(store.isTaskRunning())

        // Second release must return idle session (isRunning == false)
        val secondRelease = store.release()
        assertFalse(secondRelease.isRunning)
        assertEquals("", secondRelease.messageId)
    }

    @Test
    fun `markStopping transitions phase to STOPPING`() {
        val store = TaskSessionStore()
        store.tryAcquire("msg1", Channel.LOCAL, "Task 1")

        assertTrue(store.markStopping())
        assertEquals(TaskSessionPhase.STOPPING, store.snapshot().phase)
        assertTrue(store.snapshot().stopRequested)

        // Second markStopping returns false since already stopping
        assertFalse(store.markStopping())
    }

    // ---------------------------------------------------------------------
    // originActivity: which surface a finished task returns to.
    //
    // Tasks open other apps, so something must bring the user back. That
    // target used to be hardcoded to the chat screen. These cases pin the two
    // things that must not regress: an unset origin still means "chat screen",
    // and an origin survives to release() — the orchestrator reads it after
    // the task is done, not while it is running.
    // ---------------------------------------------------------------------

    @Test
    fun `originActivity defaults to null so an unset origin keeps the legacy chat return`() {
        val store = TaskSessionStore()
        store.tryAcquire("msg1", Channel.LOCAL, "Task 1")
        // Null is the signal for "no origin recorded", and the orchestrator
        // turns it into ComposeChatActivity. Never default this to a value —
        // a default would silently claim an origin nobody supplied.
        assertNull(store.snapshot().originActivity)
    }

    @Test
    fun `originActivity is recorded and survives release`() {
        val store = TaskSessionStore()
        val origin = "io.agents.pokeclaw.ui.web.ClaudeUiPreviewActivity"
        store.tryAcquire("msg1", Channel.LOCAL, "Task 1", originActivity = origin)

        assertEquals(origin, store.snapshot().originActivity)

        // This is the read that matters: onComplete reads it from the released
        // session, after the task is already over.
        val released = store.release()
        assertEquals(origin, released.originActivity)
    }

    @Test
    fun `release resets originActivity so the next task cannot inherit it`() {
        val store = TaskSessionStore()
        store.tryAcquire("msg1", Channel.LOCAL, "Task 1", originActivity = "com.example.Origin")
        store.release()

        // A stale origin would send the *next* task's result back to a screen
        // that is no longer involved. This is the leak that matters.
        store.tryAcquire("msg2", Channel.LOCAL, "Task 2")
        assertNull(store.snapshot().originActivity)
    }

    @Test
    fun `a rejected acquisition does not overwrite the running origin`() {
        val store = TaskSessionStore()
        store.tryAcquire("msg1", Channel.LOCAL, "Task 1", originActivity = "com.example.First")
        assertFalse(store.tryAcquire("msg2", Channel.LOCAL, "Task 2", originActivity = "com.example.Second"))

        // The failed acquire must leave the live session untouched, or the
        // return target would point at whoever lost the race.
        assertEquals("com.example.First", store.snapshot().originActivity)
    }
}
