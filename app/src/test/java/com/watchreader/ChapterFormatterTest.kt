package com.watchreader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ChapterFormatter 直接单测：正文排版、全角缩进、段落坐标映射、标题头剥除与空章。
 *
 * 坐标断言的意义：当前阅读位置持久化在原文坐标域，正文↔原文的段落映射一旦错位，
 * 翻页/存档位置就会漂移到别的段落甚至别的章节。
 */
class ChapterFormatterTest {

    private val title = "第一章 测试"
    private val chapters = listOf(Chapter(index = 0, title = title, charOffset = 0))

    private fun format(raw: String, clean: Boolean = true): ChapterContent =
        formatChapterRawText(raw, chapters, 0, 0, raw.length, clean)

    @Test
    fun testCleanTypographyAddsFullWidthIndentAndParagraphMap() {
        val raw = "第一章 测试\n\n这是第一段。\n这是第二段。\n"
        assertEquals(22, raw.length)

        val content = format(raw)

        // 标题行已被独立标题控件呈现：正文里不再重复；每段补两个全角空格，段间空一行
        assertEquals("\u3000\u3000这是第一段。\n\n\u3000\u3000这是第二段。", content.formattedBody)
        assertArrayEquals(intArrayOf(0, 10), content.bodyParagraphStarts)
        assertArrayEquals(intArrayOf(8, 15), content.rawParagraphStarts)
        // 净化态每段补 2 个缩进字符，坐标换算要扣除
        assertEquals(2, content.paragraphIndentChars)
    }

    @Test
    fun testCrlfInputProducesSameBodyAndRawOffsets() {
        val raw = "第一章 测试\r\n\r\n这是第一段。\r\n这是第二段。\r\n"

        val content = format(raw)

        // CRLF 的 \r 属于行内尾随空白：不进正文，也不影响段落起点
        assertEquals("\u3000\u3000这是第一段。\n\n\u3000\u3000这是第二段。", content.formattedBody)
        assertArrayEquals(intArrayOf(0, 10), content.bodyParagraphStarts)
        assertArrayEquals(intArrayOf(10, 18), content.rawParagraphStarts)
    }

    @Test
    fun testMultibyteAndSurrogatePairsPreserved() {
        val raw = "第一章 测试\n\n𠮷野家 😀\n"

        val content = format(raw)

        assertEquals("\u3000\u3000𠮷野家 😀", content.formattedBody)
        // 代理对完整保留：𠮷 与 😀 各占 2 个 UTF-16 单元，缩进 2 + 正文 7 = 9
        assertEquals(9, content.formattedBody.length)
        assertArrayEquals(intArrayOf(0), content.bodyParagraphStarts)
        assertArrayEquals(intArrayOf(8), content.rawParagraphStarts)
    }

    @Test
    fun testExistingFullWidthIndentNormalizedToTwo() {
        val raw = "第一章 测试\n\u3000\u3000这是第一段。\n"

        val content = format(raw)

        // 源文件自带的全角缩进被归一为固定 2 个，不叠加
        assertEquals("\u3000\u3000这是第一段。", content.formattedBody)
        // rawParagraphStarts 指向首个非空白字符（跳过源文件自带的两个全角空格）
        assertArrayEquals(intArrayOf(9), content.rawParagraphStarts)
    }

    @Test
    fun testNonTitleFirstLineKeptAsParagraph() {
        val raw = "序章\n正文。\n"

        val content = format(raw)

        assertEquals("\u3000\u3000序章\n\n\u3000\u3000正文。", content.formattedBody)
        // 首段 "　　序章" 占 4 个字符，段间空行再占 2 个 → 第二段正文起点 6
        assertArrayEquals(intArrayOf(0, 6), content.bodyParagraphStarts)
        assertArrayEquals(intArrayOf(0, 3), content.rawParagraphStarts)
    }

    @Test
    fun testRawModeStripsTitleHeaderAndMapsHeaderOffset() {
        val raw = "第一章 测试\n\n正文第一段。\n"
        assertEquals(15, raw.length)

        val content = format(raw, clean = false)

        // 原样态只剥标题头与其后空行，其余逐字保留（含行尾换行）
        assertEquals("正文第一段。\n", content.formattedBody)
        // 剥头让正文坐标整体前移 8，靠单点映射还原到原文坐标
        assertArrayEquals(intArrayOf(0), content.bodyParagraphStarts)
        assertArrayEquals(intArrayOf(8), content.rawParagraphStarts)
        assertEquals(0, content.paragraphIndentChars)
        assertEquals(0, content.startCharOffset)
        assertEquals(15, content.endCharOffset)
    }

    @Test
    fun testRawModeKeepsHeaderWhenTitleDiffers() {
        val raw = "序章\n正文。\n"

        val content = format(raw, clean = false)

        assertEquals(raw, content.formattedBody)
        assertEquals(0, content.bodyParagraphStarts.size)
        assertEquals(0, content.rawParagraphStarts.size)
        assertEquals(0, content.paragraphIndentChars)
    }

    @Test
    fun testEmptyChapterYieldsEmptyBody() {
        val empty = format("")
        assertEquals("", empty.formattedBody)
        assertEquals(0, empty.bodyParagraphStarts.size)

        val blank = format("\n\n")
        assertEquals("", blank.formattedBody)
        assertEquals(0, blank.bodyParagraphStarts.size)
    }
}
