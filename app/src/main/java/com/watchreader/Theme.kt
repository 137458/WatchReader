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
 * 主题令牌系统（羊皮纸 / AMOLED 纯黑 / 深红夜视 / 深空蓝 / 晴空蓝）
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
 * - HyperOS 深浅两档为全量自持色板（53 角色逐具名声明，不依赖 miuix 出厂缺省），
 *   中性表面 + 语义彩色 + 正确层次方向三条纪律详见该档块注释与 ADR-015；
 * - outline 作为发丝描边按装饰豁免，结构分离由 surface 三档明度与描边共同承担。
 *
 * 验收：HyperOS 两档的全部角色不透明性、表面层次方向、逐对对比度与语义三色色相分离
 * 由 HyperOsPaletteTest 在 JVM 侧断言（ADR-013 的"逐对实测"自此可执行，不再只活在注释里）。
 *
 * miuix 组件默认取色纪律：miuix TextField 容器默认取 secondaryContainer（本应用
 * 将其映射为格式绿容器语义，作输入面会串色），全部 TextField 调用点必须显式传
 * textFieldColors(backgroundColor=surfaceVariant, labelColor=onSurfaceVariantSummary,
 * borderColor=primary)。
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
private val RedNightInkSecondary = Color(0xFFC46363) // 次级暗红（纯黑底 5.3:1 / 按钮底 4.9:1，夜视下仍达 AA）
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

// ═══════════════════ 深空蓝 / 晴空蓝（HyperOS 亮暗双档，全量自持色板） ═══════════════════
// 显示名按配色命名（ThemeMode.label）：深空蓝 = 深灰底蓝强调，晴空蓝 = 白底蓝强调。
// 这两档是全站唯一「按 HyperOS 原样建模」的主题（决策全文见 ADR-015），三条重做纪律：
// 1. 全量自持：53 角色逐具名声明，不再依赖 lightColorScheme()/darkColorScheme() 缺省。
//    缺省值不认识本应用的语义与层次，凡漏填的角色当场泄漏——旧实现即泄漏了暗档
//    onBackground(#E6FFFFFF, alpha)、primaryContainer/secondaryContainer 弱配对
//    （容器内文字 2.37~3.39:1）与 error(#F12522 对页面 3.72:1)；
// 2. HyperOS 中性纪律：背景 / 卡片 / 按钮 / 分隔线 / 描边一律近中性灰（彩度 ≤ 14/255），
//    彩色只出现在语义角色上——这是 HyperOS 与 MD3 tonal（表面自带主色相）的根本区别；
// 3. 表面层次方向：暗档 页面 < 卡片 < 按钮 < 浮层（出厂 surface 为纯黑，比页面还暗，是倒挂）；
//    亮档 卡片（纯白）> 页面（浅灰）> 按钮衬底 > 浮层，与真机 HyperOS 设置页同构。
//
// 语义契约与另三档完全一致（同一套消费者，禁止按档分叉取色）：primary=品牌蓝强调，
// secondary=格式绿前景，secondaryContainer/onSecondaryContainer=TXT 角标容器对，
// tertiaryContainerVariant=书签琥珀前景。
//
// miuix 0.9.4 组件内部取色考据（AAR 字节码常量池）：TextFieldDefaults 读
// secondaryContainer/onSecondaryContainer/primary，ProgressIndicatorDefaults 读
// primary/secondaryContainer/disabledPrimarySlider，Switch/CheckboxDefaults 读 secondary，
// Dropdown/SpinnerDefaults 读 tertiaryContainer，Divider/Text/squircle 系列不取色。
// 故 secondaryContainer 可安全承载绿容器语义——前提是文件头那条纪律：
// 全部 TextField 与进度条调用点必须显式传色，输入面与轨道才会保持中性。
//
// 品牌蓝与格式绿均沿同色相做明度校准（出厂 #3482FF / #277AF7 对 12sp 章名与按钮字只有
// 3.3~3.7:1）：亮 #2460C4 配白字 / 暗 #69B3FF 配深墨字，两档在全部表面 ≥4.5:1。
// 全部对比度对由 HyperOsPaletteTest 逐对断言（不再只活在注释里）。
private val HyperOsDarkColors: Colors = darkColorScheme(
    // 品牌蓝（出厂 #277AF7 对页面仅 3.72，同相提亮）：页面 7.02 卡片 6.12 按钮 5.28
    primary = Color(0xFF69B3FF),
    onPrimary = Color(0xFF001828),                  // 入口卡深墨字，配主色 8.17:1
    primaryVariant = Color(0xFF277AF7),             // HyperOS 原厂强调蓝，供 Card 强调档
    onPrimaryVariant = Color(0xFFCDE6FF),
    primaryContainer = Color(0xFF1C4B7C),
    onPrimaryContainer = Color(0xFFBFE0FF),         // 容器对 6.53:1
    disabledPrimary = Color(0xFF253E64),
    disabledOnPrimary = Color(0xFF677993),
    disabledPrimaryButton = Color(0xFF2A3F5E),
    disabledOnPrimaryButton = Color(0xFF677893),
    disabledPrimarySlider = Color(0xFF44587C),
    // 格式绿：页面 6.88 卡片 6.00 按钮 5.18
    secondary = Color(0xFF55C08D),
    onSecondary = Color(0xFF00231A),
    secondaryVariant = Color(0xFF38383C),
    onSecondaryVariant = Color(0xFFEDEDF0),
    secondaryContainer = Color(0xFF1E4B36),         // TXT 角标容器对（绿容器语义）
    onSecondaryContainer = Color(0xFFAFE7CC),       // 容器对 7.15:1
    secondaryContainerVariant = Color(0xFF3E3E43),  // 中性容器 variant（HyperOS 灰）
    onSecondaryContainerVariant = Color(0xFFB0B2B8),
    disabledSecondary = Color(0xFF2E2E32),
    disabledOnSecondary = Color(0xFF6C6E74),
    disabledSecondaryVariant = Color(0xFF333337),
    disabledOnSecondaryVariant = Color(0xFF6C6E74),
    tertiaryContainer = Color(0xFF2B3B54),          // miuix Dropdown/Spinner 容器，保留出厂蓝调
    onTertiaryContainer = Color(0xFFB4D6FF),        // 容器对 7.54:1
    tertiaryContainerVariant = Color(0xFFFFB74D),   // 书签琥珀文本语义（页面 8.97 卡片 7.82 按钮 6.74）
    // 页面 #242424 → 卡片 → 按钮 → 浮层逐档提亮（修掉出厂 surface 纯黑的层次倒挂）
    background = Color(0xFF242424),
    onBackground = Color(0xFFEDEDF0),               // 正文柔白 13.29:1（出厂为 alpha 值 #E6FFFFFF）
    onBackgroundVariant = Color(0xFF9FA6C2),        // 6.44:1
    surface = Color(0xFF2E2E31),
    onSurface = Color(0xFFEDEDF0),                  // 卡片 11.59:1
    surfaceVariant = Color(0xFF38383C),
    onSurfaceSecondary = Color(0xFFB9BAC0),         // 卡片 6.99 按钮 6.03
    onSurfaceVariantSummary = Color(0xFFB0B2B8),    // 次级墨：六档表面 4.68~7.32 全部达 AA
    onSurfaceVariantActions = Color(0xFFB0B2B8),
    disabledOnSurface = Color(0xFF6C6E74),
    surfaceContainer = Color(0xFF2E2E31),
    onSurfaceContainer = Color(0xFFEDEDF0),
    onSurfaceContainerVariant = Color(0xFFB0B2B8),
    surfaceContainerHigh = Color(0xFF38383C),
    onSurfaceContainerHigh = Color(0xFFB9BAC0),
    surfaceContainerHighest = Color(0xFF3E3E43),
    onSurfaceContainerHighest = Color(0xFFEDEDF0),
    outline = Color(0xFF4A4A50),
    dividerLine = Color(0xFF3A3A3F),
    error = Color(0xFFFF7A6E),                      // 出厂 #F12522 对页面 3.72，提亮至 6.11
    onError = Color(0xFF3B0001),
    errorContainer = Color(0xFF6B1D16),
    onErrorContainer = Color(0xFFFFDAD6),           // 容器对 8.97:1
    windowDimming = Color(0x99000000),              // 系统遮罩：两档唯一允许带 alpha 的角色
    sliderKeyPoint = Color(0xFF3E5C80),             // 出厂 #4D7A8AA6 为 alpha 值，预混为不透明
    sliderKeyPointForeground = Color(0xFF5DAAFF),
    sliderBackground = Color(0xFF454549),           // 出厂 #26FFFFFF 预混为不透明
)
private val HyperOsLightColors: Colors = lightColorScheme(
    // 品牌蓝（出厂 #3482FF 对卡片仅 3.34，同相压暗）：页面 5.29 卡片 5.92 按钮 5.02，配白字 5.92
    primary = Color(0xFF2460C4),
    onPrimary = Color(0xFFFFFFFF),
    primaryVariant = Color(0xFF3482FF),             // HyperOS 原厂强调蓝，供 Card 强调档
    onPrimaryVariant = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E7FB),
    onPrimaryContainer = Color(0xFF0E3A66),         // 容器对 9.19:1
    disabledPrimary = Color(0xFFC9DEFB),
    disabledOnPrimary = Color(0xFFF5F8FD),
    disabledPrimaryButton = Color(0xFFD8E6F8),
    disabledOnPrimaryButton = Color(0xFFFFFFFF),
    disabledPrimarySlider = Color(0xFFB8CFF5),
    // 格式绿（TXT 徽章文字 / 成功提示）：页面 5.34 卡片 5.98 按钮 5.07
    secondary = Color(0xFF0E7246),
    onSecondary = Color(0xFFFFFFFF),
    secondaryVariant = Color(0xFFECECEF),           // 普通按钮底（miuix ButtonDefaults 常规档）
    onSecondaryVariant = Color(0xFF17181A),
    secondaryContainer = Color(0xFFCFE7DB),         // TXT 角标容器对（绿容器语义）
    onSecondaryContainer = Color(0xFF14432C),       // 容器对 8.62:1
    secondaryContainerVariant = Color(0xFFE4E5E8),  // 中性容器 variant（HyperOS 灰）
    onSecondaryContainerVariant = Color(0xFF5F6368),
    disabledSecondary = Color(0xFFF1F1F3),
    disabledOnSecondary = Color(0xFFFFFFFF),
    disabledSecondaryVariant = Color(0xFFF2F2F4),
    disabledOnSecondaryVariant = Color(0xFFB6B9BF),
    tertiaryContainer = Color(0xFFEAF2FF),          // miuix Dropdown/Spinner 容器，保留出厂蓝调
    onTertiaryContainer = Color(0xFF1A5CBD),        // 容器对 4.55:1
    tertiaryContainerVariant = Color(0xFF8A4B08),   // 书签琥珀文本语义（页面 6.07 卡片 6.79 按钮 5.76）
    // 卡片（纯白）> 页面（浅灰）> 按钮衬底 > 浮层（HyperOS 亮档层次方向，出厂为页面纯白卡片浅灰的倒挂）
    background = Color(0xFFF2F2F3),
    onBackground = Color(0xFF17181A),               // 正文浓墨 15.88:1
    onBackgroundVariant = Color(0xFF5A5F66),        // 5.75:1
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF17181A),                  // 卡片 17.77:1
    surfaceVariant = Color(0xFFECECEF),
    onSurfaceSecondary = Color(0xFF4B4E53),         // 卡片 8.35 按钮 7.09
    onSurfaceVariantSummary = Color(0xFF5F6368),    // 次级墨：六档表面 4.59~6.05 全部达 AA
    onSurfaceVariantActions = Color(0xFF5F6368),
    disabledOnSurface = Color(0xFF979AA1),
    surfaceContainer = Color(0xFFFFFFFF),
    onSurfaceContainer = Color(0xFF17181A),
    onSurfaceContainerVariant = Color(0xFF5F6368),
    surfaceContainerHigh = Color(0xFFE6E7EA),
    onSurfaceContainerHigh = Color(0xFF4B4E53),
    surfaceContainerHighest = Color(0xFFDFE0E4),
    onSurfaceContainerHighest = Color(0xFF17181A),
    outline = Color(0xFFC9CBD1),                    // 强描边（发丝按装饰豁免，与卡片有明度步长）
    dividerLine = Color(0xFFE3E4E8),                // 弱分隔线（预混弱档，替代运行时透明度）
    error = Color(0xFFC0331F),                      // 出厂 #E94634 对页面仅 3.60，校准至 5.03
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFAE3DF),
    onErrorContainer = Color(0xFF6B0D07),           // 容器对 10.15:1
    windowDimming = Color(0x4D000000),              // 系统遮罩：两档唯一允许带 alpha 的角色
    sliderKeyPoint = Color(0xFFA9C4E8),             // 出厂 #4DA3B3CD 为 alpha 值，预混为不透明
    sliderKeyPointForeground = Color(0xFF6EB5FF),
    sliderBackground = Color(0xFFE5E6E8),           // 出厂 #0F000000 预混为不透明
)

/** 五套主题共用入口：按 [ThemeMode] 取对应 miuix [Colors]（MainActivity 唯一切换点） */
fun watchColorsOf(mode: ThemeMode): Colors = when (mode) {
    ThemeMode.PARCHMENT -> WatchColors
    ThemeMode.DARK -> WatchDarkColors
    ThemeMode.RED_NIGHT -> WatchRedNightColors
    ThemeMode.MIUIX -> HyperOsDarkColors
    ThemeMode.MIUIX_LIGHT -> HyperOsLightColors
}

/**
 * 主题无关的功能固定色：语义上必须脱离主题才能成立（可扫描性 / 物理暗化），
 * 集中登记避免散落各页的"影子色"
 */
object WatchFixed {
    /** 寻道 HUD 悬浮胶囊底：所有主题恒黑底（悬浮于正文之上，黑底保证任意正文配色下可读） */
    val HudScrim = Color(0xDD000000)

    /** 寻道 HUD 悬浮文字：恒黑底配固定柔白字（主题 primary 为纸底设计，羊皮纸档黑底上仅 2.8:1） */
    val HudInk = Color(0xFFF0EFEC)

    /** 二维码面板底与前景：白底深码是扫码引擎的识别前提，严禁随主题反转 */
    val QrPanelBackground = Color(0xFFFFFFFF)
    val QrPanelInk = Color(0xFF444444)

    /** 二维码码点模块两色（QrCodeGenerator 矩阵绘制）：与面板同源的扫码引擎前置条件 */
    val QrModuleInk = Color(0xFF000000)
    val QrModulePaper = Color(0xFFFFFFFF)
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
