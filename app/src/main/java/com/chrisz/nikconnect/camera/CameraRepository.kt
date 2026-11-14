package com.chrisz.nikconnect.camera

import android.content.Context
import com.chrisz.nikconnect.ptp.DeviceInfo
import com.chrisz.nikconnect.ptp.PhotoObject
import com.chrisz.nikconnect.ptp.PtpIpClient
import java.io.Closeable
import java.io.IOException

internal class CameraRepository(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val networkProvider = CameraNetworkProvider(appContext)
    private val stateLock = Any()

    @Volatile
    private var generation = 0L
    private var acquisition: CameraNetworkAcquisition? = null
    private var networkHandle: CameraNetworkHandle? = null
    private var connectingClient: PtpIpClient? = null
    private var client: PtpIpClient? = null

    val isConnected: Boolean
        get() = synchronized(stateLock) { client?.isConnected == true }

    val connectionId: String?
        get() = synchronized(stateLock) { client?.connectionId }

    fun connect(manualHost: String): DeviceInfo {
        close()
        val ticket = synchronized(stateLock) { generation }
        val pending = networkProvider.newAcquisition()
        synchronized(stateLock) {
            ensureGeneration(ticket)
            acquisition = pending
        }

        try {
            val network = pending.await()
            synchronized(stateLock) {
                if (generation != ticket) {
                    network.close()
                    throw IOException("连接已取消")
                }
                acquisition = null
                networkHandle = network
            }
            val candidates = buildList {
                if (manualHost.isNotBlank()) add(manualHost.trim())
                // 空串/0.0.0.0 会被 InetAddress 解析成 localhost，白等一个 10 s 超时，
                // 而且它的错误会盖掉真正有用的那条（真机踩过：报 "connect to localhost:15740"）。
                network.gateway?.trim()?.takeIf { it.isNotEmpty() && it != "0.0.0.0" }?.let(::add)
                add("192.168.1.1")
            }.distinct()
            var lastError: IOException? = null
            for (host in candidates) {
                val camera = PtpIpClient(host, socketFactory = network::newSocket)
                synchronized(stateLock) {
                    if (generation != ticket) {
                        camera.close()
                        throw IOException("连接已取消")
                    }
                    connectingClient = camera
                }
                try {
                    val info = camera.connect()
                    synchronized(stateLock) {
                        ensureGeneration(ticket)
                        client = camera
                        connectingClient = null
                    }
                    return info
                } catch (error: IOException) {
                    camera.close()
                    synchronized(stateLock) {
                        if (generation == ticket && connectingClient === camera) connectingClient = null
                    }
                    ensureGeneration(ticket)
                    if (!host.startsWith("127.") && host != "localhost") lastError = error
                }
            }
            throw lastError ?: IOException("未找到相机，请检查 Wi-Fi 连接")
        } catch (error: Throwable) {
            closeGeneration(ticket)
            throw error
        }
    }

    fun listPhotos(handleOffset: Int = 0, pageSize: Int = 60): Triple<Int, List<PhotoObject>, Boolean> =
        requireClient().listPhotos(handleOffset, pageSize)

    fun thumbnail(handle: Long): ByteArray = requireClient().getThumbnail(handle)

    fun downloader(): PhotoDownloader = PhotoDownloader(appContext, requireClient())

    /** 当前连接的 client（无连接时 null）；供 ViewModel 挂断连回调。 */
    fun clientOrNull(): PtpIpClient? = synchronized(stateLock) { client }

    private fun requireClient(): PtpIpClient = synchronized(stateLock) {
        client ?: throw IOException("相机已断开，请重新连接")
    }

    private fun ensureGeneration(ticket: Long) {
        if (generation != ticket) throw IOException("连接已取消")
    }

    override fun close() = closeGeneration(null)

    private fun closeGeneration(expected: Long?) {
        val resources = synchronized(stateLock) {
            if (expected != null && generation != expected) return
            generation += 1
            Resources(acquisition, networkHandle, connectingClient, client).also {
                acquisition = null
                networkHandle = null
                connectingClient = null
                client = null
            }
        }
        resources.acquisition?.close()
        runCatching { resources.connectingClient?.close() }
        runCatching { resources.client?.close() }
        runCatching { resources.networkHandle?.close() }
    }

    private data class Resources(
        val acquisition: CameraNetworkAcquisition?,
        val networkHandle: CameraNetworkHandle?,
        val connectingClient: PtpIpClient?,
        val client: PtpIpClient?,
    )
}
