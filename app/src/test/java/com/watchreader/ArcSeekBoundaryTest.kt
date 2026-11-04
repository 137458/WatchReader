package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 弧形寻道边界测试（ArcSeekMath / ArcSeekGestureRecognizer，见 ArcSeekEngine.kt）
 *
 * 只锁既有测试未覆盖的三类边界：0°/360° 环绕与角度端点、径向热区内/外 1px、
 * 进度与章节索引的钳制；几何常量一律引用 ArcSeekMath 的公开常量，避免测试固化字面量。
 */
class ArcSeekBoundaryTest {

    private val cx = 233f
    private val cy = 233f
    private val w = 466f
    private val h = 466f

    /** 极坐标取点（角度制），半径默认落在热区中心 220 */
    private fun px(deg: Double, r: Float = 220f): Float =
        cx + (r * Math.cos(Math.toRadians(deg))).toFloat()

    private fun py(deg: Double, r: Float = 220f): Float =
        cy + (r * Math.sin(Math.toRadians(deg))).toFloat()

    // ---------- 角度↔进度换算 ----------

    @Test
    fun testProgressClampsBothEnds() {
        assertEquals(0.0, ArcSeekMath.calculateProgress(ArcSeekMath.MIN_ANGLE).toDouble(), 1e-6)
        assertEquals(1.0, ArcSeekMath.calculateProgress(ArcSeekMath.MAX_ANGLE).toDouble(), 1e-6)
        // 越界角度按端点钳制，不得溢出到区间外
        assertEquals(0.0, ArcSeekMath.calculateProgress(-100f).toDouble(), 1e-6)
        assertEquals(1.0, ArcSeekMath.calculateProgress(100f).toDouble(), 1e-6)
        assertEquals(0.5, ArcSeekMath.calculateProgress(0f).toDouble(), 1e-6)
        assertEquals(0.75, ArcSeekMath.calculateProgress(30f).toDouble(), 1e-6)
        assertEquals(0.25, ArcSeekMath.calculateProgress(-30f).toDouble(), 1e-6)
    }

    @Test
    fun testChapterIndexClampsToValidRange() {
        val total = 100
        assertEquals(0, ArcSeekMath.angleToChapterIndex(-100f, total))
        assertEquals(total - 1, ArcSeekMath.angleToChapterIndex(100f, total))
        assertEquals(50, ArcSeekMath.angleToChapterIndex(0f, total))
        // 非正章节数一律回落到 0，不抛异常、不产生负索引
        assertEquals(0, ArcSeekMath.angleToChapterIndex(0f, 0))
        assertEquals(0, ArcSeekMath.angleToChapterIndex(30f, -5))
        // 单章：任意角度都只能落在 0
        assertEquals(0, ArcSeekMath.angleToChapterIndex(ArcSeekMath.MIN_ANGLE, 1))
        assertEquals(0, ArcSeekMath.angleToChapterIndex(ArcSeekMath.MAX_ANGLE, 1))
    }

    @Test
    fun testChapterIndexSmallRangesCoverEveryChapter() {
        assertEquals(0, ArcSeekMath.angleToChapterIndex(ArcSeekMath.MIN_ANGLE, 2))
        assertEquals(1, ArcSeekMath.angleToChapterIndex(ArcSeekMath.MAX_ANGLE, 2))
        assertEquals(0, ArcSeekMath.angleToChapterIndex(-59f, 2))
        assertEquals(1, ArcSeekMath.angleToChapterIndex(59f, 2))

        assertEquals(0, ArcSeekMath.angleToChapterIndex(ArcSeekMath.MIN_ANGLE, 3))
        assertEquals(2, ArcSeekMath.angleToChapterIndex(ArcSeekMath.MAX_ANGLE, 3))
        assertEquals(2, ArcSeekMath.angleToChapterIndex(30f, 3))
        assertEquals(1, ArcSeekMath.angleToChapterIndex(-30f, 3))
        assertEquals(2, ArcSeekMath.angleToChapterIndex(0f, 4))
    }

    // ---------- 触点命中判定：径向 1px 与角度端点 ----------

    @Test
    fun testSeekZoneRadialBoundaryOnePixel() {
        assertTrue(ArcSeekMath.isInSeekZone(cx + ArcSeekMath.SEEK_RADIUS_INNER, cy, cx, cy))
        assertFalse(ArcSeekMath.isInSeekZone(cx + ArcSeekMath.SEEK_RADIUS_INNER - 1f, cy, cx, cy))
        assertTrue(ArcSeekMath.isInSeekZone(cx + ArcSeekMath.SEEK_RADIUS_OUTER, cy, cx, cy))
        assertFalse(ArcSeekMath.isInSeekZone(cx + ArcSeekMath.SEEK_RADIUS_OUTER + 1f, cy, cx, cy))
    }

    @Test
    fun testSeekZoneAngleBoundaryHalfDegree() {
        // 端点内 0.5° 命中，端点外 0.5° 落空（上下两侧各验一遍）
        assertTrue(ArcSeekMath.isInSeekZone(px(59.5), py(59.5), cx, cy))
        assertFalse(ArcSeekMath.isInSeekZone(px(60.5), py(60.5), cx, cy))
        assertTrue(ArcSeekMath.isInSeekZone(px(-59.5), py(-59.5), cx, cy))
        assertFalse(ArcSeekMath.isInSeekZone(px(-60.5), py(-60.5), cx, cy))
    }

    @Test
    fun testSeekZoneWrapsAroundZeroDegree() {
        // 359.9° 与 -0.1° 是同一物理方向，必须同判命中
        assertTrue(ArcSeekMath.isInSeekZone(px(359.9), py(359.9), cx, cy))
        assertTrue(ArcSeekMath.isInSeekZone(px(0.0), py(0.0), cx, cy))
        assertTrue(ArcSeekMath.isInSeekZone(px(360.0), py(360.0), cx, cy))
        // 环绕的另一侧：左半屏（180°）与正下（90°）都不属于寻道带
        assertFalse(ArcSeekMath.isInSeekZone(px(180.0), py(180.0), cx, cy))
        assertFalse(ArcSeekMath.isInSeekZone(px(90.0), py(90.0), cx, cy))
        assertFalse(ArcSeekMath.isInSeekZone(px(-180.0), py(-180.0), cx, cy))
    }

    @Test
    fun testSeekZoneDiagonalJustOutsideRadialBand() {
        assertFalse(ArcSeekMath.isInSeekZone(px(45.0, 199f), py(45.0, 199f), cx, cy))
        assertTrue(ArcSeekMath.isInSeekZone(px(45.0, 201f), py(45.0, 201f), cx, cy))
        assertFalse(ArcSeekMath.isInSeekZone(px(-45.0, 246f), py(-45.0, 246f), cx, cy))
        assertTrue(ArcSeekMath.isInSeekZone(px(-45.0, 244f), py(-45.0, 244f), cx, cy))
    }

    // ---------- 悬浮胶囊文案 ----------

    @Test
    fun testFormatSeekLabelEdgeCases() {
        // 章节总数为 0：百分比回落 0，不产生除零崩溃
        assertEquals("第 1/0 章 · 0%", ArcSeekMath.formatSeekLabel(0, 0, ""))
        // 纯空白标题视作无标题，不输出空括号
        assertEquals("第 1/3 章 · 33%", ArcSeekMath.formatSeekLabel(0, 3, "   "))
        assertEquals("第 3/3 章 · 100% (末章)", ArcSeekMath.formatSeekLabel(2, 3, "末章"))
    }

    // ---------- 手势状态机边界 ----------

    @Test
    fun testDownExactlyOnRadialBoundaryStartsTracking() {
        assertTrue(ArcSeekGestureRecognizer().onDown(cx + ArcSeekMath.SEEK_RADIUS_INNER, cy, w, h, 0))
        assertTrue(ArcSeekGestureRecognizer().onDown(cx + ArcSeekMath.SEEK_RADIUS_OUTER, cy, w, h, 0))
        assertFalse(ArcSeekGestureRecognizer().onDown(cx + ArcSeekMath.SEEK_RADIUS_INNER - 1f, cy, w, h, 0))
        assertFalse(ArcSeekGestureRecognizer().onDown(cx + ArcSeekMath.SEEK_RADIUS_OUTER + 1f, cy, w, h, 0))
    }

    @Test
    fun testCancelRadiusBoundaryKeepsSeekAlive() {
        val total = 100
        val alive = ArcSeekGestureRecognizer()
        assertTrue(alive.onDown(cx + 220f, cy, w, h, 0))
        alive.onMove(cx + ArcSeekMath.SEEK_CANCEL_RADIUS, cy, w, h, total)
        // 恰好落在取消半径上不算取消（严格小于才取消）
        assertEquals(ArcSeekMath.angleToChapterIndex(0f, total), alive.onUp())

        val dead = ArcSeekGestureRecognizer()
        assertTrue(dead.onDown(cx + 220f, cy, w, h, 0))
        dead.onMove(cx + ArcSeekMath.SEEK_CANCEL_RADIUS - 1f, cy, w, h, total)
        assertNull(dead.onUp())
    }

    @Test
    fun testCancelAngleBoundaryAndClamp() {
        val total = 100
        // 74° 未越过取消角 75°，但已超出寻道区间 → 角度钳到 MAX_ANGLE，落在末章
        val clamped = ArcSeekGestureRecognizer()
        assertTrue(clamped.onDown(cx + 220f, cy, w, h, 0))
        assertTrue(clamped.onMove(px(74.0, 230f), py(74.0, 230f), w, h, total))
        assertEquals(ArcSeekMath.MAX_ANGLE.toDouble(), clamped.currentAngle.toDouble(), 1e-6)
        assertEquals(total - 1, clamped.onUp()!!.toInt())

        // 75.5° 越过取消角 → 本次寻道作废
        val cancelled = ArcSeekGestureRecognizer()
        assertTrue(cancelled.onDown(cx + 220f, cy, w, h, 0))
        cancelled.onMove(px(75.5, 230f), py(75.5, 230f), w, h, total)
        assertNull(cancelled.onUp())
    }

    @Test
    fun testDragThresholdIsInclusive() {
        // 位移恰好 10px：门限取 >=，应进入寻道态并消费事件
        val atLimit = ArcSeekGestureRecognizer()
        assertTrue(atLimit.onDown(cx + 220f, cy, w, h, 0))
        assertTrue(atLimit.onMove(cx + 230f, cy, w, h, 100))
        assertTrue(atLimit.isSeeking)

        // 位移 9.5px 且角度不变：仍属候选态，不消费事件
        val belowLimit = ArcSeekGestureRecognizer()
        assertTrue(belowLimit.onDown(cx + 220f, cy, w, h, 0))
        assertFalse(belowLimit.onMove(cx + 229.5f, cy, w, h, 100))
        assertFalse(belowLimit.isSeeking)
        assertNull(belowLimit.onUp())
    }
}
