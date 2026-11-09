package com.chrisz.nikconnect.ptp

import java.io.ByteArrayOutputStream

internal class LittleEndianReader(private val data: ByteArray) {
    var offset: Int = 0
        private set

    val remaining: Int get() = data.size - offset

    fun u8(): Int {
        require(remaining >= 1) { "缺少 UInt8，偏移 $offset" }
        return data[offset++].toInt() and 0xff
    }

    fun u16(): Int {
        require(remaining >= 2) { "缺少 UInt16，偏移 $offset" }
        val value = (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8)
        offset += 2
        return value
    }

    fun u32(): Long {
        require(remaining >= 4) { "缺少 UInt32，偏移 $offset" }
        val value = (data[offset].toLong() and 0xff) or
            ((data[offset + 1].toLong() and 0xff) shl 8) or
            ((data[offset + 2].toLong() and 0xff) shl 16) or
            ((data[offset + 3].toLong() and 0xff) shl 24)
        offset += 4
        return value
    }

    fun u64(): Long {
        val low = u32()
        val high = u32()
        return (high shl 32) or low
    }

    fun bytes(count: Int): ByteArray {
        require(count >= 0 && remaining >= count) { "数据截断，偏移 $offset，需要 $count 字节" }
        return data.copyOfRange(offset, offset + count).also { offset += count }
    }

    fun ptpString(): String {
        val count = u8()
        if (count == 0) return ""
        require(count <= remaining / 2) { "PTP 字符串长度异常：$count" }
        val chars = CharArray(count)
        for (index in 0 until count) chars[index] = u16().toChar()
        return String(chars).trimEnd('\u0000')
    }

    fun u16Array(): List<Int> {
        val count = u32()
        require(count <= Int.MAX_VALUE.toLong() && count * 2 <= remaining.toLong()) {
            "UInt16 数组长度异常：$count"
        }
        return List(count.toInt()) { u16() }
    }
}

internal class LittleEndianWriter {
    private val output = ByteArrayOutputStream()

    fun u8(value: Int) = output.write(value and 0xff)

    fun u16(value: Int) {
        u8(value)
        u8(value ushr 8)
    }

    fun u32(value: Long) {
        u8(value.toInt())
        u8((value ushr 8).toInt())
        u8((value ushr 16).toInt())
        u8((value ushr 24).toInt())
    }

    fun u32(value: Int) = u32(value.toLong())

    fun u64(value: Long) {
        u32(value and 0xffffffffL)
        u32(value ushr 32)
    }

    fun bytes(value: ByteArray) = output.write(value)

    fun ptpString(value: String) {
        if (value.isEmpty()) {
            u8(0)
            return
        }
        val chars = value.toCharArray().take(254).toCharArray()
        u8(chars.size + 1)
        chars.forEach { u16(it.code) }
        u16(0)
    }

    fun toByteArray(): ByteArray = output.toByteArray()
}
