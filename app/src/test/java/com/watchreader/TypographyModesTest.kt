package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行距 / 字距档位测试：默认档必须与历史渲染参数完全一致（升级不改已有观感），
 * 档位阶梯单调，非法值回退默认档。
 */
class TypographyModesTest {

    @Test
    fun testStandardMatchesLegacyRendering() {
        // 历史正文渲染：setLineSpacing(0f, 1.45f)，letterSpacing 未设置（0）
        assertEquals(1.45f, LineSpacingMode.STANDARD.multiplier, 0.001f)
        assertEquals(0f, LetterSpacingMode.STANDARD.em, 0.001f)
        assertEquals(1, LineSpacingMode.STANDARD.value)
        assertEquals(0, LetterSpacingMode.STANDARD.value)
    }

    @Test
    fun testFromValueFallsBackToStandard() {
        assertEquals(LineSpacingMode.STANDARD, LineSpacingMode.fromValue(99))
        assertEquals(LineSpacingMode.STANDARD, LineSpacingMode.fromValue(-1))
        assertEquals(LetterSpacingMode.STANDARD, LetterSpacingMode.fromValue(99))
        // 合法值原样解析
        assertEquals(LineSpacingMode.RELAXED, LineSpacingMode.fromValue(2))
        assertEquals(LetterSpacingMode.LOOSE, LetterSpacingMode.fromValue(2))
    }

    @Test
    fun testLadderMonotonicAndLabelsPresent() {
        val lineModes = LineSpacingMode.entries.sortedBy { it.value }
        assertTrue(lineModes.zipWithNext().all { (a, b) -> a.multiplier < b.multiplier })
        val letterModes = LetterSpacingMode.entries.sortedBy { it.value }
        assertTrue(letterModes.zipWithNext().all { (a, b) -> a.em < b.em })
        LineSpacingMode.entries.forEach { assertTrue(it.label.isNotEmpty()) }
        LetterSpacingMode.entries.forEach { assertTrue(it.label.isNotEmpty()) }
    }
}
