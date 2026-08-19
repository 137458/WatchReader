package com.watchreader

import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 主题配置系统（亮色羊皮纸 + 深色 AMOLED 纯黑极省电）
 */

// ── 亮色羊皮纸色系（日间护眼） ──
private val PaperBackground = Color(0xFFF7F4EB)     // 暖调羊皮纸白背景
private val InkPrimary = Color(0xFF181B1F)          // 浓黑墨水字
private val InkSecondary = Color(0xFF3E454D)        // 次要深灰字（高对比度，确保上一章/辅助信息清晰易读）
private val ThemeAccent = Color(0xFF1E6091)         // 经典墨蓝主色
private val ThemeGreen = Color(0xFF2D6A4F)          // 护眼竹青次色
private val CardSurface = Color(0xFFEDE8DC)         // 卡片浅暖色
private val CardVariant = Color(0xFFE2DCCF)         // 按钮浅暖色
private val DividerOutline = Color(0xFFB5ACA0)      // 分隔轮廓线

val WatchColorScheme = lightColorScheme(
    primary = ThemeAccent,
    onPrimary = Color(0xFFFFFFFF),
    secondary = ThemeGreen,
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF7B2CBF),
    background = PaperBackground,
    onBackground = InkPrimary,
    surface = CardSurface,
    onSurface = InkPrimary,
    surfaceVariant = CardVariant,
    onSurfaceVariant = InkSecondary,
    outline = DividerOutline,
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF)
)

// ── 深色 AMOLED 纯黑色系（夜间护眼 + 像素级 0 耗电） ──
private val DarkBackground = Color(0xFF000000)      // 纯黑背景（OLED 像素彻底熄灭）
private val DarkInkPrimary = Color(0xFFE6E6EB)      // 柔和白字
private val DarkInkSecondary = Color(0xFFA2A2AB)    // 次要浅灰字（夜间高清晰度且不刺眼）
private val DarkThemeAccent = Color(0xFF38BDF8)     // 极光天蓝（夜间明朗高可见度）
private val DarkThemeGreen = Color(0xFF4EBA87)      // 护眼青绿
private val DarkCardSurface = Color(0xFF161619)     // 深灰卡片
private val DarkCardVariant = Color(0xFF222228)     // 深灰按钮
private val DarkDividerOutline = Color(0xFF3D3D47)  // 分隔暗线

val WatchDarkColorScheme = darkColorScheme(
    primary = DarkThemeAccent,
    onPrimary = Color(0xFF000000),
    secondary = DarkThemeGreen,
    onSecondary = Color(0xFF000000),
    tertiary = Color(0xFFB388FF),
    background = DarkBackground,
    onBackground = DarkInkPrimary,
    surface = DarkCardSurface,
    onSurface = DarkInkPrimary,
    surfaceVariant = DarkCardVariant,
    onSurfaceVariant = DarkInkSecondary,
    outline = DarkDividerOutline,
    error = Color(0xFFFF5252),
    onError = Color(0xFF000000)
)

val WatchTypography = Typography(
    titleMedium = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 24.sp
    ),
    labelSmall = TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 16.sp
    ),
    labelMedium = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 14.sp
    )
)
