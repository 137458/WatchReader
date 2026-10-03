package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 书架备份编解码测试：全字段回路一致、外来 JSON 拒绝、缺字段容错
 */
class BackupCodecTest {

    private fun samplePayload() = BackupPayload(
        shelf = listOf(
            BookItem("content://a", "斗转星移.txt", 9500, 10000, "终章", 1696400000000L, isPinned = true, finished = true),
            BookItem("content://b", "三体.epub", 100, 50000, "第一章", 1696300000000L, isPinned = false, finished = false)
        ),
        bookmarks = mapOf(
            "content://b" to listOf(
                // 已按时间倒序排列（书签持久化即按时间倒序，解码后保持该序）
                Bookmark(id = "bm2", chapterIndex = 0, chapterTitle = "第一章", charOffset = 10, snippet = "", time = 222L),
                Bookmark(id = "bm1", chapterIndex = 3, chapterTitle = "第四章", charOffset = 1234, snippet = "片段摘录", time = 111L)
            )
        ),
        totalReadSec = 3661L,
        readDays = mapOf("2026-10-03" to 5400L, "2026-10-04" to 660L),
        fontSize = 16,
        themeMode = 2,
        autoScrollSpeed = 60f,
        appBrightness = 0.35f,
        tapPageArea = 1,
        cleanTypography = false,
        fontType = 1,
        lineSpacing = 2,
        letterSpacing = 1,
        readGoalMinutes = 30
    )

    @Test
    fun testRoundtripPreservesAllFields() {
        val payload = samplePayload()
        val json = BackupCodec.encode(payload, exportedAtMs = 1696400009999L)
        val decoded = BackupCodec.decode(json)
        assertNotNull(decoded)
        assertEquals(payload, decoded)
    }

    @Test
    fun testDecodeRejectsForeignJson() {
        assertNull(BackupCodec.decode("not json at all"))
        assertNull(BackupCodec.decode("{}"))
        assertNull(BackupCodec.decode("""{"type":"other_app","version":1}"""))
        assertNull(BackupCodec.decode("""{"type":"watchreader_backup","version":99}"""))
        assertNull(BackupCodec.decode(""))
    }

    @Test
    fun testDecodeToleratesMissingFields() {
        val decoded = BackupCodec.decode("""{"type":"watchreader_backup","version":1}""")
        assertNotNull(decoded)
        assertEquals(emptyList<BookItem>(), decoded!!.shelf)
        assertEquals(emptyMap<String, List<Bookmark>>(), decoded.bookmarks)
        assertEquals(0L, decoded.totalReadSec)
        assertEquals(emptyMap<String, Long>(), decoded.readDays)
        // 设置项回默认值
        assertEquals(14, decoded.fontSize)
        assertEquals(0, decoded.themeMode)
        assertEquals(45f, decoded.autoScrollSpeed, 0.001f)
        assertEquals(-1f, decoded.appBrightness, 0.001f)
        assertEquals(0, decoded.tapPageArea)
        assertEquals(true, decoded.cleanTypography)
        assertEquals(0, decoded.fontType)
        assertEquals(1, decoded.lineSpacing)
        assertEquals(0, decoded.letterSpacing)
        assertEquals(0, decoded.readGoalMinutes)
    }
}
