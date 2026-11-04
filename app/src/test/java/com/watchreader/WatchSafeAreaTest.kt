package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 圆屏安全区几何测试：把「页面留白」从各屏裸 dp 收进一处可验证的几何
 *
 * 不变量：
 * 1. 内接正方形边长 = 屏宽 / √2，弧顶余量 =（屏宽 − 边长）/ 2 —— 圆屏可见内容的几何边界；
 * 2. 正常手表（466px / density 2.0）的约定值 44dp / 84dp 必须胜过几何下限 34.1dp，
 *    即接入安全区不改变既有视觉；
 * 3. 异常尺寸或低 density（预览、投屏）时由几何下限兜底，避免内容侵入弧角；
 * 4. density 非法时退化为 0，绝不让除零产出 Infinity 留白。
 */
class WatchSafeAreaTest {

    private companion object {
        const val DELTA = 0.01f
        const val SMALL_WATCH_PX = 454f
        const val WATCH_PX = 466f
        const val WATCH_DENSITY = 2f
    }

    // ── 466x466 主尺寸：约定值优先，几何下限不篡改视觉 ──────────

    @Test
    fun testInscribedGeometryOnWatchX() {
        assertEquals(329.5118f, WatchSafeArea.inscribedSide(WATCH_PX), DELTA)
        assertEquals(68.2441f, WatchSafeArea.arcMargin(WATCH_PX), DELTA)
        assertEquals(34.1221f, WatchSafeArea.minInsetDp(WATCH_PX, WATCH_DENSITY), DELTA)
    }

    @Test
    fun testConventionalInsetsWinOnWatchX() {
        assertEquals(44f, WatchSafeArea.topInsetDp(WATCH_PX, WATCH_DENSITY), DELTA)
        assertEquals(84f, WatchSafeArea.bottomInsetDp(WATCH_PX, WATCH_DENSITY), DELTA)
    }

    // ── 454x454 小圆屏：同一口径换一组尺寸，证明不是对 466 特化 ──

    @Test
    fun testInscribedGeometryOnSmallerWatch() {
        assertEquals(321.0265f, WatchSafeArea.inscribedSide(SMALL_WATCH_PX), DELTA)
        assertEquals(66.4868f, WatchSafeArea.arcMargin(SMALL_WATCH_PX), DELTA)
        assertEquals(33.2434f, WatchSafeArea.minInsetDp(SMALL_WATCH_PX, WATCH_DENSITY), DELTA)
        assertEquals(44f, WatchSafeArea.topInsetDp(SMALL_WATCH_PX, WATCH_DENSITY), DELTA)
        assertEquals(84f, WatchSafeArea.bottomInsetDp(SMALL_WATCH_PX, WATCH_DENSITY), DELTA)
    }

    // ── 低 density：几何下限反超约定值，接管留白 ────────────────

    @Test
    fun testLowDensityFallsBackToGeometry() {
        // 466px / 0.75 → 弧顶余量折算 90.99dp，超过 44dp 约定值，说明兜底生效
        assertEquals(90.9921f, WatchSafeArea.topInsetDp(WATCH_PX, 0.75f), DELTA)
        assertEquals(90.9921f, WatchSafeArea.bottomInsetDp(WATCH_PX, 0.75f), DELTA)
    }

    @Test
    fun testInvalidDensityFallsBackToConventions() {
        assertEquals(0f, WatchSafeArea.minInsetDp(WATCH_PX, 0f), DELTA)
        assertEquals(44f, WatchSafeArea.topInsetDp(WATCH_PX, 0f), DELTA)
        assertEquals(84f, WatchSafeArea.bottomInsetDp(WATCH_PX, 0f), DELTA)
    }

    // ── 约定值与屏幕消费口径一致：左右留白是单值令牌，不随尺寸变 ──

    @Test
    fun testHorizontalPaddingIsSingleToken() {
        assertEquals(24f, WatchSafeArea.HORIZONTAL_PADDING_DP, DELTA)
    }
}
