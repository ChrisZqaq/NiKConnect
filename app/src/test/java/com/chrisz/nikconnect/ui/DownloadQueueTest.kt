package com.chrisz.nikconnect.ui

import com.chrisz.nikconnect.ptp.PhotoObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueTest {
    private fun photo(handle: Long) =
        PhotoObject(handle, 1, 0x3801, 10, "DSC_%04d.JPG".format(handle), "", 0)

    private fun statuses(queue: DownloadQueue): List<Pair<Long, QueueStatus>> =
        queue.tasks.value.map { it.photo.handle to it.status }

    private suspend fun awaitStatus(queue: DownloadQueue, handle: Long, status: QueueStatus) {
        withTimeout(5_000) {
            queue.tasks.first { tasks -> tasks.firstOrNull { it.photo.handle == handle }?.status == status }
        }
    }

    @Test
    fun `runs tasks in FIFO order and completes them`() = runBlocking {
        val executed = mutableListOf<Long>()
        val queue = DownloadQueue(this, execute = { photo, _ ->
            executed += photo.handle
            "uri://${photo.filename}"
        })
        queue.enqueue(listOf(photo(1), photo(2), photo(3)))
        awaitStatus(queue, 3, QueueStatus.COMPLETED)
        assertEquals(listOf(1L, 2L, 3L), executed)
        queue.tasks.value.forEach { task ->
            assertEquals(QueueStatus.COMPLETED, task.status)
            assertEquals("uri://${task.photo.filename}", task.uri)
        }
        queue.cancelAll()
    }

    @Test
    fun `a failed task does not block later tasks`() = runBlocking {
        val executed = mutableListOf<Long>()
        val queue = DownloadQueue(this, execute = { photo, _ ->
            executed += photo.handle
            if (photo.handle == 1L) throw IOException("boom")
            "uri://${photo.filename}"
        })
        queue.enqueue(listOf(photo(1), photo(2)))
        awaitStatus(queue, 2, QueueStatus.COMPLETED)
        assertEquals(listOf(1L, 2L), executed)
        val failed = queue.tasks.value.first { it.photo.handle == 1L }
        assertEquals(QueueStatus.FAILED, failed.status)
        assertEquals("boom", failed.error)
        queue.cancelAll()
    }

    @Test
    fun `reports progress and final transferred bytes`() = runBlocking {
        val queue = DownloadQueue(this, execute = { _, onProgress ->
            onProgress(4)
            onProgress(8)
            "uri://x"
        })
        queue.enqueue(listOf(photo(7)))
        awaitStatus(queue, 7, QueueStatus.COMPLETED)
        val task = queue.tasks.value.single()
        assertEquals(10, task.transferred)
        queue.cancelAll()
    }

    @Test
    fun `cancelling the running task marks it cancelled and continues the queue`() = runBlocking {
        val started = CompletableDeferred<Long>()
        val release = CompletableDeferred<Unit>()
        val executed = mutableListOf<Long>()
        val queue = DownloadQueue(this, execute = { photo, _ ->
            if (photo.handle == 1L) {
                started.complete(photo.handle)
                release.await()
            }
            executed += photo.handle
            "uri://${photo.filename}"
        })
        queue.enqueue(listOf(photo(1), photo(2)))
        started.await()
        queue.cancel(1)
        awaitStatus(queue, 1, QueueStatus.CANCELLED)
        awaitStatus(queue, 2, QueueStatus.COMPLETED)
        assertFalse(1L in executed)
        assertTrue(2L in executed)
        queue.cancelAll()
    }

    @Test
    fun `cancelling a waiting task skips it without executing`() = runBlocking {
        val started = CompletableDeferred<Long>()
        val gate = CompletableDeferred<Unit>()
        val executed = mutableListOf<Long>()
        val queue = DownloadQueue(this, execute = { photo, _ ->
            started.complete(photo.handle)
            gate.await()
            executed += photo.handle
            "uri://${photo.filename}"
        })
        queue.enqueue(listOf(photo(1), photo(2), photo(3)))
        started.await()
        queue.cancel(2)
        awaitStatus(queue, 2, QueueStatus.CANCELLED)
        gate.complete(Unit)
        awaitStatus(queue, 3, QueueStatus.COMPLETED)
        assertFalse(2L in executed)
        assertEquals(listOf(1L, 3L), executed)
        queue.cancelAll()
    }

    @Test
    fun `enqueue ignores duplicates and completed tasks`() = runBlocking {
        val executions = AtomicInteger()
        val queue = DownloadQueue(this, execute = { photo, _ ->
            executions.incrementAndGet()
            "uri://${photo.filename}"
        })
        queue.enqueue(listOf(photo(1)))
        awaitStatus(queue, 1, QueueStatus.COMPLETED)
        queue.enqueue(listOf(photo(1), photo(1)))
        queue.cancelAll()
        assertEquals(1, executions.get())
        assertEquals(1, queue.tasks.value.size)
    }

    @Test
    fun `re-enqueue a failed task retries it`() = runBlocking {
        var attempts = 0
        val queue = DownloadQueue(this, execute = { photo, _ ->
            attempts++
            if (attempts == 1) throw IOException("first attempt fails")
            "uri://${photo.filename}"
        })
        queue.enqueue(listOf(photo(5)))
        awaitStatus(queue, 5, QueueStatus.FAILED)
        queue.enqueue(listOf(photo(5)))
        awaitStatus(queue, 5, QueueStatus.COMPLETED)
        assertEquals(2, attempts)
        assertEquals(1, queue.tasks.value.size)
        queue.cancelAll()
    }

    @Test
    fun `cancelAll cancels running and waiting tasks and stops the queue`() = runBlocking {
        val started = CompletableDeferred<Long>()
        val release = CompletableDeferred<Unit>()
        val queue = DownloadQueue(this, execute = { photo, _ ->
            started.complete(photo.handle)
            release.await()
            "uri://${photo.filename}"
        })
        queue.enqueue(listOf(photo(1), photo(2)))
        started.await()
        queue.cancelAll()
        assertEquals(
            listOf(1L to QueueStatus.CANCELLED, 2L to QueueStatus.CANCELLED),
            statuses(queue),
        )
        release.complete(Unit)
        // 队列已停止，新任务直接丢弃。
        queue.enqueue(listOf(photo(3)))
        assertTrue(queue.tasks.value.none { it.photo.handle == 3L })
        assertFalse(queue.isActive)
    }

    @Test
    fun `onError is invoked for non-cancellation failures`() = runBlocking {
        val seen = mutableListOf<Throwable>()
        val queue = DownloadQueue(this, execute = { _, _ -> throw IOException("connection lost") }, onError = { seen += it })
        queue.enqueue(listOf(photo(9)))
        awaitStatus(queue, 9, QueueStatus.FAILED)
        assertEquals(1, seen.size)
        assertEquals("connection lost", seen.single().message)
        queue.cancelAll()
    }

    @Test
    fun `cancelAll after all tasks finished keeps their terminal states`() = runBlocking {
        val queue = DownloadQueue(this, execute = { photo, _ -> "uri://${photo.filename}" })
        queue.enqueue(listOf(photo(1)))
        awaitStatus(queue, 1, QueueStatus.COMPLETED)
        queue.cancelAll()
        assertEquals(listOf(1L to QueueStatus.COMPLETED), statuses(queue))
    }
}
