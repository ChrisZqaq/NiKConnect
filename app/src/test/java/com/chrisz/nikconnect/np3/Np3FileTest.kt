package com.chrisz.nikconnect.np3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * NP3 编解码器测试。
 *
 * 夹具说明（2026-11-14 起）：**不再使用第三方 `.NP3` 样本**。仓库里曾经的 32 个真实样本
 * （来自 Timor88/NikonNP3）属于他人作品，公开仓库里放二进制不构成本项目的合理使用，
 * 已在 0.8.7 移除以避免再分发。这里改成用本仓自己的格式实现（`docs/research/np3-format.md`）
 * 现场拼字节：只要 parse→encode 能一致，编解码逻辑就是对的；
 * 真实相机导出的样本由开发者在本机自测，不入库。
 */
class Np3FileTest {

    /** 头部 0x00..0x17（含 name 长度 = 20），取自格式逆向结果。 */
    private val header = hex("4e4350000000010000000004303331300000020000000014")

    private fun hex(s: String): ByteArray =
        s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** 按格式规范拼一个 NP3：header + 20B 名字 + 0000 + TLV + tail。 */
    private fun buildNp3(
        name: String,
        tags: Map<Int, ByteArray>,
        tail: ByteArray = byteArrayOf(0, 0),
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header)
        val nameBytes = name.encodeToByteArray().take(20).toByteArray()
        out.write(nameBytes + ByteArray(20 - nameBytes.size))
        out.write(0x00); out.write(0x00)
        for (tag in tags.keys.sorted()) {
            val v = checkNotNull(tags[tag])
            out.write(tag)
            out.write(0x00); out.write(0x00); out.write(0x00)
            out.write((v.size shr 8) and 0xff); out.write(v.size and 0xff)
            out.write(v)
            out.write(0x00); out.write(0x00)
        }
        out.write(tail)
        return out.toByteArray()
    }

    private fun u16(v: Int) = byteArrayOf(((v shr 8) and 0xff).toByte(), (v and 0xff).toByte())

    /** 392 字节短版：只有 u16 参数 + 2 字节尾。 */
    private fun shortVariant(): ByteArray = buildNp3(
        name = "NiKConnect_Test_01a",
        tags = linkedMapOf(
            0x03 to u16(0x0020),
            0x05 to u16(0xff01),
            0x06 to u16(0x8104),   // 锐化 +1（0.25 步组）
            0x14 to u16(0x8001),   // 快速锐化 0
            0x16 to u16(0x8204),   // 中等锐化 +2
            0x19 to u16(0x8301),   // 对比 +3
            0x1d to u16(0x7101),   // 黑色色阶 -15
            0x1f to ByteArray(28) { (0x40 + it).toByte() },
            0x20 to ByteArray(20) { (0x10 + it).toByte() },
        ),
        tail = byteArrayOf(0x00, 0x00),
    )

    /** 978 字节长版：尾部带 BI0 自定义曲线块。 */
    private fun longVariant(): ByteArray {
        val curve = ByteArray(586)
        "BI0".encodeToByteArray().copyInto(curve, 0)
        for (i in 0 until 253) {
            val v = 0x0039 + i * 0x80
            curve[0x4a + i * 2] = ((v shr 8) and 0xff).toByte()
            curve[0x4a + i * 2 + 1] = (v and 0xff).toByte()
        }
        return buildNp3(
            name = "NiKConnect_Test_Curve",
            tags = linkedMapOf(
                0x03 to u16(0x0020),
                0x06 to u16(0x8004),
                0x1f to ByteArray(28) { it.toByte() },
                0x20 to ByteArray(20) { (0x20 + it).toByte() },
            ),
            tail = curve + byteArrayOf(0x00, 0x00, 0x00, 0x00),
        )
    }

    @Test
    fun `short variant roundtrips byte exact`() {
        val bytes = shortVariant()
        assertTrue("unexpected size ${bytes.size}", bytes.size > 150)
        assertArrayEquals(bytes, Np3File.parse(bytes).encode())
    }

    @Test
    fun `long variant with custom curve roundtrips byte exact`() {
        val bytes = longVariant()
        assertTrue("unexpected size ${bytes.size}", bytes.size > 700)
        assertArrayEquals(bytes, Np3File.parse(bytes).encode())
    }

    @Test
    fun `parse extracts name and core tags`() {
        val np3 = Np3File.parse(shortVariant())
        assertEquals("NiKConnect_Test_01a", np3.name)
        assertEquals(0x20, np3.u16(0x03))
        assertEquals(0, np3.signed(0x14))   // 0x8001 → hi 0x80 = 0
        assertEquals(1, np3.signed(0x06))   // 0x8104 → +1
        assertEquals(3, np3.signed(0x19))   // 0x8301 → +3
        assertEquals(-15, np3.signed(0x1d)) // 0x7101 → -15（黑色色阶）
        assertEquals(28, np3.tags[0x1f]!!.size)
        assertEquals(20, np3.tags[0x20]!!.size)
    }

    @Test
    fun `tail keeps BI0 curve block for long variant`() {
        val np3 = Np3File.parse(longVariant())
        assertEquals(586 + 4, np3.tail.size)
        assertEquals("BI0", np3.tail.copyOfRange(0, 3).decodeToString())
    }

    @Test
    fun `rejects data without magic`() {
        val bad = shortVariant().also { it[0] = 'X'.code.toByte() }
        assertTrue(runCatching { Np3File.parse(bad) }.isFailure)
    }
}
