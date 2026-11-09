package com.chrisz.nikconnect.ptp

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PtpPacketsTest {
    @Test
    fun `init command has framing guid name and protocol version`() {
        val guid = ByteArray(16) { it.toByte() }
        val packet = encodeInitCommandRequest(guid, "NiKConnect")
        assertEquals(packet.size.toLong(), LittleEndianReader(packet).u32())
        assertEquals(PacketType.INIT_COMMAND_REQUEST, LittleEndianReader(packet.copyOfRange(4, 8)).u32().toInt())

        val reader = LittleEndianReader(packet.copyOfRange(8, packet.size))
        assertArrayEquals(guid, reader.bytes(16))
        assertArrayEquals("NiKConnect".toCharArray(), CharArray(10) { reader.u16().toChar() })
        assertEquals(0, reader.u16())
        assertEquals(0x00010000L, reader.u32())
    }

    @Test
    fun `operation request writes unsigned parameters in little endian`() {
        val packet = encodeOperationRequest(
            OpCode.GET_PARTIAL_OBJECT,
            9,
            listOf(0xffffffffL, 4 * 1024 * 1024L, 512),
        )
        val reader = LittleEndianReader(packet.copyOfRange(8, packet.size))
        assertEquals(1L, reader.u32()) // PTP/IP: no data or data-in is 1; data-out is 2.
        assertEquals(OpCode.GET_PARTIAL_OBJECT, reader.u16())
        assertEquals(9L, reader.u32())
        assertEquals(0xffffffffL, reader.u32())
        assertEquals(4 * 1024 * 1024L, reader.u32())
        assertEquals(512L, reader.u32())
    }

    @Test
    fun `response and data payload round trip`() {
        val response = OperationResponse(ResponseCode.OK, 42, listOf(128))
        val encoded = encodeOperationResponse(response)
        val decoded = decodeOperationResponse(encoded.copyOfRange(8, encoded.size))
        assertEquals(response, decoded)

        val data = byteArrayOf(1, 3, 3, 7)
        val framed = encodeDataPacket(PacketType.END_DATA, 42, data)
        val packet = readPacket(ByteArrayInputStream(framed))
        assertEquals(PacketType.END_DATA, packet.type)
        assertArrayEquals(data, dataPayload(packet.payload))
    }

    @Test
    fun `truncated packet fails instead of returning partial data`() {
        val framed = encodeDataPacket(PacketType.DATA, 1, ByteArray(100))
        assertThrows(Exception::class.java) {
            readPacket(ByteArrayInputStream(framed.copyOf(40)))
        }
    }
}
