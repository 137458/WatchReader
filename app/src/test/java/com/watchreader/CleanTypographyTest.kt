package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「排版净化」行为与开关语义测试
 *
 * 净化规则原本由 formatChapterRawText 就地实现一遍、再由 TypographyCleaner 全量复跑一遍，
 * 两遍结果恒等（幂等），却让开关每次拨动都清空章节缓存、从文件头重解码正文并整章重排。
 * 现规则只归格式化器一处：开启 = 归一化并补标准缩进；关闭 = 原样保留段落结构。
 */
class CleanTypographyTest {

    private val chapters = listOf(
        Chapter(index = 0, title = "第一章 归雪", charOffset = 0),
        Chapter(index = 1, title = "第二章 长风", charOffset = 4096)
    )

    /** 标题行 + 单空行 + 无缩进段 + 连续空行 + 自带全角缩进段 + 无换行尾段 */
    private val raw = "第一章 归雪\n\n孤舟渡海。\n\n\n\n　　已有缩进的一段。\n尾行没有换行符"

    private fun content(raw: String, clean: Boolean) =
        formatChapterRawText(raw, chapters, 0, 0, raw.length, clean)

    private fun clean(raw: String) = content(raw, clean = true).formattedBody

    // ── 净化态：段落归一化规则 ──

    @Test
    fun cleanModeNormalizesBlankLinesAndIndentsEveryParagraph() {
        val expected = "\u3000\u3000孤舟渡海。\n\n" +
            "\u3000\u3000已有缩进的一段。\n\n" +
            "\u3000\u3000尾行没有换行符"
        assertEquals(expected, clean(raw))
    }

    @Test
    fun cleanModeCompressesConsecutiveBlankLines() {
        val expected = "\u3000\u3000第一段\n\n\u3000\u3000第二段\n\n\u3000\u3000第三段"
        assertEquals(expected, clean("第一段\n\n\n\n第二段\n\n\n第三段"))
    }

    @Test
    fun cleanModeStripsTrailingAndFullWidthWhitespace() {
        val expected = "\u3000\u3000第一段\n\n\u3000\u3000第二段"
        assertEquals(expected, clean("第一段   \t  \n\n第二段 　  "))
    }

    @Test
    fun cleanModeNormalizesMixedIndentWithoutDuplication() {
        val body = clean("    空格缩进段落\n　半角全角混合\n没有缩进的段落")
        val paragraphs = body.split("\n\n")
        assertEquals(3, paragraphs.size)
        for (paragraph in paragraphs) {
            assertTrue("每段必须且只有一个标准全角双空格缩进", paragraph.startsWith("\u3000\u3000"))
            assertTrue(
                "不得叠加出第三个缩进字符",
                !paragraph.startsWith("\u3000\u3000\u3000")
            )
        }
    }

    @Test
    fun cleanModeHandlesEmptyAndSingleLineInput() {
        assertEquals("", clean(""))
        assertEquals("", clean("\n\n\n   \n\t  \n"))
        assertEquals("\u3000\u3000单行文本", clean("单行文本"))
    }

    @Test
    fun cleanModeDropsLineDuplicatedWithChapterTitle() {
        assertEquals("\u3000\u3000正文起始。", clean("第一章 归雪\n正文起始。"))
    }

    // ── 原样态：开关关闭后尊重源文件排版 ──

    @Test
    fun rawModeKeepsSourceBlankLinesAndAddsNoIndent() {
        val expected = "孤舟渡海。\n\n\n\n　　已有缩进的一段。\n尾行没有换行符"
        assertEquals(expected, content(raw, clean = false).formattedBody)
    }

    @Test
    fun rawModeDegradesToIdentityOffsetMapping() {
        val unprefixed = content(raw, clean = false)
        assertTrue(
            "原样模式下正文坐标即原文坐标，不应记录段落映射",
            unprefixed.bodyParagraphStarts.isEmpty() && unprefixed.rawParagraphStarts.isEmpty()
        )
        assertEquals(
            12,
            ChapterOffsetMapper.bodyToRaw(
                12,
                unprefixed.bodyParagraphStarts,
                unprefixed.rawParagraphStarts,
                unprefixed.formattedBody.length,
                rawLength = raw.length
            )
        )
    }

    @Test
    fun cleanModeRecordsParagraphMappingForPositionRestore() {
        val mapped = content(raw, clean = true)
        assertEquals(3, mapped.bodyParagraphStarts.size)
        assertEquals(3, mapped.rawParagraphStarts.size)
        // 净化态正文坐标含缩进，必须换算回原文坐标域
        assertTrue(
            ChapterOffsetMapper.bodyToRaw(0, mapped.bodyParagraphStarts, mapped.rawParagraphStarts, mapped.formattedBody.length, rawLength = raw.length) > 0
        )
    }
}
