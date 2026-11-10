package com.chrisz.nikconnect.ui

import com.chrisz.nikconnect.ptp.PhotoObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal enum class QueueStatus { WAITING, RUNNING, COMPLETED, FAILED, CANCELLED }

internal data class QueueTask(
    val photo: PhotoObject,
    val transferred: Long = 0,
    val status: QueueStatus = QueueStatus.WAITING,
    val error: String? = null,
    val uri: String? = null,
)

/**
 * 串行下载队列：一次只向相机发送一个任务（PRD D-06），FIFO 顺序执行，
 * 单任务可取消，失败不阻塞后续项（D-06），会话结束时整体取消（PRD 2.6）。
 *
 * 队列生命周期与一次连接绑定：调用 [cancelAll] 后不再接受新任务。
 */
internal class DownloadQueue(
    scope: CoroutineScope,
    private val execute: suspend (photo: PhotoObject, onProgress: (bytes: Long) -> Unit) -> String,
    private val onError: (error: Throwable) -> Unit = {},
) {
    private val _tasks = MutableStateFlow<List<QueueTask>>(emptyList())
    val tasks: StateFlow<List<QueueTask>> = _tasks.asStateFlow()

    private val incoming = Channel<PhotoObject>(Channel.UNLIMITED)

    /** 当前正在执行的任务句柄与执行协程，用于 cancel() 定点取消。 */
    @Volatile
    private var runningHandle: Long? = null

    @Volatile
    private var runningTask: Job? = null

    private val supervisor = SupervisorJob()
    private val pumpScope = CoroutineScope(scope.coroutineContext + supervisor)

    /** 泵协程：从通道逐个取任务执行；单任务取消只取消执行体，泵本身存活。 */
    private val pump: Job = pumpScope.launch {
        for (photo in incoming) {
            if (_tasks.value.firstOrNull { it.photo.handle == photo.handle }?.status != QueueStatus.WAITING) continue
            runningHandle = photo.handle
            val task = pumpScope.launch {
                try {
                    runOne(photo)
                } catch (error: Throwable) {
                    if (error !is CancellationException) throw error
                }
            }
            runningTask = task
            try {
                task.join()
            } finally {
                runningHandle = null
                if (runningTask === task) runningTask = null
            }
        }
    }

    val isActive: Boolean get() = pump.isActive

    /** 追加任务；已在等待/执行/完成的句柄不重复入队，失败/取消的任务重置后重新排队。 */
    fun enqueue(photos: List<PhotoObject>) {
        if (photos.isEmpty() || !pump.isActive) return
        _tasks.update { current ->
            val byHandle = current.associateBy { it.photo.handle }
            photos.fold(current) { list, photo ->
                val existing = byHandle[photo.handle]
                when (existing?.status) {
                    null -> list + QueueTask(photo)
                    QueueStatus.WAITING, QueueStatus.RUNNING, QueueStatus.COMPLETED -> list
                    QueueStatus.FAILED, QueueStatus.CANCELLED -> list.map {
                        if (it.photo.handle == photo.handle) QueueTask(photo) else it
                    }
                }
            }
        }
        photos.forEach { incoming.trySend(it) }
    }

    /** 取消单个任务：等待中的直接标记取消，执行中的向泵协程投递取消（不关闭会话）。 */
    fun cancel(handle: Long) {
        val task = _tasks.value.firstOrNull { it.photo.handle == handle } ?: return
        when (task.status) {
            QueueStatus.WAITING -> updateTask(handle) { it.copy(status = QueueStatus.CANCELLED) }
            QueueStatus.RUNNING -> if (runningHandle == handle) runningTask?.cancel()
            QueueStatus.COMPLETED, QueueStatus.FAILED, QueueStatus.CANCELLED -> Unit
        }
    }

    /** 会话结束：停止调度，未完成任务标记取消。 */
    fun cancelAll() {
        incoming.close()
        pump.cancel()
        runningTask?.cancel()
        _tasks.update { list ->
            list.map {
                if (it.status == QueueStatus.WAITING || it.status == QueueStatus.RUNNING) {
                    it.copy(status = QueueStatus.CANCELLED)
                } else it
            }
        }
    }

    private suspend fun runOne(photo: PhotoObject) {
        updateTask(photo.handle) { it.copy(status = QueueStatus.RUNNING, transferred = 0, error = null, uri = null) }
        try {
            val uri = execute(photo) { transferred ->
                updateTask(photo.handle) { it.copy(transferred = transferred) }
            }
            updateTask(photo.handle) { it.copy(status = QueueStatus.COMPLETED, transferred = photo.size, uri = uri) }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                updateTask(photo.handle) { it.copy(status = QueueStatus.CANCELLED) }
            } else {
                updateTask(photo.handle) { it.copy(status = QueueStatus.FAILED, error = error.message ?: "传输失败") }
                onError(error)
            }
        }
    }

    private fun updateTask(handle: Long, transform: (QueueTask) -> QueueTask) {
        _tasks.update { list ->
            if (list.none { it.photo.handle == handle }) list
            else list.map { if (it.photo.handle == handle) transform(it) else it }
        }
    }
}
