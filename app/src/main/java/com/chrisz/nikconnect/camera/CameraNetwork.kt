package com.chrisz.nikconnect.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class CameraNetworkHandle(
    val gateway: String?,
    private val network: Network,
    private val connectivityManager: ConnectivityManager,
    private val callback: ConnectivityManager.NetworkCallback?,
) : Closeable {
    fun newSocket(): Socket = network.socketFactory.createSocket()

    fun canReach(host: String, port: Int = 15740): Boolean = runCatching {
        newSocket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 1_200)
        }
    }.isSuccess

    override fun close() {
        val networkCallback = callback ?: return
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
    }
}

internal class CameraNetworkProvider(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun newAcquisition(): CameraNetworkAcquisition = CameraNetworkAcquisition(connectivityManager)
}

internal class CameraNetworkAcquisition(
    private val connectivityManager: ConnectivityManager,
) : Closeable {
    private val latch = CountDownLatch(1)
    private val callbackLock = Any()

    @Volatile
    private var cancelled = false

    @Volatile
    private var cameraNetwork: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun await(): CameraNetworkHandle {
        ensureActive()
        connectivityManager.activeNetwork?.let { active ->
            val capabilities = connectivityManager.getNetworkCapabilities(active)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                return CameraNetworkHandle(gateway(active), active, connectivityManager, null)
            }
        }

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!cancelled && cameraNetwork == null) {
                    cameraNetwork = network
                    latch.countDown()
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            synchronized(callbackLock) {
                ensureActive()
                callback = networkCallback
                connectivityManager.requestNetwork(request, networkCallback)
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            while (!latch.await(250, TimeUnit.MILLISECONDS)) {
                ensureActive()
                if (System.nanoTime() >= deadline) throw IOException("未找到相机 Wi-Fi，请先连接")
            }
            ensureActive()
            val network = cameraNetwork ?: throw IOException("相机 Wi-Fi 不可用")
            return CameraNetworkHandle(gateway(network), network, connectivityManager, networkCallback)
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    override fun close() {
        cancelled = true
        latch.countDown()
        val registered = synchronized(callbackLock) {
            callback.also { callback = null }
        }
        if (registered != null) {
            runCatching { connectivityManager.unregisterNetworkCallback(registered) }
        }
    }

    private fun ensureActive() {
        if (cancelled) throw IOException("连接已取消")
    }

    private fun gateway(network: Network): String? =
        connectivityManager.getLinkProperties(network)?.routes
            ?.firstNotNullOfOrNull { route -> route.gateway?.hostAddress }
}
