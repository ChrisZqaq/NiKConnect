package com.chrisz.nikconnect.ui

import kotlinx.coroutines.Job

/** Identifies asynchronous work belonging to one camera connection. */
internal class SessionController {
    @Volatile
    var generation: Long = 0
        private set

    private val jobs = mutableSetOf<Job>()

    fun invalidate(afterPreviousJobs: (() -> Unit)? = null): Long {
        val previousJobs = synchronized(this) {
            generation += 1
            jobs.toList().also { jobs.clear() }
        }
        previousJobs.forEach(Job::cancel)
        if (afterPreviousJobs != null) {
            val completed = java.util.concurrent.atomic.AtomicBoolean()
            fun finishOnce() {
                if (completed.compareAndSet(false, true)) afterPreviousJobs()
            }
            previousJobs.forEach { job ->
                job.invokeOnCompletion {
                    if (previousJobs.all { it.isCompleted }) finishOnce()
                }
            }
            if (previousJobs.all { it.isCompleted }) finishOnce()
        }
        return generation
    }

    fun isCurrent(token: Long): Boolean = generation == token

    @Synchronized
    fun track(token: Long, job: Job) {
        if (!isCurrent(token)) {
            job.cancel()
            return
        }
        jobs += job
        job.invokeOnCompletion {
            synchronized(this) { jobs -= job }
        }
    }
}
