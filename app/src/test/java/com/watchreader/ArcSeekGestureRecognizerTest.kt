package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弧形寻道手势识别器测试
 *
 * 背景约束：寻道交互原先由全屏 Compose pointerInput 承接，在 Composer/AndroidView 互操作下
 * 会整条吞掉阅读页触摸流（点击、长按、滑动全部失效）。现改为由阅读页原生触摸管线驱动，
 * 因此识别逻辑必须是可独立测试的纯状态机。
 *
 * 几何：466x466 圆屏，圆心 (233,233)；寻道带为右半弧 r∈[200,245]、角度 ∈[-60°,70°] 内。
 */
class ArcSeekGestureRecognizerTest {

    private val w = 466f
    private val h = 466f
    private val totalChapters = 100

    /** 带内按下点：右侧 0° 方向，半径 220 */
    private val inZoneX = 233f + 220f
    private val inZoneY = 233f

    @Test
    fun testDownInsideSeekZoneStartsTracking() {
        val rec = ArcSeekGestureRecognizer()
        assertTrue(rec.onDown(inZoneX, inZoneY, w, h, 10))
        assertTrue(rec.isTracking)
        assertFalse(rec.isSeeking)
    }

    @Test
    fun testDownOutsideSeekZoneDoesNotTrack() {
        val rec = ArcSeekGestureRecognizer()
        // 圆屏中心与左侧均不属于寻道带
        assertFalse(rec.onDown(233f, 233f, w, h, 10))
        assertFalse(rec.isTracking)
        assertFalse(rec.onMove(233f, 240f, w, h, totalChapters))
        assertNull(rec.onUp())
    }

    @Test
    fun testSmallMoveBelowThresholdDoesNotEnterSeeking() {
        val rec = ArcSeekGestureRecognizer()
        rec.onDown(inZoneX, inZoneY, w, h, 10)
        // 位移 1px、角度变化不足 3°，应保持候选态且不消费事件
        assertFalse(rec.onMove(inZoneX - 1f, inZoneY + 1f, w, h, totalChapters))
        assertFalse(rec.isSeeking)
        assertNull(rec.onUp())
    }

    @Test
    fun testDragBeyondThresholdEntersSeekingAndConsumes() {
        val rec = ArcSeekGestureRecognizer()
        rec.onDown(inZoneX, inZoneY, w, h, 10)
        // 沿弧移动到 +30°
        val x = 233f + 220f * Math.cos(Math.toRadians(30.0)).toFloat()
        val y = 233f + 220f * Math.sin(Math.toRadians(30.0)).toFloat()
        assertTrue(rec.onMove(x, y, w, h, totalChapters))
        assertTrue(rec.isSeeking)
        assertEquals(ArcSeekMath.angleToChapterIndex(30f, totalChapters), rec.targetChapterIndex)
    }

    @Test
    fun testReleaseConfirmsTargetChapter() {
        val rec = ArcSeekGestureRecognizer()
        rec.onDown(inZoneX, inZoneY, w, h, 10)
        val x = 233f + 220f * Math.cos(Math.toRadians(30.0)).toFloat()
        val y = 233f + 220f * Math.sin(Math.toRadians(30.0)).toFloat()
        rec.onMove(x, y, w, h, totalChapters)
        assertEquals(ArcSeekMath.angleToChapterIndex(30f, totalChapters), rec.onUp())
        assertFalse(rec.isSeeking)
        assertFalse(rec.isTracking)
    }

    @Test
    fun testDragTowardCenterCancelsSeek() {
        val rec = ArcSeekGestureRecognizer()
        rec.onDown(inZoneX, inZoneY, w, h, 10)
        // 划入屏幕过深（半径 120 < 165）应取消
        rec.onMove(233f + 120f, 233f, w, h, totalChapters)
        assertTrue(rec.isSeeking)
        assertNull(rec.onUp())
    }

    @Test
    fun testSeekAfterCancellationWorksAgain() {
        val rec = ArcSeekGestureRecognizer()
        rec.onDown(inZoneX, inZoneY, w, h, 10)
        rec.onMove(233f + 120f, 233f, w, h, totalChapters)
        assertNull(rec.onUp())

        // 取消后再次进入寻道应正常确认
        assertTrue(rec.onDown(inZoneX, inZoneY, w, h, 10))
        val x = 233f + 220f * Math.cos(Math.toRadians(-30.0)).toFloat()
        val y = 233f + 220f * Math.sin(Math.toRadians(-30.0)).toFloat()
        rec.onMove(x, y, w, h, totalChapters)
        assertEquals(ArcSeekMath.angleToChapterIndex(-30f, totalChapters), rec.onUp())
    }

    @Test
    fun testMoveWithoutTrackingIsIgnored() {
        val rec = ArcSeekGestureRecognizer()
        assertFalse(rec.onMove(inZoneX, inZoneY, w, h, totalChapters))
        assertFalse(rec.isSeeking)
        assertNull(rec.onUp())
    }

    @Test
    fun testResetClearsState() {
        val rec = ArcSeekGestureRecognizer()
        rec.onDown(inZoneX, inZoneY, w, h, 10)
        rec.onMove(inZoneX - 40f, inZoneY + 40f, w, h, totalChapters)
        rec.reset()
        assertFalse(rec.isTracking)
        assertFalse(rec.isSeeking)
        assertNull(rec.onUp())
    }
}
