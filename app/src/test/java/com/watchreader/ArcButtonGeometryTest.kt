package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 弧形胶囊按钮几何测试：按钮为两端圆头的弧形胶囊，外缘悬离屏幕圆周一段距离
 *
 * 背景约束：底部操作条由「环形扇带贴死圆边」改为「弧形胶囊离边悬浮」——
 * 1. 外缘半径 = 屏幕半径 − 离边 inset（不触圆弧），内缘 = 外缘 − 径向厚度；
 * 2. 槽位角度沿用既有口径：单键 90°，双键 125°/55°，三键 140°/90°/40°；
 * 3. 相邻按钮之间保留间隙角，单键半跨度有固定上限；
 * 4. 文字沿弧逐字排布：阅读方向沿屏幕 +x（向右），底部弧上向右行进角度递减，
 *    整段文字按累计字宽在中点角两侧对称展开。
 */
class ArcButtonGeometryTest {

    private companion object {
        const val SCREEN = 466f
        const val THICKNESS = 26f
        const val GAP = 6f
        const val EDGE_INSET = 6f
    }

    // ── 槽位角度 ──────────────────────────────────────────────

    @Test
    fun testAnglesMatchLegacyLayout() {
        assertEquals(listOf(90f), ArcButtonGeometry.slotAnglesDeg(1))
        assertEquals(listOf(125f, 55f), ArcButtonGeometry.slotAnglesDeg(2))
        assertEquals(listOf(140f, 90f, 40f), ArcButtonGeometry.slotAnglesDeg(3))
    }

    @Test
    fun testUnknownCountFallsBackToSingleCenterSlot() {
        assertEquals(listOf(90f), ArcButtonGeometry.slotAnglesDeg(0))
        assertEquals(listOf(90f), ArcButtonGeometry.slotAnglesDeg(9))
    }

    // ── 胶囊几何：离边 inset 与厚度 ──────────────────────────

    @Test
    fun testOuterEdgeSitsAtEdgeInset() {
        val sectors = ArcButtonGeometry.sectors(3, SCREEN, SCREEN, THICKNESS, GAP, EDGE_INSET)
        // 外缘 = 屏幕半径 − 离边距离，按钮悬空不触圆弧
        assertTrue(sectors.all { abs(it.outerRadiusPx - (SCREEN / 2f - EDGE_INSET)) < 0.01f })
        // 内缘 = 外缘 − 径向厚度
        assertTrue(sectors.all { abs(it.innerRadiusPx - (SCREEN / 2f - EDGE_INSET - THICKNESS)) < 0.01f })
        // 内缘必须为正（厚度不得吃穿半径）
        assertTrue(sectors.all { it.innerRadiusPx > 0f })
    }

    @Test
    fun testDefaultInsetIsZero() {
        val sectors = ArcButtonGeometry.sectors(1, SCREEN, SCREEN, THICKNESS, GAP)
        assertTrue(sectors.all { abs(it.outerRadiusPx - SCREEN / 2f) < 0.01f })
    }

    @Test
    fun testInsetClampStillHolds() {
        // 厚度吃穿半径时钳制语义在带 inset 时同样成立
        val sectors = ArcButtonGeometry.sectors(1, SCREEN, SCREEN, thicknessPx = 400f, gapDeg = GAP, edgeInsetPx = EDGE_INSET)
        val outer = SCREEN / 2f - EDGE_INSET
        assertEquals(outer / 2f, sectors[0].innerRadiusPx, 0.01f)
    }

    @Test
    fun testThicknessSurvivesClampWhenTooDeep() {
        // 厚度大于半径时不得产生负内缘：钳制为半径的一半
        val sectors = ArcButtonGeometry.sectors(1, SCREEN, SCREEN, thicknessPx = 400f, gapDeg = GAP)
        assertEquals(SCREEN / 4f, sectors[0].innerRadiusPx, 0.01f)
    }

    // ── 扇带几何：槽位跨度与间隙 ──────────────────────────────

    @Test
    fun testSectorSpanCenteredOnSlotAngle() {
        val sectors = ArcButtonGeometry.sectors(3, SCREEN, SCREEN, THICKNESS, GAP)
        val centers = sectors.map { (it.startAngleDeg + it.endAngleDeg) / 2f }
        assertEquals(listOf(140f, 90f, 40f), centers)
    }

    @Test
    fun testAdjacentSectorsKeepGap() {
        val sectors = ArcButtonGeometry.sectors(3, SCREEN, SCREEN, THICKNESS, GAP)
        // 125° 与 90° 两槽之间的间隙
        assertEquals(GAP, sectors[0].startAngleDeg - sectors[1].endAngleDeg, 0.01f)
        assertEquals(GAP, sectors[1].startAngleDeg - sectors[2].endAngleDeg, 0.01f)
    }

    @Test
    fun testSingleButtonUsesWideSpan() {
        val single = ArcButtonGeometry.sectors(1, SCREEN, SCREEN, THICKNESS, GAP)
        val span = single[0].endAngleDeg - single[0].startAngleDeg
        // 单键长标签（如「自动滚屏 350 px/s」）需要宽扇带，跨度 = 2 × 32°
        assertEquals(64f, span, 0.01f)
    }

    @Test
    fun testTripleButtonSpanFitsBetweenNeighbors() {
        val sectors = ArcButtonGeometry.sectors(3, SCREEN, SCREEN, THICKNESS, GAP)
        val span = sectors[1].endAngleDeg - sectors[1].startAngleDeg
        // 三键：中心距 50°（140/90/40），去 6° 间隙后每槽 44°，未触 32° 上限
        assertEquals(44f, span, 0.01f)
    }

    @Test
    fun testSectorStartBeforeEnd() {
        ArcButtonGeometry.sectors(2, SCREEN, SCREEN, THICKNESS, GAP).forEach {
            assertTrue(it.startAngleDeg < it.endAngleDeg)
        }
    }

    // ── 弧线逐字角度 ─────────────────────────────────────────

    @Test
    fun testSingleCharStaysAtCenterAngle() {
        val angles = ArcButtonGeometry.charCenterAnglesDeg(
            totalAdvancePx = 10f, advancesPx = listOf(10f), radiusPx = 100f, centerAngleDeg = 90f
        )
        assertEquals(listOf(90f), angles)
    }

    @Test
    fun testTextSpreadsSymmetricallyAroundCenter() {
        // 三个等宽字：中字在中心角，两侧对称
        val angles = ArcButtonGeometry.charCenterAnglesDeg(
            totalAdvancePx = 30f, advancesPx = listOf(10f, 10f, 10f), radiusPx = 100f, centerAngleDeg = 90f
        )
        assertEquals(3, angles.size)
        assertEquals(90f, angles[1], 0.001f)
        assertEquals(abs(angles[0] - 90f), abs(angles[2] - 90f), 0.001f)
        // 底部弧：阅读方向向右 = 角度递减，首字（最左）角度最大
        assertTrue(angles[0] > angles[1])
        assertTrue(angles[1] > angles[2])
    }

    @Test
    fun testAngularAdvanceFollowsArcLength() {
        // 等宽字距 10px、半径 100px → 每字间隔 = degrees(10/100)
        val angles = ArcButtonGeometry.charCenterAnglesDeg(
            totalAdvancePx = 30f, advancesPx = listOf(10f, 10f, 10f), radiusPx = 100f, centerAngleDeg = 90f
        )
        val expectedStep = Math.toDegrees(10.0 / 100.0).toFloat()
        assertEquals(expectedStep, angles[0] - angles[1], 0.001f)
        assertEquals(expectedStep, angles[1] - angles[2], 0.001f)
    }

    @Test
    fun testUnequalAdvancesShiftNeighbors() {
        // 宽字 20px 在中间：左右字到中心角的角距不等，宽字侧更远
        val angles = ArcButtonGeometry.charCenterAnglesDeg(
            totalAdvancePx = 40f, advancesPx = listOf(10f, 20f, 10f), radiusPx = 100f, centerAngleDeg = 90f
        )
        // 左字中心偏移 −(40/2−5)=−15px，右字 +(40/2−5)=+15px → 角距相等才对？
        // 不：首字中心 = −(40−10)/2 = −15px，末字中心 = +(40−10)/2 = +15px，对称。
        assertEquals(90f + Math.toDegrees(15.0 / 100.0).toFloat(), angles[0], 0.001f)
        assertEquals(90f - Math.toDegrees(15.0 / 100.0).toFloat(), angles[2], 0.001f)
    }

    @Test
    fun testRadiusHalvesAngularSpread() {
        val near = ArcButtonGeometry.charCenterAnglesDeg(
            totalAdvancePx = 30f, advancesPx = listOf(10f, 10f, 10f), radiusPx = 100f, centerAngleDeg = 90f
        )
        val far = ArcButtonGeometry.charCenterAnglesDeg(
            totalAdvancePx = 30f, advancesPx = listOf(10f, 10f, 10f), radiusPx = 200f, centerAngleDeg = 90f
        )
        assertEquals((near[0] - near[2]) / 2f, far[0] - far[2], 0.001f)
    }

    // ── 扇带文字半径 ─────────────────────────────────────────

    @Test
    fun testTextRadiusSitsAtBandMiddle() {
        val sectors = ArcButtonGeometry.sectors(1, SCREEN, SCREEN, THICKNESS, GAP, EDGE_INSET)
        assertEquals(SCREEN / 2f - EDGE_INSET - THICKNESS / 2f, sectors[0].textRadiusPx, 0.01f)
    }
}
