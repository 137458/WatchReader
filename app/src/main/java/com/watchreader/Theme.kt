package com.watchreader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import top.yukonga.miuix.kmp.theme.lightColorScheme

/**
 * 主题令牌系统（羊皮纸 / AMOLED 纯黑 / 深红夜视 / HyperOS）
 *
 * 结构延续 ADR-013：每主题少量命名基色令牌（背景 / 卡片 / 按钮 / 墨字 / 次级墨 /
 * 强调蓝 / 格式绿 / 书签琥珀）→ 全量 miuix [Colors] 角色映射（迁移记录见 ADR-014）：
 * - 背景 surface 层：background(页面) → surface/surfaceContainer(卡片) → surfaceVariant(按钮/衬底) 三档明度；
 * - 文字层：onBackground(正文) → onSurfaceVariantSummary(次级) 两档，禁止临时调透明度造灰字；
 * - 强调层：primary(进度/选中/入口强调) + secondary(格式绿) + tertiaryContainerVariant(书签琥珀)，
 *   每个强调色都绑定单一语义；tertiaryContainerVariant 在 miuix 组件内部零消费，
 *   是本应用书签琥珀文本语义的唯一载体（消费者：章节列表书签原生视图色）。
 *
 * 对比度基线（WCAG 2.1，逐对实测，同 ADR-013）：
 * - 命名令牌本身未变，此前实测的对比度对（正文 ≥ 4.5:1）在新角色映射下原值成立；
 * - HyperOS 档直接采用 miuix darkColorScheme() 官方默认色板（厂商设计基线）；
 * - outline 作为发丝描边按装饰豁免，结构分离由 surface 三档明度与描边共同承担。
 */

// ═══════════════════ 亮色羊皮纸（日间护眼） ═══════════════════
// 基色令牌：暖调纸底 + 浓墨 + 三强调（墨蓝 / 竹青 / 书签琥珀）
private val PaperBackground = Color(0xFFF7F4EB)     // 暖调羊皮纸白背景
private val PaperCard = Color(0xFFEDE8DC)           // 卡片浅暖色
private val PaperButton = Color(0xFFE2DCCF)         // 按钮浅暖色
private val PaperInk = Color(0xFF181B1F)            // 浓黑墨水字（纸底 15.7:1）
private val PaperInkSecondary = Color(0xFF3E454D)   // 次级墨灰字（纸底 8.8:1）
private val PaperAccent = Color(0xFF1A6C9C)         // 晴空墨蓝强调（纸底 5.2:1，白字 5.7:1）
private val PaperGreen = Color(0xFF2D6A4F)          // 护眼竹青（格式语义，纸底 5.8:1）
private val PaperAmber = Color(0xFF984607)          // 书签琥珀（高亮语义，纸底 5.9:1 / 按钮底 4.8:1）

private val WatchColors: Colors = lightColorScheme(
    primary = PaperAccent,
    onPrimary = Color(0xFFFFFFFF),
    primaryVariant = PaperAccent,
    onPrimaryVariant = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD0E7F5),
    onPrimaryContainer = Color(0xFF0E3A54),      // 容器对 9.4:1
    secondary = PaperGreen,
    onSecondary = Color(0xFFFFFFFF),
    secondaryVariant = PaperButton,              // 普通按钮底（miuix ButtonDefaults 常规档）
    onSecondaryVariant = PaperInk,
    secondaryContainer = Color(0xFFD3E8DD),
    onSecondaryContainer = Color(0xFF17402F),    // 容器对 9.0:1
    secondaryContainerVariant = PaperButton,
    onSecondaryContainerVariant = PaperInkSecondary,
    tertiaryContainer = Color(0xFFF5E2C4),
    onTertiaryContainer = Color(0xFF5C3A00),     // 容器对 8.0:1
    tertiaryContainerVariant = PaperAmber,       // 书签琥珀文本语义（本应用唯一消费者：书签高亮）
    background = PaperBackground,
    onBackground = PaperInk,
    onBackgroundVariant = PaperInkSecondary,
    surface = PaperCard,
    onSurface = PaperInk,
    surfaceVariant = PaperButton,
    onSurfaceSecondary = PaperInkSecondary,
    onSurfaceVariantSummary = PaperInkSecondary,
    onSurfaceVariantActions = PaperInkSecondary,
    disabledOnSurface = PaperInkSecondary,
    surfaceContainer = PaperCard,
    onSurfaceContainer = PaperInk,
    onSurfaceContainerVariant = PaperInkSecondary,
    surfaceContainerHigh = PaperButton,
    onSurfaceContainerHigh = PaperInkSecondary,
    surfaceContainerHighest = PaperButton,
    onSurfaceContainerHighest = PaperInk,
    outline = Color(0xFFB5ACA0),                 // 强描边（发丝按装饰豁免）
    dividerLine = Color(0xFFDDD6C7),             // 弱分隔线（预混弱档，替代运行时透明度）
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B)         // 容器对 12.8:1
)

// ═══════════════════ 深色 AMOLED 纯黑（夜间 + 像素级 0 耗电） ═══════════════════
// 背景保持物理纯黑（OLED 熄灭像素），文字用柔白避免纯白刺眼
private val DarkBackground = Color(0xFF000000)      // 纯黑背景（OLED 像素彻底熄灭）
private val DarkCardSurface = Color(0xFF161619)     // 深灰卡片
private val DarkCardVariant = Color(0xFF222228)     // 深灰按钮
private val DarkInkPrimary = Color(0xFFE6E6EB)      // 柔和白字（纯黑底 16.9:1）
private val DarkInkSecondary = Color(0xFFA2A2AB)    // 次级浅灰字（纯黑底 8.3:1 / 按钮底 6.3:1）
private val DarkAccent = Color(0xFF38BDF8)          // 极光天蓝（纯黑底 9.8:1）
private val DarkGreen = Color(0xFF4EBA87)           // 护眼青绿（纯黑底 8.7:1）
private val DarkAmber = Color(0xFFFFB74D)           // 书签琥珀（纯黑底 12.1:1）

private val WatchDarkColors: Colors = darkColorScheme(
    primary = DarkAccent,
    onPrimary = Color(0xFF000000),
    primaryVariant = DarkAccent,
    onPrimaryVariant = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF1B4A66),
    onPrimaryContainer = Color(0xFFB8E2F8),      // 容器对 6.9:1
    secondary = DarkGreen,
    onSecondary = Color(0xFF000000),
    secondaryVariant = DarkCardVariant,
    onSecondaryVariant = DarkInkPrimary,
    secondaryContainer = Color(0xFF1F5138),
    onSecondaryContainer = Color(0xFFB4E3CB),    // 容器对 6.5:1
    secondaryContainerVariant = DarkCardVariant,
    onSecondaryContainerVariant = DarkInkSecondary,
    tertiaryContainer = Color(0xFF5C3A00),
    onTertiaryContainer = Color(0xFFFFD9A0),     // 容器对 7.6:1
    tertiaryContainerVariant = DarkAmber,        // 书签琥珀文本语义
    background = DarkBackground,
    onBackground = DarkInkPrimary,
    onBackgroundVariant = DarkInkSecondary,
    surface = DarkCardSurface,
    onSurface = DarkInkPrimary,
    surfaceVariant = DarkCardVariant,
    onSurfaceSecondary = DarkInkSecondary,
    onSurfaceVariantSummary = DarkInkSecondary,
    onSurfaceVariantActions = DarkInkSecondary,
    disabledOnSurface = DarkInkSecondary,
    surfaceContainer = DarkCardSurface,
    onSurfaceContainer = DarkInkPrimary,
    onSurfaceContainerVariant = DarkInkSecondary,
    surfaceContainerHigh = DarkCardVariant,
    onSurfaceContainerHigh = DarkInkSecondary,
    surfaceContainerHighest = DarkCardVariant,
    onSurfaceContainerHighest = DarkInkPrimary,
    outline = Color(0xFF3D3D47),
    dividerLine = Color(0xFF2A2A31),
    error = Color(0xFFFF5252),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDAD6)         // 容器对 10.1:1
)

// ═══════════════════ 深红夜视（睡前极暗 + OLED 单色子像素省电） ═══════════════════
// 单色纪律：全角色收敛在红-橙同一色相族内——长波红光对视锥/视杆刺激最小，
// 夜间褪黑素分泌受抑制最轻；绿色成功语义在本主题下让位于夜视纪律（结构性例外）。
private val RedNightBackground = Color(0xFF000000)
private val RedNightCardSurface = Color(0xFF0D0303)  // 微红黑底
private val RedNightCardVariant = Color(0xFF1A0606)  // 按钮深红底
private val RedNightInkPrimary = Color(0xFFE57373)   // 柔和珊瑚红正文（纯黑底 7.0:1）
private val RedNightInkSecondary = Color(0xFFBE5B5B) // 次级暗红（纯黑底 4.8:1，夜视下的 AA 下限）
private val RedNightAccent = Color(0xFFFF5252)       // 明亮珊瑚红强调（纯黑底 6.6:1）
private val RedNightGreen = Color(0xFFC86A6A)        // 格式语义（红夜域内取中档，5.7:1）
private val RedNightAmber = Color(0xFFD97848)        // 书签琥珀→暖橙红（纯黑底 6.7:1）

private val WatchRedNightColors: Colors = darkColorScheme(
    primary = RedNightAccent,
    onPrimary = Color(0xFF000000),
    primaryVariant = RedNightAccent,
    onPrimaryVariant = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF5C1414),
    onPrimaryContainer = Color(0xFFFFD9D9),      // 容器对 10.3:1
    secondary = RedNightGreen,
    onSecondary = Color(0xFF000000),
    secondaryVariant = RedNightCardVariant,
    onSecondaryVariant = RedNightInkPrimary,
    secondaryContainer = Color(0xFF4A1212),
    onSecondaryContainer = Color(0xFFFFD9D9),    // 容器对 11.7:1
    secondaryContainerVariant = RedNightCardVariant,
    onSecondaryContainerVariant = RedNightInkSecondary,
    tertiaryContainer = Color(0xFF4A1E12),
    onTertiaryContainer = Color(0xFFFFD8C2),     // 容器对 10.7:1
    tertiaryContainerVariant = RedNightAmber,    // 书签琥珀→暖橙红（夜视纪律下的同族替代）
    background = RedNightBackground,
    onBackground = RedNightInkPrimary,
    onBackgroundVariant = RedNightInkSecondary,
    surface = RedNightCardSurface,
    onSurface = RedNightInkPrimary,
    surfaceVariant = RedNightCardVariant,
    onSurfaceSecondary = RedNightInkSecondary,
    onSurfaceVariantSummary = RedNightInkSecondary,
    onSurfaceVariantActions = RedNightInkSecondary,
    disabledOnSurface = RedNightInkSecondary,
    surfaceContainer = RedNightCardSurface,
    onSurfaceContainer = RedNightInkPrimary,
    onSurfaceContainerVariant = RedNightInkSecondary,
    surfaceContainerHigh = RedNightCardVariant,
    onSurfaceContainerHigh = RedNightInkSecondary,
    surfaceContainerHighest = RedNightCardVariant,
    onSurfaceContainerHighest = RedNightInkPrimary,
    outline = Color(0xFF330B0B),
    dividerLine = Color(0xFF221010),
    error = Color(0xFFFF1744),
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF4A0E12),
    onErrorContainer = Color(0xFFFFD9DE)
)

// ═══════════════════ HyperOS（miuix 官方深色默认色板） ═══════════════════
// 直接采用 miuix darkColorScheme() 原厂默认（HyperOS 深色观感：#242424 底 + 品牌蓝），
// 零定制即零偏移；书签琥珀语义在本档沿用其 tertiaryContainerVariant 默认。
private val MiuixDarkColors: Colors = darkColorScheme()

/** 四套主题共用入口：按 [ThemeMode] 取对应 miuix [Colors]（MainActivity 唯一切换点） */
fun watchColorsOf(mode: ThemeMode): Colors = when (mode) {
    ThemeMode.PARCHMENT -> WatchColors
    ThemeMode.DARK -> WatchDarkColors
    ThemeMode.RED_NIGHT -> WatchRedNightColors
    ThemeMode.MIUIX -> MiuixDarkColors
}

/**
 * 主题无关的功能固定色：语义上必须脱离主题才能成立（可扫描性 / 物理暗化），
 * 集中登记避免散落各页的"影子色"
 */
object WatchFixed {
    /** 寻道 HUD 悬浮胶囊底：所有主题恒黑底（悬浮于正文之上，黑底保证任意正文配色下可读） */
    val HudScrim = Color(0xDD000000)

    /** 二维码面板底与前景：白底深码是扫码引擎的识别前提，严禁随主题反转 */
    val QrPanelBackground = Color(0xFFFFFFFF)
    val QrPanelInk = Color(0xFF444444)
}

/**
 * 手表字号排版（字号档与 ADR-009 时代的 WatchTypography 逐值一致，禁止视觉回归）：
 * 旧 M3 槽位 → miuix 槽位对位表：
 * displaySmall→title1（大数字/强调展示）、titleLarge→title2（页面主标题）、
 * titleMedium→title3（卡片标题）、bodyMedium→body1（正文）、
 * labelLarge→button（胶囊按钮）、labelMedium→footnote1、labelSmall→footnote2；
 * main/paragraph 与 body1 同档（正文收敛 14sp，供 miuix 组件内部默认取用），
 * headline1/body2 为 miuix BasicComponent 标题/摘要档，subtitle 为 SmallTitle 分组标题档。
 */
val WatchTextStyles: TextStyles = defaultTextStyles(
    main = TextStyle(fontSize = 14.sp),
    paragraph = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 24.sp
    ),
    body1 = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 24.sp
    ),
    body2 = TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
    button = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp
    ),
    footnote1 = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 14.sp,
        letterSpacing = 0.2.sp
    ),
    footnote2 = TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp
    ),
    headline1 = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold
    ),
    subtitle = TextStyle(
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold
    ),
    title1 = TextStyle(
        fontSize = 30.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 36.sp,
        letterSpacing = (-0.3).sp
    ),
    title2 = TextStyle(
        fontSize = 19.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 26.sp,
        letterSpacing = 0.1.sp
    ),
    title3 = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp
    )
)
