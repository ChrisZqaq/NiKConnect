package com.chrisz.nikconnect.ptp

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

internal class PtpException(message: String, val responseCode: Int? = null) : IOException(message)

internal class PtpIpClient(
    private val host: String,
    private val port: Int = 15740,
    private val socketFactory: () -> Socket = { Socket(Proxy.NO_PROXY) },
    private val settleDelayMs: Long = 350,
    private val socketTimeoutMs: Int = 30_000,
) : Closeable {
    @Volatile
    private var commandSocket: Socket? = null

    @Volatile
    private var eventSocket: Socket? = null
    private val resourceLock = Any()
    private var lifecycle = 0L
    private var transactionId = 0L

    @Volatile
    internal var isConnected: Boolean = false

    /** 连接意外断开（事务失败关通道/close）时回调一次；UI 层据此停止保活服务。 */
    @Volatile
    var onDisconnected: (() -> Unit)? = null

    internal var connectionId: String = ""
        private set

    lateinit var deviceInfo: DeviceInfo
        private set

    private val initiatorGuid = byteArrayOf(
        0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
        0x88.toByte(), 0x99.toByte(), 0xaa.toByte(), 0xbb.toByte(),
        0xcc.toByte(), 0xdd.toByte(), 0xee.toByte(), 0xff.toByte(),
    )

    @Synchronized
    fun connect(): DeviceInfo {
        close()
        val ticket = synchronized(resourceLock) { lifecycle }
        connectionId = java.util.UUID.randomUUID().toString()
        var stage = "连接命令通道"
        try {
            val command = openSocket(ticket, eventChannel = false)
            // Some Nikon Z bodies discard data sent immediately after the TCP handshake.
            if (settleDelayMs > 0) Thread.sleep(settleDelayMs)
            stage = "初始化命令通道"
            command.getOutputStream().write(encodeInitCommandRequest(initiatorGuid, "zf-relay/0.1 (Android)"))
            command.getOutputStream().flush()
            val initResponse = readPacket(command.getInputStream())
            if (initResponse.type == PacketType.INIT_FAIL) {
                throw PtpException("连接被拒绝，请退出其他相机 App 后重试")
            }
            require(initResponse.type == PacketType.INIT_COMMAND_ACK) { "相机未返回 InitCommandAck" }
            val ack = decodeInitCommandAck(initResponse.payload)

            stage = "连接事件通道"
            val event = openSocket(ticket, eventChannel = true)
            if (settleDelayMs > 0) Thread.sleep(settleDelayMs)
            stage = "初始化事件通道"
            event.getOutputStream().write(encodeInitEventRequest(ack.connectionNumber))
            event.getOutputStream().flush()
            val eventResponse = readPacket(event.getInputStream())
            if (eventResponse.type == PacketType.INIT_FAIL) {
                throw PtpException("连接被拒绝，请退出其他相机 App 后重试")
            }
            require(eventResponse.type == PacketType.INIT_EVENT_ACK) { "相机未返回 InitEventAck" }

            stage = "打开会话"
            val session = transaction(OpCode.OPEN_SESSION, listOf(1), DataPhase.NONE)
            if (session.response.responseCode !in setOf(ResponseCode.OK, ResponseCode.SESSION_ALREADY_OPEN)) {
                throw PtpException("OpenSession 失败：${responseName(session.response.responseCode)}", session.response.responseCode)
            }
            stage = "读取相机信息"
            deviceInfo = fetchDeviceInfo()
            if (!deviceInfo.supportsPartialObject) {
                throw PtpException("当前相机不受支持")
            }
            synchronized(resourceLock) {
                ensureOpen(ticket)
                isConnected = true
            }
            return deviceInfo
        } catch (error: Exception) {
            close()
            throw PtpException(
                "${stage}失败：${error.message ?: "请重试"}",
                (error as? PtpException)?.responseCode,
            )
        }
    }

    /**
     * 全量读取句柄列表，再分页读取对象信息（每页 [pageSize] 个，最新的在前）。
     * 返回总句柄数、已读对象列表、是否还有下一页。每页耗时打点输出便于真机调优。
     */
    @Synchronized
    fun listPhotos(
        alreadyLoaded: List<PhotoObject> = emptyList(),
        pageSize: Int = 60,
    ): Triple<Int, List<PhotoObject>, Boolean> {
        val handles = decodeU32Array(
            checked(transaction(OpCode.GET_OBJECT_HANDLES, listOf(0xffffffffL, 0, 0)), "读取文件列表").data,
        )
        // 相机返回旧→新，展示用最新优先
        val newestFirst = handles.asReversed()
        val start = alreadyLoaded.size
        if (start >= newestFirst.size) return Triple(handles.size, alreadyLoaded, false)
        val pageHandles = newestFirst.drop(start).take(pageSize)
        val pageStart = System.currentTimeMillis()
        val page = pageHandles.map { handle ->
            parseObjectInfo(
                handle,
                checked(transaction(OpCode.GET_OBJECT_INFO, listOf(handle)), "读取文件信息").data,
            )
        }
        Log.i(
            TAG,
            "GetObjectInfo page: offset=$start size=${page.size} elapsed=${System.currentTimeMillis() - pageStart}ms",
        )
        val next = start + page.size
        return Triple(handles.size, alreadyLoaded + page, next < newestFirst.size)
    }

    @Synchronized
    fun getThumbnail(handle: Long): ByteArray {
        val large = transaction(OpCode.NIKON_GET_LARGE_THUMB, listOf(handle))
        if (large.response.responseCode == ResponseCode.OK && large.data.isNotEmpty()) return large.data
        return checked(transaction(OpCode.GET_THUMB, listOf(handle)), "读取缩略图").data
    }

    /** B0 探针结果：单个候选 opcode 的探测记录 */
    data class ProbeResult(val opcode: Int, val supported: Boolean, val responseCode: Int, val dataPreview: ByteArray) {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = opcode
    }

    /**
     * B0 技术探针：向相机发送原始厂商命令，返回响应（不抛异常，失败记录 responseCode）。
     * 用于真机枚举 GetPicCtrlCapability / GetPicCtrlData / AdvancedTransfer 的实际 opcode。
     */
    @Synchronized
    fun probeCommand(opcode: Int, params: List<Long> = emptyList()): ProbeResult {
        val result = try {
            transaction(opcode, params)
        } catch (e: PtpException) {
            return ProbeResult(opcode, false, e.responseCode ?: -1, ByteArray(0))
        }
        return ProbeResult(
            opcode,
            result.response.responseCode == ResponseCode.OK,
            result.response.responseCode,
            result.data.take(32).toByteArray(),
        )
    }

    /** 枚举厂商属性码列表（GetVendorPropCodes 0x9028 候选；标准 0x1014/0x1015 兜底）。 */
    @Synchronized
    fun probeVendorPropCodes(): ProbeResult = probeCommand(OpCode.NIKON_GET_VENDOR_PROP_CODES)

    /** 读取指定属性码当前值（标准 GetDevicePropValue，0xD000 厂商区候选）。 */
    @Synchronized
    fun readPropValue(propCode: Int): ProbeResult = probeCommand(OpCode.GET_DEVICE_PROP_VALUE, listOf(propCode.toLong() and 0xffffL))

    fun copyObject(
        photo: PhotoObject,
        startOffset: Long,
        onChunk: (offset: Long, data: ByteArray) -> Unit,
    ) {
        var offset = startOffset
        while (offset < photo.size) {
            val wanted = minOf(CHUNK_SIZE.toLong(), photo.size - offset)
            val result = checked(
                transaction(OpCode.GET_PARTIAL_OBJECT, listOf(photo.handle, offset, wanted)),
                "下载 ${photo.filename}",
            )
            if (result.data.isEmpty()) throw IOException("相机返回了空数据块，已保留断点")
            if (result.data.size > wanted) throw IOException("相机返回的数据块超过请求长度")
            onChunk(offset, result.data)
            offset += result.data.size
        }
    }

    @Synchronized
    private fun transaction(opcode: Int, params: List<Long>, dataPhase: Int = DataPhase.DATA_IN): TransactionResult {
        val socket = commandSocket ?: throw IOException("尚未连接相机，请重新连接")
        val id = ++transactionId
        try {
            socket.getOutputStream().write(encodeOperationRequest(opcode, id, params, dataPhase))
            socket.getOutputStream().flush()
            val data = ByteArrayOutputStream()
            while (true) {
                val packet = readPacket(socket.getInputStream())
                when (packet.type) {
                    PacketType.START_DATA -> checkTransactionId(packet, id)
                    PacketType.DATA, PacketType.END_DATA -> {
                        checkTransactionId(packet, id)
                        data.write(dataPayload(packet.payload))
                    }
                    PacketType.OPERATION_RESPONSE -> {
                        val response = decodeOperationResponse(packet.payload)
                        if (response.transactionId != id) {
                            throw PtpException("响应事务编号不匹配：期望 $id，收到 ${response.transactionId}")
                        }
                        return TransactionResult(response, data.toByteArray())
                    }
                    PacketType.PROBE_REQUEST -> writePacket(socket.getOutputStream(), PacketType.PROBE_RESPONSE)
                }
            }
        } catch (error: Exception) {
            // A timed-out or malformed exchange may leave late packets on this TCP stream.
            // Close both channels so the next request cannot consume a previous photo's data.
            close()
            throw error
        }
    }

    private fun checkTransactionId(packet: PtpPacket, expected: Long) {
        val actual = LittleEndianReader(packet.payload).u32()
        if (actual != expected) {
            throw PtpException("数据包事务编号不匹配：期望 $expected，收到 $actual")
        }
    }

    private fun fetchDeviceInfo(): DeviceInfo =
        parseDeviceInfo(checked(transaction(OpCode.GET_DEVICE_INFO, emptyList()), "读取相机信息").data)

    private fun checked(result: TransactionResult, action: String): TransactionResult {
        if (result.response.responseCode != ResponseCode.OK) {
            throw PtpException("$action 失败：${responseName(result.response.responseCode)}", result.response.responseCode)
        }
        return result
    }

    private fun responseName(code: Int): String = "PTP 0x${code.toString(16).uppercase()}"

    private fun openSocket(ticket: Long, eventChannel: Boolean): Socket {
        val socket = socketFactory()
        synchronized(resourceLock) {
            ensureOpen(ticket)
            if (eventChannel) eventSocket = socket else commandSocket = socket
        }
        try {
            socket.receiveBufferSize = CHUNK_SIZE
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = socketTimeoutMs
            socket.connect(InetSocketAddress(host, port), 10_000)
            synchronized(resourceLock) { ensureOpen(ticket) }
            return socket
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }

    private fun ensureOpen(ticket: Long) {
        if (lifecycle != ticket) throw IOException("连接已取消")
    }

    internal fun ensureConnected() {
        if (!isConnected) throw IOException("相机已断开，请重新连接")
    }

    override fun close() {
        val wasConnected = isConnected
        val sockets = synchronized(resourceLock) {
            lifecycle += 1
            isConnected = false
            (commandSocket to eventSocket).also {
                commandSocket = null
                eventSocket = null
            }
        }
        runCatching { sockets.first?.close() }
        runCatching { sockets.second?.close() }
        if (wasConnected) runCatching { onDisconnected?.invoke() }
    }

    private data class TransactionResult(val response: OperationResponse, val data: ByteArray)

    companion object {
        const val CHUNK_SIZE = 4 * 1024 * 1024
        private const val TAG = "PtpIpClient"
    }
}
