package com.chrisz.nikconnect.np3

/**
 * NP3 (Nikon Picture Control) 二进制文件解析与生成。
 *
 * 格式逆向自 32 个真实样本，字节级重组验证 32/32 无损。
 * 详见 docs/research/np3-format.md。
 *
 * 布局：
 *  - 0x00: "NCP\0" 魔数
 *  - 0x04: 0000 0100（版本）
 *  - 0x08: 0000 0004
 *  - 0x0C: "0310"（格式串）
 *  - 0x10: 0000 0200
 *  - 0x14: 0000 0014（name 长度 = 20）
 *  - 0x18: 20B 方案名（picture_controll_name，\0 填充）
 *  - 0x2C: 0000
 *  - 0x2E: TLV 参数区：tag u8 + 3B 00 + len u16(BE) + val + 2B 00
 *    - tag 0x03..0x1E: len=2，val u16 大端
 *    - tag 0x1F: len=28 基准曲线锚点
 *    - tag 0x20: len=20 色相调优表
 *  - 尾部：392B 版为 0000；978B 版含 BI0 自定义色调曲线块 + 0000 0000
 *
 * @property name 方案名（picture_controll_name）
 * @property tags TLV 字段表（tag → 字节值）
 * @property tail 尾部原始字节（392 版 2 字节；978 版含 BI0 曲线块）
 */
class Np3File private constructor(
    val name: String,
    val tags: Map<Int, ByteArray>,
    val tail: ByteArray,
) {
    /** tag 值按 u16 大端读取（仅对 len==2 的 tag 合法）。 */
    fun u16(tag: Int): Int = checkNotNull(tags[tag]) { "tag $tag missing" }.let {
        require(it.size == 2) { "tag $tag is not u16" }
        ((it[0].toInt() and 0xff) shl 8) or (it[1].toInt() and 0xff)
    }

    /**
     * 有符号参数值：0x80 基准（0x80=0，0x81=+1，0x7F=-1）。
     * tag 0x19..0x1E 的色彩轴采用此编码（低字节恒为 0x01 标志位）。
     */
    fun signed(tag: Int): Int {
        val v = u16(tag)
        val hi = (v shr 8) and 0xff
        return hi - 0x80
    }

    fun encode(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(HEADER, 0, HEADER.size)  // HEADER = 0x00..0x17（含 name_len=0x0014）
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

    companion object {
        private val HEADER = hex(
            "4e4350000000010000000004303331300000020000000014"
        )

        /** 完整头部长度（含 name 长度字段，不含 name 本体）：0x18。 */
        const val HEADER_LEN = 0x18

        fun parse(data: ByteArray): Np3File {
            require(data.size >= HEADER_LEN + 2) { "too short: ${data.size}" }
            require(data[0] == 'N'.code.toByte() && data[1] == 'C'.code.toByte() &&
                data[2] == 'P'.code.toByte() && data[3] == 0.toByte()) { "bad magic" }
            val name = data.copyOfRange(0x18, 0x2c)
                .takeWhile { it != 0.toByte() }
                .toByteArray()
                .decodeToString()
            var off = 0x2e
            val tags = mutableMapOf<Int, ByteArray>()
            while (off + 6 <= data.size) {
                val tag = data[off].toInt() and 0xff
                val len = ((data[off + 4].toInt() and 0xff) shl 8) or
                    (data[off + 5].toInt() and 0xff)
                if (tag == 0 && len == 0) { off += 6; break }
                tags[tag] = data.copyOfRange(off + 6, off + 6 + len)
                off += 6 + len + 2
                if (tag == 0x20) break
            }
            return Np3File(name, tags, data.copyOfRange(off, data.size))
        }

        private fun hex(s: String): ByteArray =
            s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
