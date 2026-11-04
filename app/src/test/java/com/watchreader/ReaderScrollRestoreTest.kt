package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读位置恢复换算测试
 *
 * 三套换算（章节顶部锚点 / 行定位 / 行高比例）与收窄口径、布局就绪判据全部是纯函数，
 * 这里用具体像素值钉住；真实 scrollTo / 监听注册留在 ReaderScreen，不在单测范围内。
 */
class ReaderScrollRestoreTest {

    @Test
    fun testLineZeroLandsOnBodyTop() {
        // 章首行（line == 0）必须落在正文控件顶端，即顶部内边距处，而不是 0
        assertEquals(132, ReaderScrollMath.lineScrollY(bodyTop = 132, lineTop = 0))
    }

    @Test
    fun testLineMappingAddsLineTopToBodyTop() {
        assertEquals(612, ReaderScrollMath.lineScrollY(bodyTop = 132, lineTop = 480))
        assertEquals(400, ReaderScrollMath.lineScrollY(bodyTop = 132, lineTop = 268))
    }

    @Test
    fun testClampBeyondMaxScrollLandsOnMax() {
        assertEquals(400, ReaderScrollMath.clampScrollY(612, maxScrollY = 400))
        assertEquals(900, ReaderScrollMath.clampScrollY(1200, maxScrollY = 900))
    }

    @Test
    fun testClampInRangeKeepsDesired() {
        assertEquals(612, ReaderScrollMath.clampScrollY(612, maxScrollY = 900))
        assertEquals(0, ReaderScrollMath.clampScrollY(-5, maxScrollY = 900))
    }

    @Test
    fun testUnknownMaxScrollDoesNotCollapseToTop() {
        // 布局未就绪时上限为 0：只保证非负，不收窄，否则每次进章节都被压回顶部
        assertEquals(612, ReaderScrollMath.clampScrollY(612, maxScrollY = 0))
    }

    @Test
    fun testRatioMapping() {
        assertEquals(632, ReaderScrollMath.ratioScrollY(bodyTop = 132, bodyHeight = 2000, ratio = 0.25f))
        assertEquals(132, ReaderScrollMath.ratioScrollY(bodyTop = 132, bodyHeight = 2000, ratio = 0f))
    }

    @Test
    fun testTopAnchorSubtractsTitleGap() {
        assertEquals(282, ReaderScrollMath.topAnchorScrollY(titleTop = 300, sixDpPx = 18))
        assertEquals(0, ReaderScrollMath.topAnchorScrollY(titleTop = 10, sixDpPx = 18))
    }

    @Test
    fun testLayoutReadyRequiresBothLayoutAndHeight() {
        assertTrue(ReaderScrollMath.isLayoutReady(bodyHeightPx = 1200, hasLayout = true))
        assertFalse(ReaderScrollMath.isLayoutReady(bodyHeightPx = 0, hasLayout = true))
        assertFalse(ReaderScrollMath.isLayoutReady(bodyHeightPx = 1200, hasLayout = false))
    }
}
