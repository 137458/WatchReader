package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * RSVP 上报偏移必须落在「全书绝对原文偏移」域
 *
 * 分词器给出的 charOffset 是正文域索引（formattedBody 索引），原文域与正文域之间
 * 差着每段补入的缩进与段间换行；直接加章节起点会让退出闪读后的阅读位置前移。
 */
class RsvpOffsetDomainTest {

    // 章节原文：两段（段首 raw 偏移 0 / 12）；正文排版后每段前补 2 个缩进
    private val rawParas = intArrayOf(0, 12)
    private val bodyParas = intArrayOf(0, 21)
    private val chapterStart = 1000

    private fun bodyToRaw(bodyIndex: Int): Int = ChapterOffsetMapper.bodyToRaw(
        bodyIndex = bodyIndex,
        bodyParaStarts = bodyParas,
        rawParaStarts = rawParas,
        bodyLength = 40,
        rawLength = 20,
        indentChars = 2
    )

    @Test
    fun testBodyIndexIsNotBookOffset() {
        // 旧实现 startCharOffset + charOffset 会把位置推后（1021 vs 正确 1012）
        assertEquals(1012, chapterStart + bodyToRaw(21))
        assertNotEquals(chapterStart + bodyToRaw(21), chapterStart + 21)
    }

    @Test
    fun testParagraphIndentMapsBackToParagraphStart() {
        // 段首补入的缩进必须映射回段首原文位置，不能让位置从段中间起步
        assertEquals(1000, chapterStart + bodyToRaw(0))
        assertEquals(1000, chapterStart + bodyToRaw(1))
        assertEquals(1000, chapterStart + bodyToRaw(2))
        assertEquals(1001, chapterStart + bodyToRaw(3))
    }
}
