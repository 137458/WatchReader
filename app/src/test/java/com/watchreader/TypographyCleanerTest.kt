package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypographyCleanerTest {

    @Test
    fun testCompressMultipleNewlinesToDoubleNewline() {
        val input = "第一段\n\n\n\n第二段\n\n\n第三段"
        val expected = "\u3000\u3000第一段\n\n\u3000\u3000第二段\n\n\u3000\u3000第三段"
        assertEquals(expected, TypographyCleaner.clean(input))
    }

    @Test
    fun testStripTrailingWhitespace() {
        val input = "第一段   \t  \n\n第二段 \u3000  "
        val expected = "\u3000\u3000第一段\n\n\u3000\u3000第二段"
        assertEquals(expected, TypographyCleaner.clean(input))
    }

    @Test
    fun testIndentNaturalParagraphsWithoutDuplication() {
        // 无缩进自动补齐
        val unindented = "这是没有缩进的第一段。\n\n这是没有缩进的第二段。"
        val expected1 = "\u3000\u3000这是没有缩进的第一段。\n\n\u3000\u3000这是没有缩进的第二段。"
        assertEquals(expected1, TypographyCleaner.clean(unindented))

        // 已有全角缩进不重复补齐
        val indented = "\u3000\u3000已有全角缩进。\n\n\u3000\u3000第二段也已有缩进。"
        assertEquals(indented, TypographyCleaner.clean(indented))

        // 混合缩进与各种缩进格式归一化
        val inputMixed = "    空格缩进段落\n\u3000半角全角混合\n没有缩进的段落"
        val cleanedMixed = TypographyCleaner.clean(inputMixed)
        val lines = cleanedMixed.split("\n\n")
        assertEquals(3, lines.size)
        for (line in lines) {
            assertTrue("Every paragraph must start with standard double full-width space", line.startsWith("\u3000\u3000"))
        }
        assertEquals("\u3000\u3000空格缩进段落", lines[0])
        assertEquals("\u3000\u3000半角全角混合", lines[1])
        assertEquals("\u3000\u3000没有缩进的段落", lines[2])
    }

    @Test
    fun testBoundaryCases() {
        // 空文本
        assertEquals("", TypographyCleaner.clean(""))

        // 纯空行和空白字符
        assertEquals("", TypographyCleaner.clean("\n\n\n   \n\t  \n"))

        // 单行文本
        assertEquals("\u3000\u3000单行文本", TypographyCleaner.clean("单行文本"))

        // 已规整文本
        val regular = "\u3000\u3000第一段。\n\n\u3000\u3000第二段。"
        assertEquals(regular, TypographyCleaner.clean(regular))
    }
}
