package com.chrisz.nikconnect.ui

import android.app.Application
import android.content.Context
import android.provider.MediaStore
import android.os.PowerManager
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
    val galleryViewTier: Int = 0,
    val lastHost: String? = null,
    /** MediaStore Pictures/NiKConnect 中已存在的文件名（跨会话已下载标记）。 */
    val downloadedFilenames: Set<String> = emptySet(),
    val downloads: List<DownloadItem> = emptyList(),
    /** 尼康云创登录态：token 仅驻留内存，guid 可持久化到 ui_prefs。 */
    val cloudToken: String? = null,
    val cloudGuid: String? = null,
) {
    val downloading: Boolean get() = downloads.any { it.status == DownloadStatus.DOWNLOADING }
    val canDownload: Boolean get() = connectionStatus == ConnectionStatus.CONNECTED && !downloading
}

internal class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application
    private val repository = CameraRepository(application)
    private val wakeLock = (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NiKConnect:download")
    private val sessions = SessionController()
    private val thumbnailCache = LruCache<Long, ByteArray>(24)
    private val prefs = application.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        UiState(
            galleryViewTier = prefs.getInt("gallery_view_tier", 0),
            lastHost = prefs.getString("last_host", null),
            cloudToken = null,
            cloudGuid = prefs.getString("cloud_guid", null),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 当前连接对应的下载器快照；队列执行体每任务读取一次。 */
    @Volatile
    private var downloader: PhotoDownloader? = null

    private val _queueTasks = MutableStateFlow<List<QueueTask>>(emptyList())
    val queueTasks: StateFlow<List<QueueTask>> = _queueTasks.asStateFlow()

    @Volatile
    private var downloadQueue = createDownloadQueue()

    private fun createDownloadQueue() = DownloadQueue(
        scope = viewModelScope,
        execute = { photo, onProgress ->
            acquireWakeLock()
            try {
                val downloader = checkNotNull(downloader) { "相机已断开，请重新连接" }
                downloader.download(photo, onProgress).toString()
            } finally {
                // 任务终态是在 execute 返回之后才写入的，按"队列还有没有活动任务"判断必然漏释放：
                // 这里无条件释放，下一个任务自己会重新 acquire。
                releaseWakeLock()
            }
        },
        onError = { error ->
            if (repository.isConnected) handleOperationFailure(error, sessions.generation)
        },
    )

    init {
        PhotoDownloader.cleanupStalePartials(application)
        viewModelScope.launch {
            _queueTasks.collect { tasks ->
                _state.update { state -> state.copy(downloads = tasks.map { it.toDownloadItem() }) }
            }
        }
        observeQueue(downloadQueue)
    }

    private var queueObserver: kotlinx.coroutines.Job? = null

    private fun observeQueue(queue: DownloadQueue) {
        queueObserver?.cancel()
        queueObserver = viewModelScope.launch {
            queue.tasks.collect { _queueTasks.value = it }
        }
    }

    private fun replaceDownloadQueue() {
        downloadQueue.cancelAll()
        downloadQueue = createDownloadQueue()
        _queueTasks.value = emptyList()
        observeQueue(downloadQueue)
    }

    /** 云创登录完成（WebView 抓取或手动粘贴）。token 不持久化，guid 可保留到本地。 */
    fun onCloudLogin(state: CloudAuthState) {
        prefs.edit().remove("cloud_token").apply()
        state.guid?.let { prefs.edit().putString("cloud_guid", it).apply() }
        _state.update { it.copy(cloudToken = state.token, cloudGuid = state.guid ?: it.cloudGuid) }
    }

    fun cloudLogout() {
        prefs.edit().remove("cloud_token").remove("cloud_guid").apply()
        _state.update { it.copy(cloudToken = null, cloudGuid = null) }
    }

    fun connect(manualHost: String) {
        if (_state.value.connectionStatus == ConnectionStatus.CONNECTING) return
        if (manualHost.isNotBlank()) prefs.edit().putString("last_host", manualHost).apply()
        val generation = invalidateSession()
        replaceDownloadQueue()
        nextHandleOffset = 0
        _state.update {
            it.copy(
                connectionStatus = ConnectionStatus.CONNECTING,
                deviceInfo = null,
                photos = emptyList(),
                totalPhotoCount = 0,
                loadingPhotos = false,
                loadingMore = false,
                hasMore = false,
                error = null,
                downloads = emptyList(),
                downloadedFilenames = emptySet(),
            )
        }
        val job = viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.connect(manualHost) } }
                .onSuccess { info ->
                    if (!sessions.isCurrent(generation)) return@onSuccess
                    downloader = repository.downloader()
                    val downloaded = queryDownloadedFilenames()
                    _state.update {
                        it.copy(
                            connectionStatus = ConnectionStatus.CONNECTED, deviceInfo = info,
                            selectedTab = 0, error = null, downloadedFilenames = downloaded,
                        )
                    }
                    repository.clientOrNull()?.let { client ->
                        client.onDisconnected = {
                            viewModelScope.launch {
                                if (sessions.isCurrent(generation)) {
                                    invalidateSession()
                                    downloadQueue.cancelAll()
                                    _queueTasks.value = emptyList()
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
                    nextHandleOffset = photos.size
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

    private var nextHandleOffset = 0

    /** 加载下一页对象信息（追加到现有列表之后）。 */
    fun loadMorePhotos() {
        val state = _state.value
        if (state.connectionStatus != ConnectionStatus.CONNECTED || state.loadingPhotos || state.loadingMore || !state.hasMore) return
        val generation = sessions.generation
        val offset = nextHandleOffset
        val job = viewModelScope.launch {
            _state.update { it.copy(loadingMore = true, error = null) }
            runCatching { withContext(Dispatchers.IO) { repository.listPhotos(offset) } }
                .onSuccess { (total, photos, hasMore) ->
                    if (!sessions.isCurrent(generation)) return@onSuccess
                    nextHandleOffset = offset + photos.size
                    _state.update {
                        it.copy(
                            loadingMore = false,
                            totalPhotoCount = total,
                            photos = it.photos + photos.filter { photo -> photo.isJpeg || photo.isRaw },
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

    private fun acquireWakeLock() {
        if (!wakeLock.isHeld) wakeLock.acquire(30 * 60 * 1000L)  // 最长 30 分钟防泄漏
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) runCatching { wakeLock.release() }
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

    /** 查询系统相册 Pictures/NiKConnect 下已有文件名，用于跨会话"已下载"标记。 */
    private fun queryDownloadedFilenames(): Set<String> = runCatching {
        val resolver = getApplication<Application>().contentResolver
        val projection = arrayOf(MediaStore.Images.Media.DISPLAY_NAME)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
        val args = arrayOf("Pictures/NiKConnect/")
        resolver.query(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            projection, selection, args, null,
        )?.use { cursor ->
            val idx = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            buildSet { while (cursor.moveToNext()) add(cursor.getString(idx)) }
        } ?: emptySet()
    }.getOrDefault(emptySet())

    /** 切换图库视图档位（0=大 1=中 2=小），持久化到本地。 */
    fun setGalleryViewTier(tier: Int) {
        prefs.edit().putInt("gallery_view_tier", tier).apply()
        _state.update { it.copy(galleryViewTier = tier) }
    }

    fun disconnect() {
        invalidateSession()
        downloadQueue.cancelAll()
        _queueTasks.value = emptyList()
        repository.close()
        ConnectionKeeperService.stop(getApplication())
        // 只重置「相机会话」相关字段：云创身份与本机偏好跟相机连接无关，
        // 断线不该把账号登出、也不该把图库视图档位打回去。
        _state.update {
            UiState(
                cloudToken = it.cloudToken,
                cloudGuid = it.cloudGuid,
                galleryViewTier = it.galleryViewTier,
                selectedTab = if (it.selectedTab == 1) 0 else it.selectedTab,
            )
        }
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
        downloadQueue.cancelAll()
        repository.close()
        ConnectionKeeperService.stop(getApplication())
        super.onCleared()
    }
}
