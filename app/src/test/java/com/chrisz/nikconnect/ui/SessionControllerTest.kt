package com.chrisz.nikconnect.ui

import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionControllerTest {
    @Test
    fun `invalidating a session cancels its jobs and rejects late work`() = runBlocking {
        val sessions = SessionController()
        val first = sessions.invalidate()
        var oldJobRunning = true
        val oldJob = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try {
                kotlinx.coroutines.delay(60_000)
            } finally {
                oldJobRunning = false
            }
        }
        sessions.track(first, oldJob)

        val second = sessions.invalidate()
        joinAll(oldJob)

        assertNotEquals(first, second)
        assertFalse(oldJobRunning)
        assertTrue(oldJob.isCancelled)
        assertFalse(sessions.isCurrent(first))
        assertTrue(sessions.isCurrent(second))

        val lateJob = Job()
        sessions.track(first, lateJob)
        assertTrue(lateJob.isCancelled)
    }

    @Test
    fun `finished old session cleans up immediately when no writer remains`() {
        val sessions = SessionController()
        sessions.invalidate()
        var cleaned = false

        sessions.invalidate { cleaned = true }

        assertTrue(cleaned)
    }
}
