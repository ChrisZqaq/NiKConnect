package com.chrisz.nikconnect.ptp

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

internal object PacketType {
    const val INIT_COMMAND_REQUEST = 1
    const val INIT_COMMAND_ACK = 2
    const val INIT_EVENT_REQUEST = 3
    const val INIT_EVENT_ACK = 4
    const val INIT_FAIL = 5
    const val OPERATION_REQUEST = 6
    const val OPERATION_RESPONSE = 7
    const val START_DATA = 9
    const val DATA = 10
    const val END_DATA = 12
    const val PROBE_REQUEST = 13
    const val PROBE_RESPONSE = 14
}

internal object DataPhase {
    const val NONE = 1
    const val DATA_IN = 1
    const val DATA_OUT = 2
}

internal object OpCode {
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val GET_DEVICE_PROP_DESC = 0x1014
    const val GET_DEVICE_PROP_VALUE = 0x1015
    const val SET_DEVICE_PROP_VALUE = 0x1016
    const val GET_OBJECT_HANDLES = 0x1007
    const val GET_OBJECT_INFO = 0x1008
    const val GET_THUMB = 0x100a
    const val GET_PARTIAL_OBJECT = 0x101b
    const val NIKON_GET_LARGE_THUMB = 0x90c4
    // B0 探针：尼康厂商扩展（具体 opcode 待 Zf 真机枚举，先按 ZRelay/公开资料候选值）
    const val NIKON_ADVANCED_TRANSFER = 0x9201
    const val NIKON_GET_VENDOR_PROP_CODES = 0x9028
}

internal object PropCode {
    /** 尼康厂商属性码区域 0xD000+（0xD010=PictureControl 相关候选，真机枚举确认） */
    const val NIKON_VENDOR_BASE = 0xD000
}

internal object ResponseCode {
    const val OK = 0x2001
    const val SESSION_ALREADY_OPEN = 0x201e
}

internal data class PtpPacket(val type: Int, val payload: ByteArray)

internal data class InitAck(val connectionNumber: Long, val responderName: String)

internal data class OperationResponse(
    val responseCode: Int,
    val transactionId: Long,
    val params: List<Long>,
)

internal fun encodePacket(type: Int, payload: ByteArray = ByteArray(0)): ByteArray {
    val writer = LittleEndianWriter()
    writer.u32(payload.size + 8)
    writer.u32(type)
    writer.bytes(payload)
    return writer.toByteArray()
}

internal fun writePacket(output: OutputStream, type: Int, payload: ByteArray = ByteArray(0)) {
    output.write(encodePacket(type, payload))
    output.flush()
}

internal fun readPacket(input: InputStream, maxPacketSize: Int = 8 * 1024 * 1024): PtpPacket {
    val header = readFully(input, 8)
    val reader = LittleEndianReader(header)
    val length = reader.u32()
    require(length >= 8 && length <= maxPacketSize.toLong()) { "非法 PTP/IP 包长度：$length" }
    val type = reader.u32().toInt()
    return PtpPacket(type, readFully(input, length.toInt() - 8))
}

private fun readFully(input: InputStream, size: Int): ByteArray {
    val result = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val count = input.read(result, offset, size - offset)
        if (count < 0) throw EOFException("相机提前关闭了连接")
        if (count == 0) continue
        offset += count
    }
    return result
}

internal fun encodeInitCommandRequest(guid: ByteArray, friendlyName: String): ByteArray {
    require(guid.size == 16) { "Initiator GUID 必须为 16 字节" }
    val payload = LittleEndianWriter()
    payload.bytes(guid)
    val name = friendlyName.toCharArray()
    name.forEach { payload.u16(it.code) }
    payload.u16(0)
    payload.u32(0x00010000)
    return encodePacket(PacketType.INIT_COMMAND_REQUEST, payload.toByteArray())
}

internal fun decodeInitCommandAck(payload: ByteArray): InitAck {
    val reader = LittleEndianReader(payload)
    val connectionNumber = reader.u32()
    reader.bytes(16)
    val name = StringBuilder()
    while (reader.remaining >= 2) {
        val char = reader.u16()
        if (char == 0) break
        name.append(char.toChar())
    }
    return InitAck(connectionNumber, name.toString())
}

internal fun encodeInitEventRequest(connectionNumber: Long): ByteArray {
    val payload = LittleEndianWriter()
    payload.u32(connectionNumber)
    return encodePacket(PacketType.INIT_EVENT_REQUEST, payload.toByteArray())
}

internal fun encodeOperationRequest(
    opcode: Int,
    transactionId: Long,
    params: List<Long> = emptyList(),
    dataPhase: Int = DataPhase.DATA_IN,
): ByteArray {
    val payload = LittleEndianWriter()
    payload.u32(dataPhase)
    payload.u16(opcode)
    payload.u32(transactionId)
    params.forEach(payload::u32)
    return encodePacket(PacketType.OPERATION_REQUEST, payload.toByteArray())
}

internal fun decodeOperationResponse(payload: ByteArray): OperationResponse {
    val reader = LittleEndianReader(payload)
    val responseCode = reader.u16()
    val transactionId = reader.u32()
    val params = mutableListOf<Long>()
    while (reader.remaining >= 4) params += reader.u32()
    return OperationResponse(responseCode, transactionId, params)
}

internal fun dataPayload(payload: ByteArray): ByteArray {
    val reader = LittleEndianReader(payload)
    reader.u32()
    return reader.bytes(reader.remaining)
}

internal fun encodeDataPacket(type: Int, transactionId: Long, data: ByteArray): ByteArray {
    require(type == PacketType.DATA || type == PacketType.END_DATA)
    val payload = LittleEndianWriter()
    payload.u32(transactionId)
    payload.bytes(data)
    return encodePacket(type, payload.toByteArray())
}

internal fun encodeOperationResponse(response: OperationResponse): ByteArray {
    val payload = LittleEndianWriter()
    payload.u16(response.responseCode)
    payload.u32(response.transactionId)
    response.params.forEach(payload::u32)
    return encodePacket(PacketType.OPERATION_RESPONSE, payload.toByteArray())
}
