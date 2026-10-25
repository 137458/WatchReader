package com.watchreader

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 主题令牌系统（Material 3 亮 / 暗双档）
 *
 * 结构延续 ADR-013 的「少量命名基色 → 全量角色显式映射」思路，映射目标改为
 * Material 3 [ColorScheme]（决策全文见 ADR-018，取代 ADR-014/015 的 miuix 组件树）：
 * - 表面层：background → surfaceContainerLowest/Low/Container/High/Highest 按 MD3
 *   tonal elevation 形成层次；暗档 background 保持纯黑以熄灭 OLED 像素；
 * - 文字层：onBackground / onSurface / onSurfaceVariant 两档语义，禁止运行时叠 alpha；
 * - 强调层：primary（品牌蓝：进度 / 选中 / 当前章节）、secondary（格式绿：TXT 角标 /
 *   成功提示）、tertiary（书签琥珀：书签与夜读语义），每个强调色绑定单一语义。
 *
 * 与 ADR-013 的差异：角色名从自建命名令牌换成 MD3 官方角色；语义契约不变。
 * 全部文字对由 MaterialPaletteTest 以 WCAG 2.1 断言守住（正文 ≥ 4.5:1）。
 */

// ═══════════════════ MD3 浅色（日间） ═══════════════════
// 品牌蓝 seed 同源 tonal 推导；表面为冷中性灰白，卡片层次由 surfaceContainer 系列承担。
private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF0061A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD1E4FF),
    onPrimaryContainer = Color(0xFF001D36),
    inversePrimary = Color(0xFF9ECAFF),

    secondary = Color(0xFF2E6C4F),              // 格式绿（TXT 角标 / 成功提示）
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB4F1D3),
    onSecondaryContainer = Color(0xFF002114),

    tertiary = Color(0xFF8A4B08),               // 书签琥珀
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCC0),
    onTertiaryContainer = Color(0xFF2E1500),

    background = Color(0xFFF8F9FF),
    onBackground = Color(0xFF191C20),
    surface = Color(0xFFF8F9FF),
    onSurface = Color(0xFF191C20),
    surfaceVariant = Color(0xFFDFE2EB),
    onSurfaceVariant = Color(0xFF43474E),
    surfaceTint = Color(0xFF0061A4),
    inverseSurface = Color(0xFF2E3135),
    inverseOnSurface = Color(0xFFEFF0F7),

    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),

    outline = Color(0xFF73777F),                // 强描边
    outlineVariant = Color(0xFFC3C6CF),         // 弱分隔线
    scrim = Color(0xFF000000),

    surfaceBright = Color(0xFFF8F9FF),
    surfaceDim = Color(0xFFD8DAE0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F3FA),
    surfaceContainer = Color(0xFFECEEF4),
    surfaceContainerHigh = Color(0xFFE6E8EE),
    surfaceContainerHighest = Color(0xFFE0E2E8),

    primaryFixed = Color(0xFFD1E4FF),
    primaryFixedDim = Color(0xFF9ECAFF),
    onPrimaryFixed = Color(0xFF001D36),
    onPrimaryFixedVariant = Color(0xFF00497D),
    secondaryFixed = Color(0xFFB4F1D3),
    secondaryFixedDim = Color(0xFF8ED6B0),
    onSecondaryFixed = Color(0xFF002114),
    onSecondaryFixedVariant = Color(0xFF11512F),
    tertiaryFixed = Color(0xFFFFDCC0),
    tertiaryFixedDim = Color(0xFFFFB95F),
    onTertiaryFixed = Color(0xFF2E1500),
    onTertiaryFixedVariant = Color(0xFF663D00)
)

// ═══════════════════ MD3 深色（AMOLED 纯黑底） ═══════════════════
// background 纯黑（OLED 像素熄灭），surfaceContainer 逐档提亮承担卡片 / 浮层层次；
// 主色与语义色按 MD3 暗档 tonal 提亮，保证 10–13sp 小字仍达 WCAG AA。
private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFF9ECAFF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF00497D),
    onPrimaryContainer = Color(0xFFD1E4FF),
    inversePrimary = Color(0xFF0061A4),

    secondary = Color(0xFF8ED6B0),
    onSecondary = Color(0xFF00382A),
    secondaryContainer = Color(0xFF00513D),
    onSecondaryContainer = Color(0xFFA8F0CC),

    tertiary = Color(0xFFFFB95F),
    onTertiary = Color(0xFF482800),
    tertiaryContainer = Color(0xFF663D00),
    onTertiaryContainer = Color(0xFFFFDDB0),

    background = Color(0xFF000000),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF0A0A0C),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF43474E),
    onSurfaceVariant = Color(0xFFC3C6CF),
    surfaceTint = Color(0xFF9ECAFF),
    inverseSurface = Color(0xFFE2E2E6),
    inverseOnSurface = Color(0xFF2E3135),

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    outline = Color(0xFF8D9199),
    outlineVariant = Color(0xFF43474E),
    scrim = Color(0xFF000000),

    surfaceBright = Color(0xFF36393E),
    surfaceDim = Color(0xFF0A0A0C),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF111214),
    surfaceContainer = Color(0xFF17181B),
    surfaceContainerHigh = Color(0xFF1D1E21),
    surfaceContainerHighest = Color(0xFF242529),

    primaryFixed = Color(0xFFD1E4FF),
    primaryFixedDim = Color(0xFF9ECAFF),
    onPrimaryFixed = Color(0xFF001D36),
    onPrimaryFixedVariant = Color(0xFF00497D),
    secondaryFixed = Color(0xFFB4F1D3),
    secondaryFixedDim = Color(0xFF8ED6B0),
    onSecondaryFixed = Color(0xFF002114),
    onSecondaryFixedVariant = Color(0xFF11512F),
    tertiaryFixed = Color(0xFFFFDCC0),
    tertiaryFixedDim = Color(0xFFFFB95F),
    onTertiaryFixed = Color(0xFF2E1500),
    onTertiaryFixedVariant = Color(0xFF663D00)
)

/** 双档主题唯一入口（MainActivity 唯一切换点） */
fun watchColorSchemeOf(mode: ThemeMode): ColorScheme = when (mode) {
    ThemeMode.LIGHT -> LightColors
    ThemeMode.DARK -> DarkColors
}

/**
 * 主题无关的功能固定色：语义上必须脱离主题才能成立（可扫描性 / 物理暗化），
 * 集中登记避免散落各页的"影子色"
 */
object WatchFixed {
    /** 寻道 HUD 悬浮胶囊底：所有主题恒黑底（悬浮于正文之上，黑底保证任意正文配色下可读） */
    val HudScrim = Color(0xDD000000)

    /** 寻道 HUD 悬浮文字：恒黑底配固定柔白字 */
    val HudInk = Color(0xFFF0EFEC)

    /** 二维码面板底与前景：白底深码是扫码引擎的识别前提，严禁随主题反转 */
    val QrPanelBackground = Color(0xFFFFFFFF)
    val QrPanelInk = Color(0xFF444444)

    /** 二维码码点模块两色（QrCodeGenerator 矩阵绘制）：与面板同源的扫码引擎前置条件 */
    val QrModuleInk = Color(0xFF000000)
    val QrModulePaper = Color(0xFFFFFFFF)
}

/**
 * 手表字号排版（Material 3 Type Scale 的腕上收敛版）
 *
 * MD3 手机档位（displayLarge 57sp / headlineLarge 32sp）在 466px 圆屏上会吞噬正文区，
 * 故整体下压约 25%：displaySmall 28sp 用于传书百分比、headlineSmall 20sp 用于页面主标题、
 * titleMedium 15sp 用于卡片标题、bodyLarge 14sp 承载正文、labelLarge 12sp 用于胶囊按钮。
 * 字体族使用系统默认（Roboto / 系统中文字形），不向 APK 内置字体。
 */
val WatchTypography: Typography = Typography(
    displayLarge = TextStyle(fontSize = 36.sp, lineHeight = 44.sp, fontWeight = FontWeight.Normal),
    displayMedium = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal),
    displaySmall = TextStyle(
        fontSize = 28.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.3).sp
    ),
    headlineLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.1.sp
    ),
    titleLarge = TextStyle(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.1.sp
    ),
    titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
        fontFamily = FontFamily.Default
    ),
    bodyMedium = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp
    ),
    labelMedium = TextStyle(
        fontSize = 11.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp
    ),
    labelSmall = TextStyle(
        fontSize = 10.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.3.sp
    )
)

/** 手表形状标度：卡片走 medium，浮层走 large；胶囊按钮显式使用 CircleShape */
val WatchShapesM3: Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
