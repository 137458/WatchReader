package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderProgressPrecisionTest {

    @Test
    fun testTapPageAreaTopBottom() {
        val width = 466f
        val height = 466f

        // Top 35% (y < 163.1) -> PAGE_UP
        assertEquals(
            TapAction.PAGE_UP,
            TapPageHelper.resolveTapAction(233f, 100f, width, height, TapPageArea.TOP_BOTTOM)
        )
        // Bottom 35% (y > 302.9) -> PAGE_DOWN
        assertEquals(
            TapAction.PAGE_DOWN,
            TapPageHelper.resolveTapAction(233f, 350f, width, height, TapPageArea.TOP_BOTTOM)
        )
        // Middle 30% -> SHOW_MENU
        assertEquals(
            TapAction.SHOW_MENU,
            TapPageHelper.resolveTapAction(233f, 233f, width, height, TapPageArea.TOP_BOTTOM)
        )

        // Boundary testing
        assertEquals(
            TapAction.SHOW_MENU,
            TapPageHelper.resolveTapAction(233f, height * 0.35f, width, height, TapPageArea.TOP_BOTTOM)
        )
        assertEquals(
            TapAction.SHOW_MENU,
            TapPageHelper.resolveTapAction(233f, height * 0.65f, width, height, TapPageArea.TOP_BOTTOM)
        )
    }

    @Test
    fun testTapPageAreaLeftRight() {
        val width = 466f
        val height = 466f

        // Left 35% (x < 163.1) -> PAGE_UP
        assertEquals(
            TapAction.PAGE_UP,
            TapPageHelper.resolveTapAction(100f, 233f, width, height, TapPageArea.LEFT_RIGHT)
        )
        // Right 35% (x > 302.9) -> PAGE_DOWN
        assertEquals(
            TapAction.PAGE_DOWN,
            TapPageHelper.resolveTapAction(350f, 233f, width, height, TapPageArea.LEFT_RIGHT)
        )
        // Center -> SHOW_MENU
        assertEquals(
            TapAction.SHOW_MENU,
            TapPageHelper.resolveTapAction(233f, 233f, width, height, TapPageArea.LEFT_RIGHT)
        )
    }

    @Test
    fun testTapPageAreaDisabled() {
        val width = 466f
        val height = 466f

        // Any position should return SHOW_MENU
        assertEquals(
            TapAction.SHOW_MENU,
            TapPageHelper.resolveTapAction(100f, 100f, width, height, TapPageArea.DISABLED)
        )
        assertEquals(
            TapAction.SHOW_MENU,
            TapPageHelper.resolveTapAction(400f, 400f, width, height, TapPageArea.DISABLED)
        )
        assertEquals(
            TapAction.SHOW_MENU,
            TapPageHelper.resolveTapAction(233f, 233f, width, height, 2)
        )
    }

    @Test
    fun testScrollDistanceOverlap() {
        val screenHeight = 466f
        val density = 2.0f
        val distance = TapPageHelper.calculateScrollDistance(screenHeight, density)

        // 466 - 32 * 2 = 402
        assertEquals(402, distance)

        // Small screen clamping: minOf 100 * density
        val tinyScreenDistance = TapPageHelper.calculateScrollDistance(120f, density)
        assertEquals(200, tinyScreenDistance)
    }

    @Test
    fun testReadDurationFormatting() {
        // Zero or negative seconds
        assertEquals("⏱️ 累计阅读: 0小时 0分钟", ReadDurationFormatter.format(0L))
        assertEquals("⏱️ 累计阅读: 0小时 0分钟", ReadDurationFormatter.format(-10L))
        assertEquals("0小时 0分钟", ReadDurationFormatter.formatText(0L))

        // Seconds only
        assertEquals("⏱️ 累计阅读: 0小时 0分钟", ReadDurationFormatter.format(45L))
        assertEquals("0小时 0分钟", ReadDurationFormatter.formatText(45L))

        // Hours, minutes, and seconds
        assertEquals("⏱️ 累计阅读: 1小时 2分钟", ReadDurationFormatter.format(3725L))
        assertEquals("1小时 2分钟", ReadDurationFormatter.formatText(3725L))

        // Exact 2 hours
        assertEquals("⏱️ 累计阅读: 2小时 0分钟", ReadDurationFormatter.format(7200L))
        assertEquals("2小时 0分钟", ReadDurationFormatter.formatText(7200L))
    }

    @Test
    fun testTypographyCleaningIntegration() {
        val rawText = "第一章 初始\n\n\n   这是第一行内容   \n\n\n这是第二行内容。\n"
        val cleaned = TypographyCleaner.clean(rawText)

        val expected = "\u3000\u3000第一章 初始\n\n\u3000\u3000这是第一行内容\n\n\u3000\u3000这是第二行内容。"
        assertEquals(expected, cleaned)
    }
}
