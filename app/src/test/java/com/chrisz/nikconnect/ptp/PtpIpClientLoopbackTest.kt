package com.chrisz.nikconnect.ptp

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PtpIpClientLoopbackTest {
    @Test
    fun `direct socket can connect to loopback`() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            Socket(Proxy.NO_PROXY).use { socket ->
                socket.receiveBufferSize = PtpIpClient.CHUNK_SIZE
                socket.tcpNoDelay = true
                socket.keepAlive = true
                socket.soTimeout = 30_000
                socket.connect(InetSocketAddress("127.0.0.1", server.localPort), 10_000)
                assertTrue(socket.isConnected)
            }
        }
    }

    @Test
    fun `connects both channels and resumes partial object at requested offset`() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 8_000
            val serverError = AtomicReference<Throwable?>()
            val worker = thread(isDaemon = true) {
                runCatching { fakeCamera(server) }.onFailure(serverError::set)
            }
            val client = PtpIpClient(
                host = "127.0.0.1",
                port = server.localPort,
                socketFactory = { Socket(Proxy.NO_PROXY) },
                settleDelayMs = 0,
            )
            val info = client.connect()
            assertEquals("Z f", info.model)
            assertTrue(info.supportsPartialObject)

            val photo = PhotoObject(7, 1, 0x3801, 10, "DSC_0007.JPG", "", 0)
            val chunks = mutableListOf<ByteArray>()
            client.copyObject(photo, startOffset = 4) { offset, data ->
                assertEquals(4, offset)
                chunks += data
            }
            assertArrayEquals("efghij".toByteArray(), chunks.single())
            val fired = java.util.concurrent.atomic.AtomicBoolean(false)
            client.onDisconnected = { fired.set(true) }
            client.close()
            assertTrue("close 后应触发 onDisconnected 回调", fired.get())
            worker.join(8_000)
            assertNull(serverError.get())
        }
    }

    @Test
    fun `onDisconnected is not fired when client was never connected`() {
        val client = PtpIpClient(
            host = "127.0.0.1",
            port = 1, // 不会连接成功
            socketFactory = { Socket(Proxy.NO_PROXY) },
            settleDelayMs = 0,
        )
        val fired = java.util.concurrent.atomic.AtomicBoolean(false)
        client.onDisconnected = { fired.set(true) }
        client.close()
        assertTrue("未连接过就不应触发回调", !fired.get())
    }

    @Test
    fun `photo pages use handle offsets independent of returned photo filtering`() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 8_000
            val serverError = AtomicReference<Throwable?>()
            val worker = thread(isDaemon = true) { runCatching { fakePhotoListCamera(server) }.onFailure(serverError::set) }
            val client = PtpIpClient(
                host = "127.0.0.1",
                port = server.localPort,
                socketFactory = { Socket(Proxy.NO_PROXY) },
                settleDelayMs = 0,
            )
            client.connect()
            val first = client.listPhotos(handleOffset = 0, pageSize = 2)
            assertEquals(3, first.first)
            assertEquals(listOf(3L, 2L), first.second.map { it.handle })
            assertTrue(first.third)
            val second = client.listPhotos(handleOffset = 2, pageSize = 2)
            assertEquals(listOf(1L), second.second.map { it.handle })
            assertFalse(second.third)
            client.close()
            worker.join(8_000)
            assertNull(serverError.get())
        }
    }

    private fun fakePhotoListCamera(server: ServerSocket) {
        val command = server.accept().apply { soTimeout = 8_000 }
        readPacket(command.getInputStream())
        val ack = LittleEndianWriter().apply {
            u32(77); bytes(ByteArray(16)); "Fake Z f".forEach { u16(it.code) }; u16(0); u32(0x00010000)
        }
        writePacket(command.getOutputStream(), PacketType.INIT_COMMAND_ACK, ack.toByteArray())
        val event = server.accept().apply { soTimeout = 8_000 }
        val eventInit = readPacket(event.getInputStream())
        writePacket(event.getOutputStream(), PacketType.INIT_EVENT_ACK)
        val session = readOperation(command)
        respond(command, session.second, ResponseCode.OK)
        val device = readOperation(command)
        respondWithData(command, device.second, deviceInfoDataset())
        while (true) {
            val op = try { readOperation(command) } catch (_: Exception) { return }
            when (op.first) {
                OpCode.GET_OBJECT_HANDLES -> {
                    val handles = LittleEndianWriter().apply { u32(3); u32(1); u32(2); u32(3) }.toByteArray()
                    respondWithData(command, op.second, handles)
                }
                OpCode.GET_OBJECT_INFO -> {
                    val reader = LittleEndianReader(op.third)
                    reader.u32(); reader.u16(); reader.u32(); val handle = reader.u32()
                    val format = if (handle == 2L) 0x3000 else 0x3801
                    respondWithData(command, op.second, objectInfoDataset(handle, format))
                }
                else -> respond(command, op.second, 0x2005)
            }
        }
    }

    private fun objectInfoDataset(handle: Long, format: Int): ByteArray = LittleEndianWriter().apply {
        u32(1); u16(format); u16(0); u32(100); u16(0); u32(0)
        repeat(5) { u32(0) }
        u32(0); u16(0); u32(0); u32(0)
        ptpString("IMG_$handle.JPG"); ptpString(""); ptpString(""); ptpString("")
    }.toByteArray()

    private fun fakeCamera(server: ServerSocket) {
        val command = server.accept()
        command.soTimeout = 8_000
        val init = readPacket(command.getInputStream())
        assertEquals(PacketType.INIT_COMMAND_REQUEST, init.type)
        val ack = LittleEndianWriter()
        ack.u32(77)
        ack.bytes(ByteArray(16))
        "Fake Z f".forEach { ack.u16(it.code) }
        ack.u16(0)
        ack.u32(0x00010000)
        writePacket(command.getOutputStream(), PacketType.INIT_COMMAND_ACK, ack.toByteArray())

        val event = server.accept()
        event.soTimeout = 8_000
        val eventInit = readPacket(event.getInputStream())
        assertEquals(PacketType.INIT_EVENT_REQUEST, eventInit.type)
        assertEquals(77, LittleEndianReader(eventInit.payload).u32())
        writePacket(event.getOutputStream(), PacketType.INIT_EVENT_ACK)

        val session = readOperation(command)
        assertEquals(OpCode.OPEN_SESSION, session.first)
        respond(command, session.second, ResponseCode.OK)

        val device = readOperation(command)
        assertEquals(OpCode.GET_DEVICE_INFO, device.first)
        val deviceData = deviceInfoDataset()
        respondWithData(command, device.second, deviceData)

        val partial = readOperation(command)
        assertEquals(OpCode.GET_PARTIAL_OBJECT, partial.first)
        val reader = LittleEndianReader(partial.third)
        assertEquals(DataPhase.DATA_IN.toLong(), reader.u32())
        assertEquals(OpCode.GET_PARTIAL_OBJECT, reader.u16())
        reader.u32()
        assertEquals(7, reader.u32())
        assertEquals(4, reader.u32())
        assertEquals(6, reader.u32())
        respondWithData(command, partial.second, "efghij".toByteArray())
    }

    private fun readOperation(socket: Socket): Triple<Int, Long, ByteArray> {
        val packet = readPacket(socket.getInputStream())
        assertEquals(PacketType.OPERATION_REQUEST, packet.type)
        val reader = LittleEndianReader(packet.payload)
        reader.u32()
        val opcode = reader.u16()
        val transaction = reader.u32()
        return Triple(opcode, transaction, packet.payload)
    }

    private fun respond(socket: Socket, transaction: Long, code: Int) {
        socket.getOutputStream().write(
            encodeOperationResponse(OperationResponse(code, transaction, emptyList())),
        )
        socket.getOutputStream().flush()
    }

    private fun respondWithData(socket: Socket, transaction: Long, data: ByteArray) {
        val start = LittleEndianWriter()
        start.u32(transaction)
        start.u64(data.size.toLong())
        writePacket(socket.getOutputStream(), PacketType.START_DATA, start.toByteArray())
        socket.getOutputStream().write(encodeDataPacket(PacketType.END_DATA, transaction, data))
        socket.getOutputStream().flush()
        respond(socket, transaction, ResponseCode.OK)
    }

    @Test
    fun `probeCommand returns supported result with data preview`() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 8_000
            val worker = thread(isDaemon = true) { runCatching { fakeProbeCamera(server, mapOf(0x9028 to byteArrayOf(1, 0, 0, 0, 0x10, 0xD0.toByte()))) } }
            val client = PtpIpClient(host = "127.0.0.1", port = server.localPort, socketFactory = { Socket(Proxy.NO_PROXY) }, settleDelayMs = 0)
            client.connect()
            val probe = client.probeVendorPropCodes()
            assertTrue(probe.supported)
            assertEquals(0x9028, probe.opcode)
            assertEquals(0xD010, ((probe.dataPreview[4].toInt() and 0xFF) or ((probe.dataPreview[5].toInt() and 0xFF) shl 8)))
            client.close()
            worker.join(8_000)
        }
    }

    @Test
    fun `probeCommand records failure instead of throwing`() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 8_000
            val worker = thread(isDaemon = true) { runCatching { fakeProbeCamera(server, mapOf(0x9201 to null)) } }
            val client = PtpIpClient(host = "127.0.0.1", port = server.localPort, socketFactory = { Socket(Proxy.NO_PROXY) }, settleDelayMs = 0)
            client.connect()
            val probe = client.probeCommand(OpCode.NIKON_ADVANCED_TRANSFER)
            assertFalse(probe.supported)
            assertEquals(0x2005, probe.responseCode) // OperationNotSupported
            client.close()
            worker.join(8_000)
        }
    }

    @Test
    fun `readPropValue sends prop code parameter`() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 8_000
            val worker = thread(isDaemon = true) { runCatching { fakeProbeCamera(server, mapOf(0x1015 to byteArrayOf(1, 0, 0, 0))) } }
            val client = PtpIpClient(host = "127.0.0.1", port = server.localPort, socketFactory = { Socket(Proxy.NO_PROXY) }, settleDelayMs = 0)
            client.connect()
            val probe = client.readPropValue(0xD010)
            assertTrue(probe.supported)
            client.close()
            worker.join(8_000)
        }
    }

    /**
     * 探针专用假相机：按 opcode→data 映射应答（null=回 OperationNotSupported），
     * OPEN_SESSION/GET_DEVICE_INFO 与标准 fakeCamera 相同，之后按序应答任意命令。
     */
    private fun fakeProbeCamera(server: ServerSocket, responses: Map<Int, ByteArray?>) {
        val command = server.accept()
        command.soTimeout = 8_000
        readPacket(command.getInputStream())
        val ack = LittleEndianWriter()
        ack.u32(88)
        ack.bytes(ByteArray(16))
        "Fake Probe".forEach { ack.u16(it.code) }
        ack.u16(0)
        ack.u32(0x00010000)
        writePacket(command.getOutputStream(), PacketType.INIT_COMMAND_ACK, ack.toByteArray())

        val event = server.accept()
        event.soTimeout = 8_000
        readPacket(event.getInputStream())
        writePacket(event.getOutputStream(), PacketType.INIT_EVENT_ACK)

        val session = readOperation(command)
        assertEquals(OpCode.OPEN_SESSION, session.first)
        respond(command, session.second, ResponseCode.OK)

        val device = readOperation(command)
        assertEquals(OpCode.GET_DEVICE_INFO, device.first)
        respondWithData(command, device.second, deviceInfoDataset())

        // 后续命令按映射应答（顺序不限，循环到客户端关闭）
        while (true) {
            val op = try { readOperation(command) } catch (e: Exception) { return }
            val data = responses[op.first]
            if (data != null) {
                respondWithData(command, op.second, data)
            } else if (responses.containsKey(op.first)) {
                respond(command, op.second, 0x2005)
            } else {
                respond(command, op.second, 0x2005)
            }
        }
    }
}
