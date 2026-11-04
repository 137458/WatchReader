package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置行里那些「值 → 文案 / 步进」的换算：亮度步进与各档位文案。
 *
 * 这些换算原先散在 DisplaySettingsBlock 的两个 lambda 与 PreferenceSettingsBlock 的
 * 三个局部 val 里，只能靠像素回归看；抽成纯对象后按业务口径逐条锁：
 * 步长与钳制边界、系统档起跳、以及每一档中文文案的唯一口径。
 */
class MenuSectionLogicTest {

    // ── 亮度步进 ──

    @Test
    fun testBrightnessStepMovesByFivePercent() {
        assertEquals(0.45f, BrightnessStep.step(0.50f, -0.05f), 1e-6f)
        assertEquals(0.55f, BrightnessStep.step(0.50f, 0.05f), 1e-6f)
        assertEquals(0.60f, BrightnessStep.step(0.55f, 0.05f), 1e-6f)
    }

    @Test
    fun testBrightnessStepFromSystemDefaultStartsAtStepBase() {
        // 「跟随系统」档（-1f）不能按 -1+0.05 起跳，必须从 BrightnessManager.SYSTEM_STEP_BASE 起跳
        assertEquals(
            BrightnessStep.step(BrightnessManager.SYSTEM_STEP_BASE, 0.05f),
            BrightnessStep.step(BrightnessManager.BRIGHTNESS_SYSTEM_DEFAULT, 0.05f),
            1e-6f
        )
        assertEquals(
            BrightnessStep.step(BrightnessManager.SYSTEM_STEP_BASE, -0.05f),
            BrightnessStep.step(BrightnessManager.BRIGHTNESS_SYSTEM_DEFAULT, -0.05f),
            1e-6f
        )
        assertEquals(0.55f, BrightnessStep.step(BrightnessManager.BRIGHTNESS_SYSTEM_DEFAULT, 0.05f), 1e-6f)
    }

    @Test
    fun testBrightnessStepClampsAtBothEnds() {
        assertEquals(BrightnessStep.MIN, BrightnessStep.step(0.02f, -0.05f), 1e-6f)
        assertEquals(BrightnessStep.MIN, BrightnessStep.step(BrightnessStep.MIN, -0.05f), 1e-6f)
        assertEquals(BrightnessStep.MAX, BrightnessStep.step(0.99f, 0.05f), 1e-6f)
        assertEquals(BrightnessStep.MAX, BrightnessStep.step(BrightnessStep.MAX, 0.05f), 1e-6f)
    }

    @Test
    fun testBrightnessStepIsSymmetricAroundBase() {
        // 同一基准上「减」与「加」对称偏移，避免某一侧把基准判反
        val down = BrightnessStep.step(0.60f, -0.05f)
        val up = BrightnessStep.step(0.60f, 0.05f)
        assertTrue(up > 0.60f)
        assertTrue(down < 0.60f)
        assertEquals(1.20f, up + down, 1e-6f)
    }

    @Test
    fun testBrightnessStepZeroDeltaKeepsValue() {
        assertEquals(0.30f, BrightnessStep.step(0.30f, 0f), 1e-6f)
        // 系统档 + 零步长：停在起步基准而不是停在 -1f
        assertEquals(
            BrightnessManager.SYSTEM_STEP_BASE,
            BrightnessStep.step(BrightnessManager.BRIGHTNESS_SYSTEM_DEFAULT, 0f),
            1e-6f
        )
    }

    // ── 值文案 ──

    @Test
    fun testTapPageAreaLabelsCoverAllModes() {
        assertEquals("上下点按", MenuValueText.tapPageArea(TapPageArea.TOP_BOTTOM))
        assertEquals("左右点按", MenuValueText.tapPageArea(TapPageArea.LEFT_RIGHT))
        assertEquals("已关闭", MenuValueText.tapPageArea(TapPageArea.DISABLED))
    }

    @Test
    fun testTapPageAreaLabelsAreDistinct() {
        val labels = TapPageArea.entries.map { MenuValueText.tapPageArea(it) }
        assertEquals(TapPageArea.entries.size, labels.toSet().size)
    }

    @Test
    fun testTapPageAreaUnknownValueFallsBackToTopBottomLabel() {
        // 持久化里出现未知档位时，枚举回落 TOP_BOTTOM，文案必须跟着一起回落
        assertEquals("上下点按", MenuValueText.tapPageArea(TapPageArea.fromValue(99)))
    }

    @Test
    fun testFontTypeLabelsMatchReaderFonts() {
        assertEquals("宋体", MenuValueText.fontType(FontType.SERIF))
        assertEquals("黑体", MenuValueText.fontType(FontType.SANS_SERIF))
        assertEquals("宋体", MenuValueText.fontType(FontType.fromValue(FontType.SERIF.value)))
        assertNotEquals(
            MenuValueText.fontType(FontType.SERIF),
            MenuValueText.fontType(FontType.SANS_SERIF)
        )
    }

    @Test
    fun testCleanTypographyLabels() {
        assertEquals("开启", MenuValueText.cleanTypography(true))
        assertEquals("关闭", MenuValueText.cleanTypography(false))
    }

    @Test
    fun testAutoScrollStateLabelsCarryRunningState() {
        assertEquals("自动滚屏中", MenuValueText.autoScrollState(true))
        assertEquals("自动滚屏", MenuValueText.autoScrollState(false))
    }

    @Test
    fun testAutoScrollSpeedTruncatesToWholePixelsPerSecond() {
        assertEquals("42 px/s", MenuValueText.autoScrollSpeed(42.9f))
        assertEquals("43 px/s", MenuValueText.autoScrollSpeed(43.0f))
        assertEquals("0 px/s", MenuValueText.autoScrollSpeed(0f))
        assertEquals(
            "${AutoScrollSpeed.MAX.toInt()} px/s",
            MenuValueText.autoScrollSpeed(AutoScrollSpeed.MAX)
        )
        assertEquals(
            "${AutoScrollSpeed.MIN.toInt()} px/s",
            MenuValueText.autoScrollSpeed(AutoScrollSpeed.MIN)
        )
    }

    @Test
    fun testLabelsDoNotLeakBetweenRows() {
        // 开关类文案与档位类文案互不复用，防止复制粘贴时把「开启/关闭」贴到档位行
        assertNotEquals(
            MenuValueText.cleanTypography(true),
            MenuValueText.tapPageArea(TapPageArea.TOP_BOTTOM)
        )
        assertNotEquals(
            MenuValueText.autoScrollState(false),
            MenuValueText.autoScrollState(true)
        )
    }
}
