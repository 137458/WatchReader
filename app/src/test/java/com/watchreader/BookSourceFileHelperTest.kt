package com.watchreader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * 从 BookSource / FileHelper 抽出的纯实现：编码探测、按字符偏移取切片、章节索引几何。
 *
 * 这些分支此前必须先拿到 ContentResolver 才能触达；抽成 object 后可以逐条喂字节流，
 * 把两条隐藏约定钉死：BOM 不占字符偏移；章节终点单调不减且不越过全书总长。
 */
class BookSourceFileHelperTest {

    // ---- TextStreams.detect ----

    @Test
    fun `UTF-8 BOM 识别为 UTF-8`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                "中文".toByteArray(Charsets.UTF_8)
        assertEquals("UTF-8", TextStreams.detect(bytes, bytes.size))
    }

    @Test
    fun `UTF-16 双字节 BOM 优先于内容判定`() {
        assertEquals("UTF-16LE", TextStreams.detect(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41, 0x00), 4))
        assertEquals("UTF-16BE", TextStreams.detect(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x00, 0x41), 4))
    }

    @Test
    fun `短 ASCII 样本按 UTF-8 处理`() {
        val bytes = "abc".toByteArray(Charsets.US_ASCII)
        assertEquals("UTF-8", TextStreams.detect(bytes, bytes.size))
    }

    @Test
    fun `长 ASCII 样本无多字节字符时回退 GB18030`() {
        val bytes = ByteArray(200) { 0x61 }
        assertEquals("GB18030", TextStreams.detect(bytes, bytes.size))
    }

    @Test
    fun `合法多字节序列判定为 UTF-8`() {
        val bytes = "汉字".toByteArray(Charsets.UTF_8)
        assertEquals("UTF-8", TextStreams.detect(bytes, bytes.size))
    }

    @Test
    fun `非法首字节回退 GB18030`() {
        val bytes = byteArrayOf(0x61, 0xFF.toByte(), 0x62)
        assertEquals("GB18030", TextStreams.detect(bytes, bytes.size))
    }

    @Test
    fun `尾部截断的多字节序列不判为非法`() {
        val bytes = byteArrayOf(0x61, 0xE4.toByte())
        assertEquals("UTF-8", TextStreams.detect(bytes, bytes.size))
    }

    @Test
    fun `length 限定只看前缀`() {
        val bytes = "中文".toByteArray(Charsets.UTF_8) + ByteArray(4) { 0xFF.toByte() }
        assertEquals("UTF-8", TextStreams.detect(bytes, 6))
        assertEquals("GB18030", TextStreams.detect(bytes, bytes.size))
    }

    @Test
    fun `单参数重载等价于整数组长度`() {
        val bytes = "章节".toByteArray(Charsets.UTF_8)
        assertEquals(TextStreams.detect(bytes, bytes.size), detectEncoding(bytes))
    }

    // ---- TextStreams.readChunk ----

    @Test
    fun `BOM 不计入字符偏移`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                "中文abc".toByteArray(Charsets.UTF_8)
        assertEquals("中文", TextStreams.readChunk(ByteArrayInputStream(bytes), "UTF-8", 0, 2))
    }

    @Test
    fun `无 BOM 时按字符偏移切片`() {
        val bytes = "abcdef".toByteArray(Charsets.UTF_8)
        assertEquals("cde", TextStreams.readChunk(ByteArrayInputStream(bytes), "UTF-8", 2, 3))
    }

    @Test
    fun `偏移越过文件末尾返回 null`() {
        val bytes = "abc".toByteArray(Charsets.UTF_8)
        assertNull(TextStreams.readChunk(ByteArrayInputStream(bytes), "UTF-8", 10, 2))
    }

    @Test
    fun `零长度切片返回空串`() {
        val bytes = "abc".toByteArray(Charsets.UTF_8)
        assertEquals("", TextStreams.readChunk(ByteArrayInputStream(bytes), "UTF-8", 1, 0))
    }

    @Test
    fun `UTF-16LE 按字符偏移切片`() {
        val bytes = "汉字".toByteArray(Charsets.UTF_16LE)
        assertEquals("字", TextStreams.readChunk(ByteArrayInputStream(bytes), "UTF-16LE", 1, 1))
    }

    // ---- BookSourceIndex ----

    @Test
    fun `章节终点单调不减且末章取总长`() {
        val chapters = listOf(
            Chapter(title = "第一章", charOffset = 0),
            Chapter(title = "第二章", charOffset = 120),
            Chapter(title = "第三章", charOffset = 80)
        )
        assertArrayEquals(intArrayOf(120, 120, 200), BookSourceIndex.chapterEndOffsets(chapters, 200))
    }

    @Test
    fun `切片边界夹进总长且越界返回 null`() {
        val chapters = listOf(
            Chapter(title = "甲", charOffset = 0),
            Chapter(title = "乙", charOffset = 250)
        )
        assertEquals(CharBounds(0, 120), BookSourceIndex.chapterBounds(chapters, 0, 120))
        assertEquals(CharBounds(120, 120), BookSourceIndex.chapterBounds(chapters, 1, 120))
        assertNull(BookSourceIndex.chapterBounds(chapters, 2, 120))
    }

    @Test
    fun `UTF-16 文件总长按每字符两字节估算`() {
        assertEquals(4096, BookSourceIndex.estimateTotalChars(emptyList(), 8192, "UTF-16LE"))
        assertEquals(8192, BookSourceIndex.estimateTotalChars(emptyList(), 8192, "UTF-8"))
    }

    @Test
    fun `估算值不得截断末章起点`() {
        val chapters = listOf(Chapter(title = "末章", charOffset = 9000))
        assertEquals(12000, BookSourceIndex.estimateTotalChars(chapters, 1000, "UTF-8"))
    }

    @Test
    fun `空章占位是结构合法的 ChapterContent`() {
        val empty = emptyChapterContent()
        assertEquals(0, empty.chapterIndex)
        assertEquals("", empty.title)
        assertEquals("", empty.formattedBody)
        assertEquals(0, empty.startCharOffset)
        assertEquals(0, empty.endCharOffset)
        assertFalse(empty.hasPrevChapter)
        assertFalse(empty.hasNextChapter)
    }
}
