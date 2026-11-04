package com.watchreader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * BookSource 中不依赖 Android Context 的纯逻辑接缝：偏移域换算、切片边界、总长估算、
 * 缓存文件可用判据。
 *
 * 依赖 Context 的部分（格式分流、编码探测、章节索引装载、EPUB 条目读取）需要
 * Instrumentation 或 Robolectric 才能覆盖，本仓库约定为纯 JVM 单测，故不在此覆盖；
 * 这些路径靠 BookTextConverterTest / ChapterDiskCacheTest / EpubParserTest 的纯字节流
 * 用例间接覆盖。
 */
class BookSourceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ---- chapterEndOffsets ----

    @Test
    fun `章节终点按下一章起点推进且末章取全书总长`() {
        val chapters = listOf(
            Chapter(title = "第一章", charOffset = 0),
            Chapter(title = "第二章", charOffset = 100),
            Chapter(title = "第三章", charOffset = 250)
        )

        assertArrayEquals(intArrayOf(100, 250, 400), BookSource.chapterEndOffsets(chapters, 400))
    }

    @Test
    fun `章节起点倒序时终点保持单调不减`() {
        val chapters = listOf(
            Chapter(title = "第一章", charOffset = 0),
            Chapter(title = "第二章", charOffset = 120),
            Chapter(title = "第三章", charOffset = 80)
        )

        // 第三章起点回退到 80：它的终点仍须 >= 上一章终点 120，否则切片装配器会把
        // 倒序边界当成「本章已经结束」，静默产出空章
        assertArrayEquals(intArrayOf(120, 120, 200), BookSource.chapterEndOffsets(chapters, 200))
    }

    @Test
    fun `空章节表没有终点`() {
        assertArrayEquals(IntArray(0), BookSource.chapterEndOffsets(emptyList(), 500))
    }

    // ---- chapterBounds ----

    @Test
    fun `章节边界为本章起点到下一章起点`() {
        val chapters = listOf(
            Chapter(title = "第一章", charOffset = 0),
            Chapter(title = "第二章", charOffset = 100),
            Chapter(title = "第三章", charOffset = 250)
        )

        assertEquals(CharBounds(0, 100), BookSource.chapterBounds(chapters, 0, 400))
        assertEquals(CharBounds(100, 250), BookSource.chapterBounds(chapters, 1, 400))
        assertEquals(CharBounds(250, 400), BookSource.chapterBounds(chapters, 2, 400))
    }

    @Test
    fun `章节起点超出全书总长时夹成空区间`() {
        val chapters = listOf(
            Chapter(title = "第一章", charOffset = 0),
            Chapter(title = "第二章", charOffset = 100),
            Chapter(title = "第三章", charOffset = 250)
        )

        // 磁盘缓存的总长比章节索引短（旧缓存的估算值偏小）：末章夹成空区间，
        // readChapter 会返回合法的空正文，而不是把越界偏移交给流去 skip
        assertEquals(CharBounds(100, 200), BookSource.chapterBounds(chapters, 1, 200))
        assertEquals(CharBounds(200, 200), BookSource.chapterBounds(chapters, 2, 200))
    }

    @Test
    fun `章节索引越界返回 null`() {
        val chapters = listOf(Chapter(title = "第一章", charOffset = 0))

        assertNull(BookSource.chapterBounds(chapters, -1, 100))
        assertNull(BookSource.chapterBounds(chapters, 1, 100))
        assertNull(BookSource.chapterBounds(emptyList(), 0, 100))
    }

    @Test
    fun `负起点夹到零且长度非负`() {
        val chapters = listOf(Chapter(title = "第一章", charOffset = -50))

        val bounds = BookSource.chapterBounds(chapters, 0, 100)

        assertEquals(CharBounds(0, 100), bounds)
        assertEquals(100, bounds!!.length)
    }

    // ---- estimateTotalChars ----

    @Test
    fun `UTF-16 按两字节折算字符数`() {
        val chapters = listOf(Chapter(title = "第一章", charOffset = 0))

        assertEquals(4096, BookSource.estimateTotalChars(chapters, 8192, "UTF-16LE"))
    }

    @Test
    fun `末章起点大于估算值时以末章起点加余量为准`() {
        val chapters = listOf(
            Chapter(title = "第一章", charOffset = 0),
            Chapter(title = "第二章", charOffset = 9000)
        )

        assertEquals(12000, BookSource.estimateTotalChars(chapters, 1000, "UTF-8"))
    }

    @Test
    fun `UTF-16 同时受字节折算与末章兜底影响`() {
        val chapters = listOf(Chapter(title = "第一章", charOffset = 5000))

        // 1000 字节按 UTF-16 只折算 500 字，远低于末章起点 5000，兜底取 5000 + 3000
        assertEquals(8000, BookSource.estimateTotalChars(chapters, 1000, "UTF-16BE"))
    }

    @Test
    fun `无章节时只按字节数估算且负数视为零`() {
        assertEquals(500, BookSource.estimateTotalChars(emptyList(), 500, "UTF-8"))
        assertEquals(0, BookSource.estimateTotalChars(emptyList(), -1, "UTF-8"))
    }

    // ---- isUsableCacheFile ----

    @Test
    fun `转换缓存缺失或为空视为不可用`() {
        val missing = File(tempFolder.root, "missing.txt")
        val empty = tempFolder.newFile("empty.txt")

        assertFalse(isUsableCacheFile(missing))
        assertFalse(isUsableCacheFile(empty))
    }

    @Test
    fun `转换缓存有内容时可用`() {
        val file = File(tempFolder.root, "cache.txt")
        file.writeText("第一章 正文")

        assertTrue(isUsableCacheFile(file))
    }

    // ---- emptyChapterContent ----

    @Test
    fun `越界占位内容为结构合法的空章`() {
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
