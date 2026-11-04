package com.watchreader

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计令牌：动效时长 / 缓动、层次 alpha、形状与字号标度
 *
 * 令牌只有一个权威来源才谈得上「令牌」：屏幕消费令牌而不复写数值，改一处全站生效，
 * 不变量也才能被测试锁定（见 MaterialPaletteTest / CleanTypographyTest）。
 */

/** 统一动效令牌：时长、缓动与弹簧参数（对齐 M3 motion tokens + emphasized 曲线） */
object WatchMotion {
    /** M3 medium2：页面级入场 */
    const val DUR_ENTER = 300

    /** M3 short4：页面淡入 */
    const val DUR_FADE = 200

    /** M3 short3：页面淡出 */
    const val DUR_FADE_OUT = 150

    // 微内容切换（数值轮换 / Tab 填充 / 局部淡入淡出）：比页面级淡入淡出更快一档，
    // 全应用所有小面积状态切换统一走这一对时长（M3 short4 / short2），
    // 杜绝各页自定 120/180/190/200 漂移
    const val DUR_SWAP_IN = 200
    const val DUR_SWAP_OUT = 100

    const val STAGGER_STEP_MS = 36L
    const val MAX_STAGGER = 8

    /** M3 emphasized decelerate / accelerate */
    val EnterEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val ExitEasing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    /** 按压回弹：快速沉稳，不过弹 */
    fun <T> pressSpring() = spring<T>(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium)
}

/**
 * 统一低透明度令牌：仅用于纯装饰的微光 / 阴影，不得用于文字或结构线。
 * 结构线与分隔线已升级为 outlineVariant / outline 角色（MD3）。
 */
object WatchAlpha {
    /** 弧形寻道微光与选中描边（装饰豁免） */
    const val ACCENT_BORDER = 0.45f
}

/**
 * 统一圆角体系：卡片 / 行 / 角标映射 MD3 shape scale；Pill 为全弧胶囊（手表触控友好）
 */
object WatchShapes {
    val Card = 16.dp
    val Row = 12.dp
    val Pill = CircleShape
    val Badge = RoundedCornerShape(6.dp)
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
