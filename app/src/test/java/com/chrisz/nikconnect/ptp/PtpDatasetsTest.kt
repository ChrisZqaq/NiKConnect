package com.chrisz.nikconnect.ptp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PtpDatasetsTest {
    @Test
    fun `device info reads real identity and partial object capability`() {
        val info = parseDeviceInfo(deviceInfoDataset())
        assertEquals("Nikon Corporation", info.manufacturer)
        assertEquals("Z f", info.model)
        assertEquals("1.30", info.deviceVersion)
        assertTrue(info.supportsPartialObject)
        assertFalse("Z f" == info.manufacturer)
    }

    @Test
    fun `object info reads size unicode filename and media type`() {
        val writer = LittleEndianWriter()
        writer.u32(0x10001)
        writer.u16(0x3801)
        writer.u16(0)
        writer.u32(8_500_000)
        writer.u16(0x3801)
        writer.u32(24_000)
        writer.u32(160) // ThumbWidth
        writer.u32(120) // ThumbHeight
        writer.u32(6000) // ImageWidth
        writer.u32(4000) // ImageHeight
        writer.u32(24) // ImageBitDepth
        writer.u32(0) // ParentObject
        writer.u16(0)
        writer.u32(0)
        writer.u32(0)
        writer.ptpString("DSC_测试.JPG")
        writer.ptpString("20261027T143900")
        writer.ptpString("20261027T143900")
        writer.ptpString("")

        val photo = parseObjectInfo(7, writer.toByteArray())
        assertEquals(7, photo.handle)
        assertEquals(8_500_000, photo.size)
        assertEquals("DSC_测试.JPG", photo.filename)
        assertEquals("20261027T143900", photo.captureDate)
        assertTrue(photo.isJpeg)
        assertFalse(photo.isRaw)
    }

    @Test
    fun `uint32 array preserves unsigned handles`() {
        val writer = LittleEndianWriter()
        writer.u32(2)
        writer.u32(0xffffffffL)
        writer.u32(7)
        assertEquals(listOf(0xffffffffL, 7L), decodeU32Array(writer.toByteArray()))
    }
}

internal fun deviceInfoDataset(): ByteArray {
    val writer = LittleEndianWriter()
    writer.u16(100)
    writer.u32(0x0000000a)
    writer.u16(100)
    writer.ptpString("Nikon")
    writer.u16(0)
    listOf(
        listOf(OpCode.GET_DEVICE_INFO, OpCode.OPEN_SESSION, OpCode.GET_PARTIAL_OBJECT),
        listOf(0x4002),
        listOf(0x5001),
        listOf(0x3801),
        listOf(0x3801, 0x3802),
    ).forEach { values ->
        writer.u32(values.size)
        values.forEach(writer::u16)
    }
    writer.ptpString("Nikon Corporation")
    writer.ptpString("Z f")
    writer.ptpString("1.30")
    writer.ptpString("000000000000")
    return writer.toByteArray()
}
