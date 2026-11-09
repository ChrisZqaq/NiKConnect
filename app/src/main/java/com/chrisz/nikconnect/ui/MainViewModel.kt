package com.chrisz.nikconnect.ui

import android.app.Application
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chrisz.nikconnect.camera.CameraRepository
import com.chrisz.nikconnect.camera.PhotoDownloader
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
internal enum class DownloadStatus { DOWNLOADING, COMPLETED, FAILED }

internal data class DownloadItem(
    val photo: PhotoObject,
    val transferred: Long = 0,
    val status: DownloadStatus = DownloadStatus.DOWNLOADING,
    val error: String? = null,
    val uri: String? = null,
)

internal data class UiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val deviceInfo: DeviceInfo? = null,
    val photos: List<PhotoObject> = emptyList(),
    val totalPhotoCount: Int = 0,
    val loadingPhotos: Boolean = false,
    val error: String? = null,
    val selectedTab: Int = 0,
    val downloads: List<DownloadItem> = emptyList(),
) {
    val downloading: Boolean get() = downloads.any { it.status == DownloadStatus.DOWNLOADING }
}

internal class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = CameraRepository(application)
    private val sessions = SessionController()
    private val thumbnailCache = LruCache<Long, ByteArray>(24)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        PhotoDownloader.cleanupStalePartials(application)
    }

    fun connect(manualHost: String) {
        if (_state.value.connectionStatus == ConnectionStatus.CONNECTING) return
        val generation = invalidateSession()
        _state.value = UiState(connectionStatus = ConnectionStatus.CONNECTING)
        val job = viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.connect(manualHost) } }
                .onSuccess { info ->
                    if (!sessions.isCurrent(generation)) return@onSuccess
                    _state.update {
                        it.copy(connectionStatus = ConnectionStatus.CONNECTED, deviceInfo = info, selectedTab = 0)
                    }
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
                .onSuccess { (total, photos) ->
                    if (!sessions.isCurrent(generation)) return@onSuccess
                    _state.update {
                        it.copy(
                            loadingPhotos = false,
                            totalPhotoCount = total,
                            photos = photos.filter { photo -> photo.isJpeg || photo.isRaw },
                        )
                    }
                }
                .onFailure { error -> handleOperationFailure(error, generation) }
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
        if (
            _state.value.connectionStatus != ConnectionStatus.CONNECTED ||
            _state.value.downloading ||
            !photo.isJpeg
        ) return
        val generation = sessions.generation
        val item = DownloadItem(photo)
        _state.update { state ->
            state.copy(downloads = listOf(item) + state.downloads.filterNot { it.photo.handle == photo.handle })
        }
        val job = viewModelScope.launch {
            runCatching {
                repository.downloader().download(photo) { transferred ->
                    updateDownload(photo.handle, generation) { it.copy(transferred = transferred) }
                }
            }.onSuccess { uri ->
                updateDownload(photo.handle, generation) {
                    it.copy(
                        transferred = photo.size,
                        status = DownloadStatus.COMPLETED,
                        uri = uri.toString(),
                    )
                }
            }.onFailure { error ->
                if (error !is CancellationException) {
                    updateDownload(photo.handle, generation) {
                        it.copy(status = DownloadStatus.FAILED, error = humanError(error))
                    }
                    handleOperationFailure(error, generation)
                }
            }
        }
        sessions.track(generation, job)
    }

    private fun updateDownload(handle: Long, generation: Long, transform: (DownloadItem) -> DownloadItem) {
        if (!sessions.isCurrent(generation)) return
        _state.update { state ->
            if (!sessions.isCurrent(generation)) return@update state
            state.copy(downloads = state.downloads.map { item ->
                if (item.photo.handle == handle) transform(item) else item
            })
        }
    }

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
        repository.close()
        _state.value = UiState()
    }

    private fun invalidateSession(): Long {
        val previousConnectionId = repository.connectionId
        val generation = sessions.invalidate {
            previousConnectionId?.let { PhotoDownloader.cleanupPartials(getApplication(), it) }
        }
        synchronized(thumbnailCache) { thumbnailCache.evictAll() }
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
        super.onCleared()
    }
}
