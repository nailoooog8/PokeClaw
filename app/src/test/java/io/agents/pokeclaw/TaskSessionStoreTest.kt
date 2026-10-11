// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw

import io.agents.pokeclaw.channel.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
