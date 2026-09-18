package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderProgressPrecisionTest {

    @Test
    fun testTapPageAreaBoundaries() {
        val screenHeight = 466f
        val topBoundary = screenHeight * 0.35f      // 163.1f
        val bottomBoundary = screenHeight * 0.65f   // 302.9f

        // Top tap: Y = 100f < 163.1f -> Page Up
        assertTrue(100f < topBoundary)

        // Bottom tap: Y = 350f > 302.9f -> Page Down
        assertTrue(350f > bottomBoundary)

        // Middle tap: Y = 233f in [163.1, 302.9] -> Menu
        val midY = 233f
        assertTrue(midY in topBoundary..bottomBoundary)
    }

    @Test
    fun testScrollDistanceOverlap() {
        val screenHeight = 466f
        val density = 2.0f
        val overlap = (32 * density).toInt() // 64px overlap
        val scrollDistance = maxOf((100 * density).toInt(), (screenHeight - overlap).toInt())

        // 466 - 64 = 402px
        assertEquals(402, scrollDistance)
        // Ensure scrollDistance leaves exactly 64px overlap for visual anchoring
        assertEquals(screenHeight.toInt() - 64, scrollDistance)
    }

    @Test
    fun testReadDurationFormatting() {
        val durationSec1 = 45L // 45 seconds
        val hours1 = durationSec1 / 3600
        val mins1 = (durationSec1 % 3600) / 60
        assertEquals("0小时 0分钟", "${hours1}小时 ${mins1}分钟")

        val durationSec2 = 3725L // 1 hour, 2 minutes, 5 seconds
        val hours2 = durationSec2 / 3600
        val mins2 = (durationSec2 % 3600) / 60
        assertEquals("1小时 2分钟", "${hours2}小时 ${mins2}分钟")
    }

    @Test
    fun testTypographyCleaningIntegration() {
        val rawText = "第一章 初始\n\n\n   这是第一行内容   \n\n\n这是第二行内容。\n"
        val cleaned = TypographyCleaner.clean(rawText)

        // Cleaned should have double full-width space indent for paragraphs and double newlines between paragraphs
        val expected = "\u3000\u3000第一章 初始\n\n\u3000\u3000这是第一行内容\n\n\u3000\u3000这是第二行内容。"
        assertEquals(expected, cleaned)
    }
}
