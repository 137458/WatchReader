package com.watchreader

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * MOBI / AZW3（PalmDOC 容器）文本抽取模块。
 *
 * 职责边界：PDB 头与记录表 → 逐条读记录 → 剥尾随数据 → PalmDOC 解压 → 增量解码 →
 * 交给 [HtmlFormat] 的剥离器。全程流式，峰值内存为单条记录量级，不整书驻留。
 * palmDocDecompress / trimTrailingData 对本包可见：BookTextConverter 上的同名成员只做委托。
 */
internal object MobiFormat {

    private const val MOBI_PDB_HEADER_SIZE = 78
    private const val MOBI_RECORD_ENTRY_SIZE = 8
    private const val MAX_DECOMPRESSED_RECORD_BYTES = 1 shl 22

    fun convert(input: InputStream, out: Appendable) {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, 65536)

        val pdbHeader = readExactly(buffered, MOBI_PDB_HEADER_SIZE) { "文件过小，不是有效的 MOBI/AZW3 文件" }
        if (!hasAsciiTag(pdbHeader, 60, "BOOK") || !hasAsciiTag(pdbHeader, 64, "MOBI")) {
            throw BookFormatException("不是有效的 MOBI/AZW3 文件")
        }
        val numRecords = u16be(pdbHeader, 76)
        if (numRecords < 2) throw BookFormatException("MOBI 记录表为空")

        val tableBytes = readExactly(buffered, numRecords * MOBI_RECORD_ENTRY_SIZE) { "MOBI 记录表被截断" }
        val offsets = IntArray(numRecords)
        for (i in 0 until numRecords) {
            val off = u32be(tableBytes, i * MOBI_RECORD_ENTRY_SIZE)
            if (off < 0) throw BookFormatException("MOBI 文件过大，无法解析")
            offsets[i] = off
        }
        val expectedTableEnd = MOBI_PDB_HEADER_SIZE + numRecords * MOBI_RECORD_ENTRY_SIZE
        if (offsets[0] < expectedTableEnd) throw BookFormatException("MOBI 记录表损坏")
        if (offsets[0] > expectedTableEnd) skipFully(buffered, (offsets[0] - expectedTableEnd).toLong())

        val rec0 = readRange(buffered, offsets, 0)
        if (rec0.size < 16) throw BookFormatException("MOBI 头部损坏")

        val compression = u16be(rec0, 0)
        val textRecordCount = u16be(rec0, 8)
        val crypto = u16be(rec0, 12)
        if (crypto != 0) throw BookFormatException("文件受 DRM 保护，无法阅读")
        when (compression) {
            1, 2 -> {}
            0x4448 -> throw BookFormatException("暂不支持 HUFF/CDIC 压缩的 MOBI 文件，请用 Calibre 重新转换后导入")
            else -> throw BookFormatException("不支持的 MOBI 压缩类型: $compression")
        }

        var extraFlags = 0
        var charset = StandardCharsets.UTF_8
        if (rec0.size >= 40 && hasAsciiTag(rec0, 16, "MOBI")) {
            val mobiLength = u32be(rec0, 20)
            // codepage 1252 → windows-1252，65001 → UTF-8（其余回落 UTF-8）
            if (u32be(rec0, 28) == 1252) charset = Charset.forName("windows-1252")
            // 额外记录数据标志（参考 KindleUnpack：mobi_length >= 0xE4 且 min version >= 5）
            if (mobiLength >= 0xE4 && rec0.size >= 108 && u32be(rec0, 104) >= 5 && rec0.size >= 244) {
                extraFlags = u16be(rec0, 242)
            }
        }

        val count = minOf(textRecordCount, numRecords - 1)
        if (count < 1) throw BookFormatException("MOBI 无正文记录")

        val stripper = HtmlTextStripper(out)
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        var carry = ByteArray(0)
        for (i in 1..count) {
            val record = readRange(buffered, offsets, i)
            val trimmed = if (extraFlags != 0) trimTrailingData(record, extraFlags) else record
            val raw = if (compression == 2) palmDocDecompress(trimmed) else trimmed
            if (raw.isEmpty()) continue
            carry = HtmlFormat.feedDecoded(decoder, carry + raw, false, stripper)
        }
        if (carry.isNotEmpty()) HtmlFormat.feedDecoded(decoder, carry, true, stripper)
        stripper.finish()
        // 正文记录之后的资源记录（图片/字体等）不读取，流由调用方关闭
    }

    /**
     * PalmDOC LZ77 解压（参考 KindleUnpack PalmdocReader）：
     * 1~8 → 后续 n 字节为字面串；<128 → 字面；>=192 → 空格 + (c^0x80)；
     * 128~191 → 双字节回引 (distance=c12>>3 & 0x7FF, length=(c12&7)+3)，允许重叠拷贝。
     */
    internal fun palmDocDecompress(data: ByteArray): ByteArray {
        var buf = ByteArray(data.size * 2 + 4096)
        var len = 0

        fun ensure(extra: Int) {
            if (len + extra > buf.size) {
                var newSize = buf.size * 2
                while (newSize < len + extra) newSize *= 2
                buf = buf.copyOf(newSize)
            }
        }

        var p = 0
        val n = data.size
        while (p < n) {
            if (len > MAX_DECOMPRESSED_RECORD_BYTES) throw BookFormatException("MOBI 记录解压异常膨胀")
            val c = data[p].toInt() and 0xFF
            p++
            when {
                c in 1..8 -> {
                    val take = minOf(c, n - p)
                    if (take > 0) {
                        ensure(take)
                        System.arraycopy(data, p, buf, len, take)
                        len += take
                        p += take
                    }
                }
                c < 128 -> {
                    ensure(1)
                    buf[len++] = c.toByte()
                }
                c >= 192 -> {
                    ensure(2)
                    buf[len++] = 0x20
                    buf[len++] = (c and 0x7F).toByte()
                }
                else -> {
                    if (p >= n) break
                    val pair = (c shl 8) or (data[p].toInt() and 0xFF)
                    p++
                    val distance = (pair shr 3) and 0x7FF
                    val length = (pair and 7) + 3
                    if (distance in 1..len) {
                        ensure(length)
                        repeat(length) {
                            buf[len] = buf[len - distance]
                            len++
                        }
                    }
                    // distance 越界视为损坏数据，跳过该回引
                }
            }
        }
        return buf.copyOf(len)
    }

    /**
     * 剥离文本记录的尾随数据（参考 KindleUnpack getRawML）：
     * extraFlags 低位起每个置位（除 bit0）对应一个反向变长整数描述的尾随条目；
     * bit0 为 multibyte 重叠标记，按末字节低 2 位 +1 剥离。顺序：先条目后 multibyte。
     */
    internal fun trimTrailingData(data: ByteArray, extraFlags: Int): ByteArray {
        var end = data.size

        fun sizeOfTrailingEntry(): Int {
            // 参考实现按末 4 字节从左到右累加，遇高位字节重置
            var num = 0
            val start = maxOf(0, end - 4)
            for (i in start until end) {
                val v = data[i].toInt() and 0xFF
                if (v and 0x80 != 0) num = 0
                num = (num shl 7) or (v and 0x7F)
            }
            return num
        }

        var flags = extraFlags
        var trailers = 0
        while (flags > 1) {
            if (flags and 2 != 0) trailers++
            flags = flags shr 1
        }
        repeat(trailers) {
            if (end <= 0) return@repeat
            end -= sizeOfTrailingEntry()
            if (end < 0) end = 0
        }
        if (extraFlags and 1 != 0 && end > 0) {
            end -= (data[end - 1].toInt() and 3) + 1
            if (end < 0) end = 0
        }
        return if (end == data.size) data else data.copyOf(end)
    }
    private inline fun readExactly(stream: InputStream, count: Int, onError: () -> String): ByteArray {
        val out = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val n = stream.read(out, filled, count - filled)
            if (n <= 0) throw BookFormatException(onError())
            filled += n
        }
        return out
    }
    /** 依据记录表边界从流中精确读取第 [index] 条记录（最后一条读到 EOF） */
    private fun readRange(stream: InputStream, offsets: IntArray, index: Int): ByteArray {
        val start = offsets[index]
        val end = if (index + 1 < offsets.size) offsets[index + 1] else -1
        if (end >= 0) {
            if (end < start) throw BookFormatException("MOBI 记录表损坏")
            return readExactly(stream, end - start) { "MOBI 文件被截断" }
        }
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = stream.read(chunk)
            if (n <= 0) break
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }
    private fun skipFully(stream: InputStream, total: Long) {
        var remaining = total
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val n = stream.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n <= 0) throw BookFormatException("MOBI 文件被截断")
            remaining -= n
        }
    }
    internal fun hasAsciiTag(data: ByteArray, at: Int, tag: String): Boolean {
        if (at + tag.length > data.size) return false
        for (i in tag.indices) {
            if (data[at + i] != tag[i].code.toByte()) return false
        }
        return true
    }
    private fun u16be(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun u32be(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
                ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}
