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
}
