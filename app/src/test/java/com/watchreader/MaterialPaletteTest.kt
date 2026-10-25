package com.watchreader

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Material 3 亮 / 暗双档色板验收（ADR-018）：
 * 把 ADR-013 的「逐对实测」升级为可执行断言，覆盖不透明纪律、表面层次方向、
 * WCAG 2.1 对比度与语义三色色相分离。
 *
 * 阈值口径：
 * - 正文与次级墨对全部 ≥ 4.5:1（WCAG AA）；
 * - 强调色作为小字号文字色使用时 ≥ 4.5:1；
 * - 发丝描边 / 分隔线按装饰豁免，只要求与宿主表面存在可辨明度步长。
 */
class MaterialPaletteTest {

    private val dark = watchColorSchemeOf(ThemeMode.DARK)
    private val light = watchColorSchemeOf(ThemeMode.LIGHT)

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

    private fun assertAtLeast(name: String, actual: Float, need: Float) {
        assertTrue("$name 实测 ${"%.2f".format(actual)} 低于要求 $need", actual >= need - 0.01f)
    }

    // ────────────────────────── 角色完整性 ──────────────────────────

    /** 本应用实际消费的 M3 角色（角色名 → 取值器），新增消费点必须同步扩表 */
    private val roleGetters: List<Pair<String, ColorScheme.() -> Color>> = listOf(
        "primary" to { primary }, "onPrimary" to { onPrimary },
        "primaryContainer" to { primaryContainer }, "onPrimaryContainer" to { onPrimaryContainer },
        "inversePrimary" to { inversePrimary },
        "secondary" to { secondary }, "onSecondary" to { onSecondary },
        "secondaryContainer" to { secondaryContainer }, "onSecondaryContainer" to { onSecondaryContainer },
        "tertiary" to { tertiary }, "onTertiary" to { onTertiary },
        "tertiaryContainer" to { tertiaryContainer }, "onTertiaryContainer" to { onTertiaryContainer },
        "background" to { background }, "onBackground" to { onBackground },
        "surface" to { surface }, "onSurface" to { onSurface },
        "surfaceVariant" to { surfaceVariant }, "onSurfaceVariant" to { onSurfaceVariant },
        "surfaceTint" to { surfaceTint },
        "inverseSurface" to { inverseSurface }, "inverseOnSurface" to { inverseOnSurface },
        "error" to { error }, "onError" to { onError },
        "errorContainer" to { errorContainer }, "onErrorContainer" to { onErrorContainer },
        "outline" to { outline }, "outlineVariant" to { outlineVariant }, "scrim" to { scrim },
        "surfaceBright" to { surfaceBright }, "surfaceDim" to { surfaceDim },
        "surfaceContainerLowest" to { surfaceContainerLowest },
        "surfaceContainerLow" to { surfaceContainerLow },
        "surfaceContainer" to { surfaceContainer },
        "surfaceContainerHigh" to { surfaceContainerHigh },
        "surfaceContainerHighest" to { surfaceContainerHighest }
    )

    @Test
    fun `角色表覆盖本应用消费的全部 M3 角色`() {
        assertTrue("角色表至少覆盖 30 个 M3 角色", roleGetters.size >= 30)
    }

    @Test
    fun `全部角色为不透明色`() {
        for ((modeName, colors) in listOf("dark" to dark, "light" to light)) {
            for ((name, getter) in roleGetters) {
                assertAtLeast("$modeName.$name alpha", colors.getter().alpha, 1f)
            }
        }
    }

    // ────────────────────────── 表面层次 ──────────────────────────

    @Test
    fun `浅色档表面由最亮卡片到最高浮层逐档压暗`() {
        val lowest = luminance(light.surfaceContainerLowest)
        val surface = luminance(light.surface)
        val low = luminance(light.surfaceContainerLow)
        val container = luminance(light.surfaceContainer)
        val high = luminance(light.surfaceContainerHigh)
        val highest = luminance(light.surfaceContainerHighest)
        assertTrue(
            "期望 lowest>surface>=low>container>high>highest，实测 $lowest/$surface/$low/$container/$high/$highest",
            lowest > surface && surface >= low && low > container && container > high && high > highest
        )
    }

    @Test
    fun `深色档由纯黑页面到最高容器逐档提亮`() {
        val background = luminance(dark.background)
        val surface = luminance(dark.surface)
        val low = luminance(dark.surfaceContainerLow)
        val container = luminance(dark.surfaceContainer)
        val high = luminance(dark.surfaceContainerHigh)
        val highest = luminance(dark.surfaceContainerHighest)
        assertTrue(
            "期望 background<=surface<=low<container<high<highest，实测 $background/$surface/$low/$container/$high/$highest",
            background <= surface && surface <= low && low < container && container < high && high < highest
        )
        assertAtLeast("深色档 background 纯黑（OLED 像素熄灭）", background, 0f)
        assertTrue("深色档 background 必须为纯黑", background == 0f)
    }

    @Test
    fun `描边与宿主表面有可辨明度步长`() {
        for ((name, colors) in listOf("dark" to dark, "light" to light)) {
            val stepOutline = kotlin.math.abs(luminance(colors.outline) - luminance(colors.surface))
            val stepVariant = kotlin.math.abs(luminance(colors.outlineVariant) - luminance(colors.surface))
            assertAtLeast("$name outline 与 surface 明度步长", stepOutline, 0.02f)
            assertAtLeast("$name outlineVariant 与 surface 明度步长", stepVariant, 0.01f)
        }
    }

    // ────────────────────────── 文字对比度 ──────────────────────────

    @Test
    fun `正文与次级墨在每一档表面达 WCAG AA`() {
        for ((name, colors) in listOf("dark" to dark, "light" to light)) {
            val surfaces = listOf(
                "background" to colors.background,
                "surface" to colors.surface,
                "surfaceContainerLow" to colors.surfaceContainerLow,
                "surfaceContainer" to colors.surfaceContainer,
                "surfaceContainerHigh" to colors.surfaceContainerHigh,
                "surfaceContainerHighest" to colors.surfaceContainerHighest
            )
            assertAtLeast("$name onBackground/background", ratio(colors.onBackground, colors.background), 4.5f)
            assertAtLeast("$name onSurface/surface", ratio(colors.onSurface, colors.surface), 4.5f)
            for ((surfaceName, surfaceColor) in surfaces) {
                assertAtLeast("$name onSurface/$surfaceName", ratio(colors.onSurface, surfaceColor), 4.5f)
                assertAtLeast("$name onSurfaceVariant/$surfaceName", ratio(colors.onSurfaceVariant, surfaceColor), 4.5f)
            }
        }
    }

    @Test
    fun `品牌蓝作文字色在三档表面达 AA 且配对内容色达 AA`() {
        for ((name, colors) in listOf("dark" to dark, "light" to light)) {
            for ((surfaceName, surfaceColor) in listOf(
                "background" to colors.background,
                "surface" to colors.surface,
                "surfaceContainer" to colors.surfaceContainer,
                "surfaceContainerHigh" to colors.surfaceContainerHigh
            )) {
                assertAtLeast("$name primary/$surfaceName", ratio(colors.primary, surfaceColor), 4.5f)
            }
            assertAtLeast("$name onPrimary/primary", ratio(colors.onPrimary, colors.primary), 4.5f)
            assertAtLeast("$name onPrimaryContainer/primaryContainer", ratio(colors.onPrimaryContainer, colors.primaryContainer), 4.5f)
        }
    }

    @Test
    fun `格式绿前景与容器对达 AA`() {
        for ((name, colors) in listOf("dark" to dark, "light" to light)) {
            for ((surfaceName, surfaceColor) in listOf(
                "background" to colors.background,
                "surface" to colors.surface,
                "surfaceContainer" to colors.surfaceContainer
            )) {
                assertAtLeast("$name secondary(格式绿)/$surfaceName", ratio(colors.secondary, surfaceColor), 4.5f)
            }
            assertAtLeast("$name onSecondary/secondary", ratio(colors.onSecondary, colors.secondary), 4.5f)
            assertAtLeast("$name onSecondaryContainer/secondaryContainer", ratio(colors.onSecondaryContainer, colors.secondaryContainer), 4.5f)
        }
    }

    @Test
    fun `书签琥珀前景与容器对达 AA`() {
        for ((name, colors) in listOf("dark" to dark, "light" to light)) {
            for ((surfaceName, surfaceColor) in listOf(
                "background" to colors.background,
                "surface" to colors.surface,
                "surfaceContainer" to colors.surfaceContainer
            )) {
                assertAtLeast("$name tertiary(琥珀)/$surfaceName", ratio(colors.tertiary, surfaceColor), 4.5f)
            }
            assertAtLeast("$name onTertiary/tertiary", ratio(colors.onTertiary, colors.tertiary), 4.5f)
            assertAtLeast("$name onTertiaryContainer/tertiaryContainer", ratio(colors.onTertiaryContainer, colors.tertiaryContainer), 4.5f)
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

    @Test
    fun `语义三色色相分离避免混淆`() {
        for ((name, colors) in listOf("dark" to dark, "light" to light)) {
            val hBlue = hueDegrees(colors.primary)
            val hGreen = hueDegrees(colors.secondary)
            val hAmber = hueDegrees(colors.tertiary)
            assertAtLeast("$name 蓝/绿色相分离", hueSeparation(hBlue, hGreen), 40f)
            assertAtLeast("$name 绿/琥珀色相分离", hueSeparation(hGreen, hAmber), 40f)
            assertAtLeast("$name 蓝/琥珀色相分离", hueSeparation(hBlue, hAmber), 40f)
        }
    }

    @Test
    fun `亮暗两档正文对比度均高于 12 比 1`() {
        assertAtLeast("dark onBackground/background", ratio(dark.onBackground, dark.background), 12f)
        assertAtLeast("light onBackground/background", ratio(light.onBackground, light.background), 12f)
    }
}
