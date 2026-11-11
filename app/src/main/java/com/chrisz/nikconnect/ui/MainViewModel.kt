package com.chrisz.nikconnect.ui

import android.app.Application
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chrisz.nikconnect.camera.CameraRepository
import com.chrisz.nikconnect.camera.PhotoDownloader
import com.chrisz.nikconnect.camera.ConnectionKeeperService
import com.chrisz.nikconnect.ptp.DeviceInfo
import com.chrisz.nikconnect.ptp.PhotoObject
import com.chrisz.nikconnect.ptp.PtpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

internal enum class DownloadStatus { WAITING, DOWNLOADING, COMPLETED, FAILED, CANCELLED }

internal data class DownloadItem(
    val photo: PhotoObject,
    val transferred: Long = 0,
    val status: DownloadStatus = DownloadStatus.WAITING,
    val error: String? = null,
    val uri: String? = null,
)

internal data class UiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val deviceInfo: DeviceInfo? = null,
    val photos: List<PhotoObject> = emptyList(),
    val totalPhotoCount: Int = 0,
    val loadingPhotos: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val selectedTab: Int = 0,
    val downloads: List<DownloadItem> = emptyList(),
) {
    val downloading: Boolean get() = downloads.any { it.status == DownloadStatus.DOWNLOADING }
    val canDownload: Boolean get() = connectionStatus == ConnectionStatus.CONNECTED && !downloading
}

internal class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CameraRepository(application)
    private val sessions = SessionController()
    private val thumbnailCache = LruCache<Long, ByteArray>(24)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 当前连接对应的下载器快照；队列执行体每任务读取一次。 */
    @Volatile
    private var downloader: PhotoDownloader? = null

    private val downloadQueue = DownloadQueue(
        scope = viewModelScope,
        execute = { photo, onProgress ->
            val downloader = checkNotNull(downloader) { "相机已断开，请重新连接" }
            val uri = downloader.download(photo, onProgress)
            uri.toString()
        },
        onError = { error ->
            if (repository.isConnected) handleOperationFailure(error, sessions.generation)
        },
    )

    init {
        PhotoDownloader.cleanupStalePartials(application)
        viewModelScope.launch {
            downloadQueue.tasks.collect { tasks ->
                _state.update { state ->
                    state.copy(downloads = tasks.map { it.toDownloadItem() })
                }
            }
        }
    }

    fun connect(manualHost: String) {
        if (_state.value.connectionStatus == ConnectionStatus.CONNECTING) return
        val generation = invalidateSession()
        _state.value = UiState(connectionStatus = ConnectionStatus.CONNECTING)
        val job = viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.connect(manualHost) } }
                .onSuccess { info ->
                    if (!sessions.isCurrent(generation)) return@onSuccess
                    downloader = repository.downloader()
                    _state.update {
                        it.copy(connectionStatus = ConnectionStatus.CONNECTED, deviceInfo = info, selectedTab = 0)
                    }
                    repository.clientOrNull()?.let { client ->
                        client.onDisconnected = {
                            viewModelScope.launch {
                                if (sessions.isCurrent(generation)) {
                                    invalidateSession()
                                    ConnectionKeeperService.stop(getApplication())
                                    _state.update {
                                        it.copy(
                                            connectionStatus = ConnectionStatus.DISCONNECTED,
                                            error = "相机连接已断开",
                                        )
                                    }
                                }
                            }
                        }
                    }
                    ConnectionKeeperService.start(
                        getApplication(),
                        title = "NiKConnect 已连接",
                        text = "${info.manufacturer} ${info.model} 传输保持中",
                    )
                    refreshPhotos()
                }
                .onFailure { error ->
                    if (error is CancellationException || !sessions.isCurrent(generation)) return@onFailure
                    _state.update {
                        it.copy(connectionStatus = ConnectionStatus.ERROR, error = humanError(error))
                    }
                }
        }
        sessions.track(generation, job)
    }

    fun refreshPhotos() {
        if (_state.value.connectionStatus != ConnectionStatus.CONNECTED || _state.value.loadingPhotos) return
        val generation = sessions.generation
        val job = viewModelScope.launch {
            _state.update { it.copy(loadingPhotos = true, error = null) }
            runCatching { withContext(Dispatchers.IO) { repository.listPhotos() } }
                .onSuccess { (total, photos, hasMore) ->
                    if (!sessions.isCurrent(generation)) return@onSuccess
                    _state.update {
                        it.copy(
                            loadingPhotos = false,
                            totalPhotoCount = total,
                            photos = photos.filter { photo -> photo.isJpeg || photo.isRaw },
                            hasMore = hasMore,
                        )
                    }
                }
                .onFailure { error -> handleOperationFailure(error, generation) }
        }
        sessions.track(generation, job)
    }

    /** 加载下一页对象信息（追加到现有列表之后）。 */
    fun loadMorePhotos() {
        val state = _state.value
        if (state.connectionStatus != ConnectionStatus.CONNECTED || state.loadingPhotos || state.loadingMore || !state.hasMore) return
        val generation = sessions.generation
        val loaded = state.photos
        val job = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, error = null) }
            runCatching { withContext(Dispatchers.IO) { repository.listPhotos(loaded) } }
                .onSuccess { (total, photos, hasMore) ->
                    if (!sessions.isCurrent(generation)) return@onSuccess
                    _state.update {
                        it.copy(
                            loadingMore = false,
                            totalPhotoCount = total,
                            photos = photos.filter { photo -> photo.isJpeg || photo.isRaw },
                            hasMore = hasMore,
                        )
                    }
                }
                .onFailure { error ->
                    if (!sessions.isCurrent(generation)) return@onFailure
                    _state.update { it.copy(loadingMore = false) }
                    handleOperationFailure(error, generation)
                }
        }
        sessions.track(generation, job)
    }

    suspend fun thumbnail(handle: Long): ByteArray = withContext(Dispatchers.IO) {
        val generation = sessions.generation
        checkSession(generation)
        synchronized(thumbnailCache) { thumbnailCache.get(handle) }?.let { return@withContext it }
        try {
            val bytes = repository.thumbnail(handle)
            synchronized(thumbnailCache) {
                checkSession(generation)
                thumbnailCache.put(handle, bytes)
            }
            bytes
        } catch (error: Throwable) {
            handleOperationFailure(error, generation)
            throw error
        }
    }

    fun download(photo: PhotoObject) {
        if (_state.value.connectionStatus != ConnectionStatus.CONNECTED || !(photo.isJpeg || photo.isRaw)) return
        downloadQueue.enqueue(listOf(photo))
    }

    /** 批量下载（多选模式）；仅 JPEG 入队，RAW 由队列逐项跳过。 */
    fun downloadAll(photos: List<PhotoObject>) {
        if (_state.value.connectionStatus != ConnectionStatus.CONNECTED) return
        downloadQueue.enqueue(photos.filter { it.isJpeg || it.isRaw })
    }

    /** 重试失败/取消的任务；队列的去重逻辑会重置后重新排队。 */
    fun retryDownload(handle: Long) {
        val task = downloadQueue.tasks.value.firstOrNull { it.photo.handle == handle } ?: return
        downloadQueue.enqueue(listOf(task.photo))
    }

    fun cancelDownload(handle: Long) {
        downloadQueue.cancel(handle)
    }

    private fun QueueTask.toDownloadItem() = DownloadItem(
        photo = photo,
        transferred = transferred,
        status = when (status) {
            QueueStatus.WAITING -> DownloadStatus.WAITING
            QueueStatus.RUNNING -> DownloadStatus.DOWNLOADING
            QueueStatus.COMPLETED -> DownloadStatus.COMPLETED
            QueueStatus.FAILED -> DownloadStatus.FAILED
            QueueStatus.CANCELLED -> DownloadStatus.CANCELLED
        },
        error = error,
        uri = uri,
    )

    private fun handleOperationFailure(error: Throwable, generation: Long) {
        if (error is CancellationException || !sessions.isCurrent(generation)) return
        if (repository.isConnected) {
            _state.update { it.copy(loadingPhotos = false, error = humanError(error)) }
        } else {
            _state.update {
                UiState(downloads = it.downloads, error = humanError(error))
            }
        }
    }

    private fun checkSession(generation: Long) {
        if (!sessions.isCurrent(generation)) throw CancellationException("连接已切换")
    }

    fun selectTab(index: Int) = _state.update { it.copy(selectedTab = index) }

    fun disconnect() {
        invalidateSession()
        downloadQueue.cancelAll()
        repository.close()
        ConnectionKeeperService.stop(getApplication())
        _state.value = UiState()
    }

    private fun invalidateSession(): Long {
        val previousConnectionId = repository.connectionId
        val generation = sessions.invalidate {
            previousConnectionId?.let { PhotoDownloader.cleanupPartials(getApplication(), it) }
        }
        synchronized(thumbnailCache) { thumbnailCache.evictAll() }
        downloader = null
        return generation
    }

    private fun humanError(error: Throwable): String = when (error) {
        is PtpException -> error.message ?: "连接失败"
        is SocketTimeoutException -> "连接超时，请重试"
        is UnknownHostException -> "相机 IP 无效"
        is IOException -> error.message ?: "连接中断，请重试"
        else -> "操作失败，请重试"
    }

    override fun onCleared() {
        invalidateSession()
        repository.close()
        ConnectionKeeperService.stop(getApplication())
        super.onCleared()
    }
}
