package com.chrisz.nikconnect.np3

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用 32 个真实 NP3 样本（Timor88/NikonNP3）做字节级真值验证：
 * parse → encode 必须与原文件完全一致。
 */
class Np3FileTest {

    private fun samples(): List<Pair<String, ByteArray>> =
        javaClass.classLoader.getResources("np3")
            .toList()
            .flatMap { url ->
                val dir = java.io.File(url.toURI())
                dir.listFiles { f -> f.name.endsWith(".NP3") }!!.sortedBy { it.name }
            }
            .map { it.name to it.readBytes() }

    @Test
    fun `roundtrip all 32 official samples byte exact`() {
        val files = samples()
        assertTrue("expected >=32 samples, got ${files.size}", files.size >= 32)
        var ok = 0
        for ((name, bytes) in files) {
            val np3 = Np3File.parse(bytes)
            assertArrayEquals("roundtrip failed for $name", bytes, np3.encode())
            ok++
        }
        assertEquals(files.size, ok)
    }

    @Test
    fun `parse extracts sample name and core tags`() {
        val (_, bytes) = samples().first { it.first == "Negative_Cy_01a.NP3" }
        val np3 = Np3File.parse(bytes)
        assertEquals("Negative_Cy_01a", np3.name)
        assertEquals(0x20, np3.u16(0x03))
        assertEquals(0, np3.signed(0x14))    // 0x8001 → hi 0x80 = 0
        // tag 0x1d: 0x7101 → hi 0x71 = -15 (黑色色阶编码)
        assertEquals(-15, np3.signed(0x1d))
        // tag 0x1F 基准曲线锚点 28B、tag 0x20 色相调优表 20B
        assertEquals(28, np3.tags[0x1f]!!.size)
        assertEquals(20, np3.tags[0x20]!!.size)
    }

    @Test
    fun `sample sizes are 392 or 978 bytes`() {
        val sizes = samples().map { it.second.size }.toSet()
        assertTrue("unexpected sizes $sizes", sizes.all { it == 392 || it == 978 })
    }
}
