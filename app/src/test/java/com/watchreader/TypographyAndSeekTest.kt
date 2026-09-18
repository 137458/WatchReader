package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcSeekMathTest {

    @Test
    fun testIsInSeekZone() {
        val cx = 233f
        val cy = 233f

        // Exact 3 o'clock (angle = 0 deg, r = 220 in [210, 233])
        assertTrue(ArcSeekMath.isInSeekZone(cx + 220f, cy, cx, cy))

        // 45 degrees up (angle = -45 deg, r = 225)
        val r45 = 225f
        val xUp = cx + (r45 * kotlin.math.cos(Math.toRadians(-45.0))).toFloat()
        val yUp = cy + (r45 * kotlin.math.sin(Math.toRadians(-45.0))).toFloat()
        assertTrue(ArcSeekMath.isInSeekZone(xUp, yUp, cx, cy))

        // Center of screen (r = 0): outside
        assertFalse(ArcSeekMath.isInSeekZone(cx, cy, cx, cy))

        // Left side (angle = 180 deg): outside
        assertFalse(ArcSeekMath.isInSeekZone(cx - 220f, cy, cx, cy))

        // Top edge outside angle range (angle = -90 deg): outside
        assertFalse(ArcSeekMath.isInSeekZone(cx, cy - 220f, cx, cy))
    }

    @Test
    fun testAngleToChapterIndex() {
        val totalChapters = 100

        // -60 degrees -> chapter 0
        assertEquals(0, ArcSeekMath.angleToChapterIndex(-60f, totalChapters))
        assertEquals(0, ArcSeekMath.angleToChapterIndex(-70f, totalChapters)) // clamped

        // +60 degrees -> chapter 99
        assertEquals(99, ArcSeekMath.angleToChapterIndex(60f, totalChapters))
        assertEquals(99, ArcSeekMath.angleToChapterIndex(80f, totalChapters)) // clamped

        // 0 degrees -> middle chapter ~ 50 (index 49 or 50)
        val mid = ArcSeekMath.angleToChapterIndex(0f, totalChapters)
        assertTrue("Mid chapter index should be around 49-50, got $mid", mid in 49..50)
    }

    @Test
    fun testFormatSeekLabel() {
        val label = ArcSeekMath.formatSeekLabel(
            chapterIndex = 4,
            totalChapters = 10,
            chapterTitle = "风起云涌"
        )
        // Expected format: "第 X/Y 章 · Z% (章节名)"
        assertEquals("第 5/10 章 · 50% (风起云涌)", label)
    }
}
