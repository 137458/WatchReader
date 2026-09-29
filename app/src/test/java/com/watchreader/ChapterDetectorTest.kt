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

        val (chapters, totalChars) = detectChaptersFromInputStream(
            ByteArrayInputStream(sampleText.toByteArray(Charsets.UTF_8)),
            "UTF-8"
        )
        assertEquals(3, chapters.size)
        assertEquals("第1章 初入江湖", chapters[0].title)
        assertEquals("第2章 绝处逢生", chapters[1].title)
        assertEquals("第3章 大道争锋", chapters[2].title)
        assertEquals(sampleText.length, totalChars)
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

    // ── 超长章节封顶分节 ──

    private fun streamChapters(text: String, maxChapterChars: Int): Pair<List<Chapter>, Int> =
        detectChaptersFromInputStream(
            ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)),
            "UTF-8",
            maxChapterChars
        )

    @Test
    fun testOversizedChapterSplitsIntoLabelledParts() {
        val text = "第一章 长山\n" + (1..40).joinToString("\n") { "山风掠过城头第${it}遍。" }
        val (chapters, _) = streamChapters(text, maxChapterChars = 60)

        val parts = chapters.filter { it.title.startsWith("第一章 长山") }
        assertTrue("超长章节必须被切分，实际只得到 ${chapters.size} 项", parts.size > 1)
        assertEquals("第一章 长山 · 1/${parts.size}", parts.first().title)
        assertEquals("第一章 长山 · ${parts.size}/${parts.size}", parts.last().title)
    }

    @Test
    fun testSplitBreaksLandOnParagraphStartsAndLeaveNoGap() {
        val text = "第一章 长山\n" + (1..40).joinToString("\n") { "山风掠过城头第${it}遍。" }
        val (chapters, totalChars) = streamChapters(text, maxChapterChars = 60)
        val parts = chapters.filter { it.title.startsWith("第一章 长山") }
        assertTrue("未切分时本用例为空断言", parts.size > 1)

        for (i in 1 until parts.size) {
            val breakAt = parts[i].charOffset
            assertEquals("切点必须落在自然段首字符", '\n', text[breakAt - 1])
        }
        assertEquals("切分不得吞掉或重复任何字符", parts.first().charOffset, 0)
        assertEquals(totalChars, text.length)
    }

    @Test
    fun testParagraphSnappedSpansBoundedByCapPlusOneParagraph() {
        val text = "第一章 长山\n" + (1..200).joinToString("\n") { "山风掠过城头第${it}遍。" }
        val (chapters, totalChars) = streamChapters(text, maxChapterChars = 60)
        val max = 60

        chapters.forEachIndexed { i, chapter ->
            val end = chapters.getOrNull(i + 1)?.charOffset ?: totalChars
            assertTrue(
                "分节 ${chapter.title} 跨度 ${end - chapter.charOffset} 超过 封顶+一个自然段（切点吸附段首的固有上界）",
                end - chapter.charOffset <= max + 12
            )
        }
    }

    @Test
    fun testSingleLongLineIsCutAtCap() {
        // 介于封顶与 2×封顶之间的无换行单行必须在封顶处强切，
        // 而不是留到行尾才由换行分支切出近 2×封顶的分节
        val longLine = "无换行长句。".repeat(19) // 114 字，介于 60 与 120 之间
        val text = "第一章 长山\n" + longLine + "\n" + "收尾一行。"
        val (chapters, totalChars) = streamChapters(text, maxChapterChars = 60)

        // 长行首字符在偏移 7，末字符在偏移 120：切点必须落在长行内部
        val cutInsideLongLine = chapters.any { it.charOffset in 8..120 }
        assertTrue("超长单行必须在封顶处被强切，实得切点 ${chapters.map { it.charOffset }}", cutInsideLongLine)
        assertEquals(totalChars, text.length)
    }

    @Test
    fun testSingleLineChapterStillSplits() {
        // 整本一行（无任何换行）的退化文本也必须封顶，否则切分形同虚设
        val text = "第一章 长山\n" + "无换行长句。".repeat(200)
        val (chapters, _) = streamChapters(text, maxChapterChars = 60)
        val parts = chapters.filter { it.title.startsWith("第一章 长山") }

        assertTrue("无段落边界时仍须按步长切分，实得 ${parts.size} 节", parts.size > 2)
    }

    @Test
    fun testNormalLengthChaptersAreNotSplit() {
        val text = "第1章 短\n山风吹来。\n第2章 也短\n江水东流。\n"
        val (chapters, _) = streamChapters(text, maxChapterChars = 60)

        assertEquals(listOf("第1章 短", "第2章 也短"), chapters.map { it.title })
    }
}
