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

    // ── 系统 inset 参与取大：方屏手机 / 刘海屏不被状态栏与导航条压住 ──
    // 圆屏手表上 safeDrawing 通常为 0，解析结果必须与收口前完全一致（不改变既有视觉）

    @Test
    fun testSystemInsetTakesOverWhenLarger() {
        // 44dp 约定值 < 系统状态栏 52dp → 采用系统值
        assertEquals(52f, WatchSafeArea.topInsetDp(WATCH_PX, WATCH_DENSITY, systemInsetDp = 52f), DELTA)
        // 84dp 约定值 > 系统导航条 48dp → 仍采用约定值，圆屏底部操作条hot zone不被压缩
        assertEquals(84f, WatchSafeArea.bottomInsetDp(WATCH_PX, WATCH_DENSITY, systemInsetDp = 48f), DELTA)
        // 横向：24dp 约定值与系统值取大
        assertEquals(38f, WatchSafeArea.horizontalInsetDp(38f), DELTA)
        assertEquals(24f, WatchSafeArea.horizontalInsetDp(0f), DELTA)
    }

    @Test
    fun testReaderUsesLowerPreferredInsetsThanPages() {
        // 阅读页顶部 52dp / 底部 56dp：顶部比页面级多 8dp 给弧形章节名，底部不避让操作条
        assertEquals(52f, WatchSafeArea.topInsetDp(WATCH_PX, WATCH_DENSITY, WatchSafeArea.READER_TOP_INSET_DP), DELTA)
        assertEquals(56f, WatchSafeArea.bottomInsetDp(WATCH_PX, WATCH_DENSITY, WatchSafeArea.READER_BOTTOM_INSET_DP), DELTA)
    }

    @Test
    fun testContentInsetUsesMaxOfAllThree() {
        assertEquals(52f, WatchSafeArea.contentInsetDp(44f, 34.1221f, 52f), DELTA)
        assertEquals(90.9921f, WatchSafeArea.contentInsetDp(44f, 90.9921f, 0f), DELTA)
    }

    // ── 系统 inset 从 0 变为非 0：解析结果必须随 inset 变化 ────────
    // P3-2 的验收口径：inset 迟到（首帧 rootWindowInsets 未就绪）由几何下限兜底，
    // 晚到的 inset 一旦大于约定值就必须立即接管，而不是等下一次无关重组

    @Test
    fun testSystemInsetTakesOverAfterArrivingLate() {
        // 首帧：inset 尚未就绪 → 约定值兜底
        assertEquals(44f, WatchSafeArea.topInsetDp(WATCH_PX, WATCH_DENSITY, systemInsetDp = 0f), DELTA)
        // 迟到但更小：仍用约定值，视觉不因 inset 迟到而缩水
        assertEquals(44f, WatchSafeArea.topInsetDp(WATCH_PX, WATCH_DENSITY, systemInsetDp = 30f), DELTA)
        // 迟到且更大：立即接管
        assertEquals(52f, WatchSafeArea.topInsetDp(WATCH_PX, WATCH_DENSITY, systemInsetDp = 52f), DELTA)
        // 阅读页底部约定值 56dp 大于系统的 52dp，迟到 inset 不得压缩既有留白
        assertEquals(56f, WatchSafeArea.bottomInsetDp(
            WATCH_PX, WATCH_DENSITY, WatchSafeArea.READER_BOTTOM_INSET_DP, systemInsetDp = 52f
        ), DELTA)
    }
}
