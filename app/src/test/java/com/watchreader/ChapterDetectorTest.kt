package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class ChapterDetectorTest {

    @Test
    fun testDetectChaptersStandard() {
        val sampleText = """
            第1章 初入江湖
            这是第一章的正文内容。风声潇潇，剑影重重。
            第2章 绝处逢生
            这是第二章的正文内容。悬崖峭壁，云海翻腾。
            第3章 大道争锋
            这是第三章的正文内容。天地不仁，以万物为刍狗。
        """.trimIndent()

        val chapters = detectChapters(sampleText)
        assertEquals(3, chapters.size)
        assertEquals("第1章 初入江湖", chapters[0].title)
        assertEquals("第2章 绝处逢生", chapters[1].title)
        assertEquals("第3章 大道争锋", chapters[2].title)
    }

    @Test
    fun testDetectChaptersStream() {
        val sampleText = """
            引子
            千年之前，神魔大战。
            第一卷 潜龙在渊
            第一章 少年远行
            路漫漫其修远兮。
            第二章 荒野伏击
            刀光剑影，生死一线。
            尾声
            岁月如梭，青丝白发。
        """.trimIndent()

        val bytes = sampleText.toByteArray(Charsets.UTF_8)
        val (chapters, totalChars) = detectChaptersFromInputStream(ByteArrayInputStream(bytes), "UTF-8")

        assertTrue(chapters.isNotEmpty())
        assertEquals(5, chapters.size)
        assertEquals("引子", chapters[0].title)
        assertEquals("第一卷 潜龙在渊", chapters[1].title)
        assertEquals("第一章 少年远行", chapters[2].title)
        assertEquals("第二章 荒野伏击", chapters[3].title)
        assertEquals("尾声", chapters[4].title)
        assertEquals(sampleText.length, totalChars)
    }

    @Test
    fun testDetectChaptersVirtualFallback() {
        val noChapterText = "一段长文本，没有任何规范的章节标题。".repeat(200)
        val (chapters, totalChars) = detectChaptersFromInputStream(ByteArrayInputStream(noChapterText.toByteArray(Charsets.UTF_8)), "UTF-8")

        assertTrue(chapters.isNotEmpty())
        assertTrue(chapters[0].title.startsWith("第 1 节"))
        assertEquals(noChapterText.length, totalChars)
    }

    @Test
    fun testBinarySearchFindChapterIndex() {
        val chapters = listOf(
            Chapter(index = 0, title = "第1章", charOffset = 0),
            Chapter(index = 1, title = "第2章", charOffset = 1000),
            Chapter(index = 2, title = "第3章", charOffset = 2500),
            Chapter(index = 3, title = "第4章", charOffset = 4000)
        )

        assertEquals(0, findCurrentChapterIndex(chapters, 0))
        assertEquals(0, findCurrentChapterIndex(chapters, 500))
        assertEquals(1, findCurrentChapterIndex(chapters, 1000))
        assertEquals(1, findCurrentChapterIndex(chapters, 1500))
        assertEquals(2, findCurrentChapterIndex(chapters, 2500))
        assertEquals(3, findCurrentChapterIndex(chapters, 4000))
        assertEquals(3, findCurrentChapterIndex(chapters, 9999))
    }
}
