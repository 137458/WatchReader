package com.watchreader

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.CurvedTextStyle
import androidx.wear.compose.material3.ColorScheme as WearColorScheme
import androidx.wear.compose.material3.MotionScheme
import androidx.wear.compose.material3.Shapes as WearShapes
import androidx.wear.compose.material3.Typography as WearTypography

/**
 * Wear Compose Material 3 主题桥接（ADR-021）
 *
 * 把手表既有的 MD3 令牌（[ColorScheme] / 字号 / 形状）映射到 Wear M3 的同名角色，
 * 使官方手表组件（Button / Card / Slider / Stepper / ScrollIndicator / ScalingLazyColumn）
 * 与既有页面共享同一套配色与排版，不出现两套视觉体系。
 *
 * 映射原则：
 * - 颜色角色一一对应；Wear 多出的 primaryDim / secondaryDim / tertiaryDim / errorDim
 *   由对应基色向黑色插值得到（腕上压暗档，用于 pressed / disabled 变体）；
 * - 形状沿用 MD3 shape scale 的圆角半径，卡片 medium=16dp；
 * - 字号沿用手表收敛档（MD3 type scale 下压 25%），弧形排版槽位用同档字号构造。
 */
internal fun Color.dim(): Color = lerp(this, Color.Black, 0.24f)

/** MD3 语义色 → Wear ColorScheme */
internal fun watchWearColorSchemeOf(scheme: ColorScheme): WearColorScheme = WearColorScheme(
    primary = scheme.primary,
    primaryDim = scheme.primary.dim(),
    primaryContainer = scheme.primaryContainer,
    onPrimary = scheme.onPrimary,
    onPrimaryContainer = scheme.onPrimaryContainer,
    secondary = scheme.secondary,
    secondaryDim = scheme.secondary.dim(),
    secondaryContainer = scheme.secondaryContainer,
    onSecondary = scheme.onSecondary,
    onSecondaryContainer = scheme.onSecondaryContainer,
    tertiary = scheme.tertiary,
    tertiaryDim = scheme.tertiary.dim(),
    tertiaryContainer = scheme.tertiaryContainer,
    onTertiary = scheme.onTertiary,
    onTertiaryContainer = scheme.onTertiaryContainer,
    surfaceContainerLow = scheme.surfaceContainerLow,
    surfaceContainer = scheme.surfaceContainer,
    surfaceContainerHigh = scheme.surfaceContainerHigh,
    onSurface = scheme.onSurface,
    onSurfaceVariant = scheme.onSurfaceVariant,
    outline = scheme.outline,
    outlineVariant = scheme.outlineVariant,
    background = scheme.background,
    onBackground = scheme.onBackground,
    error = scheme.error,
    errorDim = scheme.error.dim(),
    errorContainer = scheme.errorContainer,
    onError = scheme.onError,
    onErrorContainer = scheme.onErrorContainer
)

private fun curved(size: androidx.compose.ui.unit.TextUnit, weight: FontWeight) =
    CurvedTextStyle(fontSize = size, fontWeight = weight)

/** 手表收敛档字号 → Wear Typography（弧形槽位取同档 title / label 字号） */
internal fun watchWearTypography(): WearTypography = WearTypography().copy(
    arcLarge = curved(28.sp, FontWeight.Bold),
    arcMedium = curved(20.sp, FontWeight.Bold),
    arcSmall = curved(15.sp, FontWeight.SemiBold),
    displayLarge = TextStyle(fontSize = 36.sp, lineHeight = 44.sp),
    displayMedium = TextStyle(fontSize = 32.sp, lineHeight = 40.sp),
    displaySmall = TextStyle(fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp),
    bodyLarge = TextStyle(fontSize = 14.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp),
    bodyExtraSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp)
)

/** 动效方案：Wear M3 标准档 */
internal fun watchWearMotionScheme(): MotionScheme = MotionScheme.standard()

/** 形状映射：沿用 MD3 shape scale 圆角半径 */
internal fun watchWearShapes(): WearShapes = WearShapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * 页面级 Wear M3 主题包裹。
 *
 * MainActivity 已在根部嵌套 WearMaterialTheme；此处为各页面提供自洽的包裹入口，
 * 使页面内直接使用官方手表组件（Button / Card / ListSubHeader / 进度指示）时
 * 仍能拿到与全局一致的配色、字号与形状令牌。
 */
@Composable
internal fun WatchReaderWearTheme(content: @Composable () -> Unit) {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    androidx.wear.compose.material3.MaterialTheme(
        colorScheme = watchWearColorSchemeOf(scheme),
        typography = watchWearTypography(),
        shapes = watchWearShapes(),
        motionScheme = watchWearMotionScheme()
    ) {
        content()
    }
}
