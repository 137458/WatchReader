package com.watchreader

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yukonga.miuix.kmp.theme.Colors

/**
 * HyperOS 两档（深空蓝 / 晴空蓝）色板验收：
 * 把 ADR-013 的「逐对实测」从注释里的数字升级为可执行断言，覆盖
 * 不透明纪律、表面层次方向、WCAG 2.1 对比度、HyperOS 中性容器纪律与语义三色色相分离。
 *
 * 阈值口径（与 ADR-013 / ADR-015 一致）：
 * - 正文与次级墨对全部 ≥ 4.5:1（WCAG AA）；
 * - 强调色作为小字号文字色使用时 ≥ 4.5:1；
 * - 发丝描边 / 分隔线按装饰豁免，只要求与宿主表面存在可辨明度步长。
 */
class HyperOsPaletteTest {

    private val dark = watchColorsOf(ThemeMode.MIUIX)
    private val light = watchColorsOf(ThemeMode.MIUIX_LIGHT)

    // ────────────────────────── WCAG 工具 ──────────────────────────

    private fun linearize(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055) / 1.055).toDouble(), 2.4).toFloat()

    private fun luminance(color: Color): Float =
        0.2126f * linearize(color.red) + 0.7152f * linearize(color.green) + 0.0722f * linearize(color.blue)

    private fun ratio(fg: Color, bg: Color): Float {
        val a = luminance(fg)
        val b = luminance(bg)
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }

    /** 色相角（度），无彩度返回 -1；用于校验语义三色的色相分离 */
    private fun hueDegrees(color: Color): Float {
        val r = color.red
        val g = color.green
        val b = color.blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (delta == 0f) return -1f
        val segment = when (max) {
            r -> ((g - b) / delta) % 6f
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        }
        return (segment * 60f + 360f) % 360f
    }

    private fun hueSeparation(a: Float, b: Float): Float {
        val d = kotlin.math.abs(a - b)
        return minOf(d, 360f - d)
    }

    /** 近中性判定：最大通道差（chroma）足够小，容器与描边才不会串色 */
    private fun chroma(color: Color): Float =
        maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue)

    private fun assertAtLeast(name: String, actual: Float, need: Float) {
        assertTrue("$name 实测 ${"%.2f".format(actual)} 低于要求 $need", actual >= need - 0.01f)
    }

    // ────────────────────────── 角色完整性 ──────────────────────────

    /**
     * miuix Colors 全部 53 角色的取值器。
     * 新增角色（库升级）时此表不会报错，但下面的不透明断言会漏掉它——
     * 故配套一条数量断言，扩表与升级必须同步。
     */
    private val roleGetters: List<Pair<String, Colors.() -> Color>> = listOf(
        "primary" to { primary }, "onPrimary" to { onPrimary },
        "primaryVariant" to { primaryVariant }, "onPrimaryVariant" to { onPrimaryVariant },
        "error" to { error }, "onError" to { onError },
        "errorContainer" to { errorContainer }, "onErrorContainer" to { onErrorContainer },
        "disabledPrimary" to { disabledPrimary }, "disabledOnPrimary" to { disabledOnPrimary },
        "disabledPrimaryButton" to { disabledPrimaryButton }, "disabledOnPrimaryButton" to { disabledOnPrimaryButton },
        "disabledPrimarySlider" to { disabledPrimarySlider },
        "primaryContainer" to { primaryContainer }, "onPrimaryContainer" to { onPrimaryContainer },
        "secondary" to { secondary }, "onSecondary" to { onSecondary },
        "secondaryVariant" to { secondaryVariant }, "onSecondaryVariant" to { onSecondaryVariant },
        "disabledSecondary" to { disabledSecondary }, "disabledOnSecondary" to { disabledOnSecondary },
        "disabledSecondaryVariant" to { disabledSecondaryVariant }, "disabledOnSecondaryVariant" to { disabledOnSecondaryVariant },
        "secondaryContainer" to { secondaryContainer }, "onSecondaryContainer" to { onSecondaryContainer },
        "secondaryContainerVariant" to { secondaryContainerVariant }, "onSecondaryContainerVariant" to { onSecondaryContainerVariant },
        "tertiaryContainer" to { tertiaryContainer }, "onTertiaryContainer" to { onTertiaryContainer },
        "tertiaryContainerVariant" to { tertiaryContainerVariant },
        "background" to { background }, "onBackground" to { onBackground },
        "onBackgroundVariant" to { onBackgroundVariant },
        "surface" to { surface }, "onSurface" to { onSurface }, "surfaceVariant" to { surfaceVariant },
        "onSurfaceSecondary" to { onSurfaceSecondary }, "onSurfaceVariantSummary" to { onSurfaceVariantSummary },
        "onSurfaceVariantActions" to { onSurfaceVariantActions }, "disabledOnSurface" to { disabledOnSurface },
        "surfaceContainer" to { surfaceContainer }, "onSurfaceContainer" to { onSurfaceContainer },
        "onSurfaceContainerVariant" to { onSurfaceContainerVariant },
        "surfaceContainerHigh" to { surfaceContainerHigh }, "onSurfaceContainerHigh" to { onSurfaceContainerHigh },
        "surfaceContainerHighest" to { surfaceContainerHighest }, "onSurfaceContainerHighest" to { onSurfaceContainerHighest },
        "outline" to { outline }, "dividerLine" to { dividerLine }, "windowDimming" to { windowDimming },
        "sliderKeyPoint" to { sliderKeyPoint }, "sliderKeyPointForeground" to { sliderKeyPointForeground },
        "sliderBackground" to { sliderBackground },
    )

    @Test
    fun `角色取值表覆盖 miuix 全部 53 角色`() {
        assertEquals(53, roleGetters.size)
    }

    @Test
    fun `除系统遮罩外全部角色为不透明色`() {
        for (mode in listOf(ThemeMode.MIUIX, ThemeMode.MIUIX_LIGHT)) {
            val colors = watchColorsOf(mode)
            for ((name, getter) in roleGetters) {
                if (name == "windowDimming") continue
                val alpha = colors.getter().alpha
                assertAtLeast("$mode.$name alpha", alpha, 1f)
            }
        }
    }

    // ────────────────────────── 表面层次 ──────────────────────────

    @Test
    fun `暗色档表面层次由页面到卡片到按钮逐档提亮`() {
        val page = luminance(dark.background)
        val card = luminance(dark.surface)
        val button = luminance(dark.surfaceVariant)
        val highest = luminance(dark.surfaceContainerHighest)
        assertTrue("期望 page<card<button<highest，实测 $page/$card/$button/$highest",
            page < card && card < button && button < highest)
    }

    @Test
    fun `亮色档卡片比页面更亮而按钮衬底回落`() {
        val page = luminance(light.background)
        val card = luminance(light.surface)
        val button = luminance(light.surfaceVariant)
        val highest = luminance(light.surfaceContainerHighest)
        assertTrue("期望 card>page 且 button<card 且 highest<button，实测 $page/$card/$button/$highest",
            card > page && button < card && highest < button)
    }

    @Test
    fun `分隔线与描边相对宿主表面有可辨步长`() {
        for ((name, colors) in listOf("dark" to dark, "light" to light)) {
            val stepDivider = kotlin.math.abs(luminance(colors.dividerLine) - luminance(colors.surface))
            val stepOutline = kotlin.math.abs(luminance(colors.outline) - luminance(colors.surface))
            val stepTrack = kotlin.math.abs(luminance(colors.sliderBackground) - luminance(colors.surface))
            assertAtLeast("$name dividerLine 与 surface 明度步长", stepDivider, 0.012f)
            assertAtLeast("$name outline 与 surface 明度步长", stepOutline, 0.03f)
            assertAtLeast("$name sliderBackground 与 surface 明度步长", stepTrack, 0.012f)
        }
    }

    // ────────────────────────── 文字对比度 ──────────────────────────

    @Test
    fun `正文与次级墨在每一档表面达 WCAG AA`() {
        for ((name, colors) in listOf("dark" to dark, "light" to light)) {
            val surfaces = listOf(
                "background" to colors.background,
                "surface" to colors.surface,
                "surfaceContainer" to colors.surfaceContainer,
                "surfaceVariant" to colors.surfaceVariant,
                "surfaceContainerHigh" to colors.surfaceContainerHigh,
                "surfaceContainerHighest" to colors.surfaceContainerHighest,
            )
            assertAtLeast("$name onBackground/background", ratio(colors.onBackground, colors.background), 4.5f)
            assertAtLeast("$name onSurface/surface", ratio(colors.onSurface, colors.surface), 4.5f)
            assertAtLeast("$name onSurfaceContainer/surfaceContainer", ratio(colors.onSurfaceContainer, colors.surfaceContainer), 4.5f)
            assertAtLeast("$name onSurfaceContainerHighest/surfaceContainerHighest", ratio(colors.onSurfaceContainerHighest, colors.surfaceContainerHighest), 4.5f)
            assertAtLeast("$name onBackgroundVariant/background", ratio(colors.onBackgroundVariant, colors.background), 4.5f)
            assertAtLeast("$name onSurfaceContainerVariant/surfaceContainer", ratio(colors.onSurfaceContainerVariant, colors.surfaceContainer), 4.5f)
            for ((surfaceName, surfaceColor) in surfaces) {
                assertAtLeast("$name onSurfaceVariantSummary/$surfaceName", ratio(colors.onSurfaceVariantSummary, surfaceColor), 4.5f)
                assertAtLeast("$name onSurface/$surfaceName", ratio(colors.onSurface, surfaceColor), 4.5f)
            }
            assertAtLeast("$name onSurfaceSecondary/surface", ratio(colors.onSurfaceSecondary, colors.surface), 4.5f)
            assertAtLeast("$name onSurfaceSecondary/surfaceVariant", ratio(colors.onSurfaceSecondary, colors.surfaceVariant), 4.5f)
        }
    }

    // ────────────────────────── 语义三色 ──────────────────────────

    @Test
    fun `品牌蓝作文字色在页面卡片按钮三档表面达 AA 且入口卡配字达 AA`() {
        for ((name, colors) in listOf("dark" to dark, "light" to light)) {
            for ((surfaceName, surfaceColor) in listOf(
                "background" to colors.background,
                "surface" to colors.surface,
                "surfaceVariant" to colors.surfaceVariant,
                "surfaceContainerHigh" to colors.surfaceContainerHigh,
            )) {
                assertAtLeast("$name primary/$surfaceName", ratio(colors.primary, surfaceColor), 4.5f)
            }
            // 继续阅读 / 当前章节 hero 卡：primary 作底，onPrimary 承载 10sp 脚注
            assertAtLeast("$name onPrimary/primary", ratio(colors.onPrimary, colors.primary), 4.5f)
            assertAtLeast("$name onPrimaryContainer/primaryContainer", ratio(colors.onPrimaryContainer, colors.primaryContainer), 4.5f)
            // 进度填充相对弱档轨道必须可辨（非文字，2:1 起判）
            assertAtLeast("$name primary/dividerLine 进度可辨", ratio(colors.primary, colors.dividerLine), 2f)
        }
    }

    @Test
    fun `格式绿前景与容器对达 AA 且与绿容器同色族`() {
        for ((name, colors) in listOf("dark" to dark, "light" to light)) {
            for ((surfaceName, surfaceColor) in listOf(
                "background" to colors.background,
                "surface" to colors.surface,
                "surfaceVariant" to colors.surfaceVariant,
            )) {
                assertAtLeast("$name secondary(格式绿)/$surfaceName", ratio(colors.secondary, surfaceColor), 4.5f)
            }
            assertAtLeast("$name onSecondary/secondary", ratio(colors.onSecondary, colors.secondary), 4.5f)
            // TXT 角标容器对（BookshelfScreen 走 secondaryContainer/onSecondaryContainer）
            assertAtLeast("$name onSecondaryContainer/secondaryContainer", ratio(colors.onSecondaryContainer, colors.secondaryContainer), 4.5f)
            assertAtLeast("$name onSurface/secondaryContainer", ratio(colors.onSurface, colors.secondaryContainer), 4.5f)
            assertAtLeast("$name onTertiaryContainer/tertiaryContainer", ratio(colors.onTertiaryContainer, colors.tertiaryContainer), 4.5f)
            val hGreen = hueDegrees(colors.secondary)
            val hGreenContainer = hueDegrees(colors.secondaryContainer)
            assertTrue("$name 格式绿前景与绿容器色族漂移过大：$hGreen vs $hGreenContainer",
                hGreen >= 0f && hGreenContainer >= 0f && hueSeparation(hGreen, hGreenContainer) <= 40f)
        }
    }

    @Test
    fun `书签琥珀在页面卡片按钮三档表面达 AA`() {
        for ((name, colors) in listOf("dark" to dark, "light" to light)) {
            for ((surfaceName, surfaceColor) in listOf(
                "background" to colors.background,
                "surface" to colors.surface,
                "surfaceVariant" to colors.surfaceVariant,
            )) {
                assertAtLeast("$name tertiaryContainerVariant(琥珀)/$surfaceName",
                    ratio(colors.tertiaryContainerVariant, surfaceColor), 4.5f)
            }
        }
    }

    @Test
    fun `语义三色色相分离避免混淆`() {
        for ((name, colors) in listOf("dark" to dark, "light" to light)) {
            val hBlue = hueDegrees(colors.primary)
            val hGreen = hueDegrees(colors.secondary)
            val hAmber = hueDegrees(colors.tertiaryContainerVariant)
            assertAtLeast("$name 蓝/绿色相分离", hueSeparation(hBlue, hGreen), 40f)
            assertAtLeast("$name 绿/琥珀色相分离", hueSeparation(hGreen, hAmber), 40f)
            assertAtLeast("$name 蓝/琥珀色相分离", hueSeparation(hBlue, hAmber), 40f)
        }
    }

    // ────────────────────────── HyperOS 中性纪律 ──────────────────────────

    @Test
    fun `表面与中性容器保持近中性以贴合 HyperOS`() {
        for ((name, colors) in listOf("dark" to dark, "light" to light)) {
            val neutrals = listOf(
                "background" to colors.background,
                "surface" to colors.surface,
                "surfaceVariant" to colors.surfaceVariant,
                "surfaceContainer" to colors.surfaceContainer,
                "surfaceContainerHigh" to colors.surfaceContainerHigh,
                "surfaceContainerHighest" to colors.surfaceContainerHighest,
                "secondaryContainerVariant" to colors.secondaryContainerVariant,
                "dividerLine" to colors.dividerLine,
                "outline" to colors.outline,
            )
            for ((neutralName, neutralColor) in neutrals) {
                assertTrue("$name $neutralName 彩度过大（${"%.3f".format(chroma(neutralColor) * 255)}），HyperOS 中性档必须近灰",
                    chroma(neutralColor) * 255f <= 14f)
            }
        }
    }

    @Test
    fun `错误色与容器对达 AA`() {
        for ((name, colors) in listOf("dark" to dark, "light" to light)) {
            assertAtLeast("$name error/background", ratio(colors.error, colors.background), 4.5f)
            assertAtLeast("$name error/surface", ratio(colors.error, colors.surface), 4.5f)
            assertAtLeast("$name onError/error", ratio(colors.onError, colors.error), 4.5f)
            assertAtLeast("$name onErrorContainer/errorContainer", ratio(colors.onErrorContainer, colors.errorContainer), 4.5f)
        }
    }

    // ────────────────────────── 档位协议 ──────────────────────────

    @Test
    fun `HyperOS 两档名称与持久化值原样保留`() {
        assertEquals(3, ThemeMode.MIUIX.value)
        assertEquals(4, ThemeMode.MIUIX_LIGHT.value)
        assertEquals("深空蓝", ThemeMode.MIUIX.label)
        assertEquals("晴空蓝", ThemeMode.MIUIX_LIGHT.label)
        assertTrue(ThemeMode.MIUIX.isDark)
        assertTrue(!ThemeMode.MIUIX_LIGHT.isDark)
        assertEquals(5, ThemeMode.entries.size)
    }

    @Test
    fun `HyperOS 亮暗两档主文字色互为反向层次`() {
        // 暗档：柔白字深底；亮档：浓墨字浅底——两档正文对比度都必须在 12:1 以上，
        // 否则说明重做时把某一档调成了「灰字灰底」
        assertAtLeast("dark onBackground/background", ratio(dark.onBackground, dark.background), 12f)
        assertAtLeast("light onBackground/background", ratio(light.onBackground, light.background), 12f)
    }
}
