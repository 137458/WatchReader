package com.watchreader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 菜单面：设置行骨架 + 由骨架装配的菜单分区块。
 *
 * 骨架部分（[SettingSection] / [SettingLine] / [CycleLine] / [Stepper] / [ThemeOptionRow] /
 * [AnimatedValue]）只认 MaterialTheme 令牌与 48dp 热区，不知道菜单有哪些项；
 * 分区块（[ReadingControlBlock] / [ScrollSettingsBlock] / [DisplaySettingsBlock] /
 * [PreferenceSettingsBlock] / [ThemePickerOverlay]）是菜单自己的内容，靠骨架把排版细节
 * 沉在下面。页面文件只按顺序调这些块，不再逐行重复「卡片 + 内边距 + 发丝线」。
 */

/**
 * 设置分区骨架：卡片 + 12dp 内边距 + 分区标题 + 10dp 行距。
 *
 * 四个分区此前是同一段 Card{Column{SectionLabel}} 的四份复制，差异只有标题与入场序号；
 * 收成一处后新增分区不再复制排版常量。modifier 走 then 追加，调用点的
 * 「先铺满宽度、再错峰入场」顺序与原实现一致。
 */
@Composable
internal fun SettingSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    SurfaceCard(modifier = Modifier.fillMaxWidth().then(modifier)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionLabel(title)
            content()
        }
    }
}

/**
 * 值切换的交叉淡入：换档时旧值上浮淡出、新值下方淡入，只换文字不重排整行
 */
@Composable
internal fun AnimatedValue(value: String, content: @Composable (String) -> Unit) {
    AnimatedContent(
        targetState = value,
        transitionSpec = {
            (fadeIn(tween(WatchMotion.DUR_SWAP_IN)) + slideInVertically { it / 3 }) togetherWith
                (fadeOut(tween(WatchMotion.DUR_SWAP_OUT)) + slideOutVertically { -it / 3 })
        },
        label = "menu-value"
    ) { current ->
        content(current)
    }
}

/** 设置行：左侧标题 + 主强调色当前值，右侧挂步进器或开关 */
@Composable
internal fun SettingLine(label: String, value: String, trailing: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            AnimatedValue(value) { current ->
                Text(
                    text = current,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Visible
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        trailing()
    }
}

/**
 * 步进器：发丝描边胶囊双键（各自独立按压缩放）；长按 400ms 后 90ms 连发，
 * 免去调字号从默认到目标档的逐档连点
 */
@Composable
internal fun Stepper(onMinus: () -> Unit, onPlus: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = colors.surfaceContainerHigh
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepperKey("−", colors, onMinus)
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(22.dp)
                    .background(colors.outlineVariant)
            )
            StepperKey("+", colors, onPlus)
        }
    }
}

/**
 * 步进键连发策略（由 MenuStepperRepeatTest 锁定）：离散步进只由 clickable 在抬起时派发一次，
 * 本对象只描述「按住超过 [HOLD_DELAY_MS] 之后」的补充连发，因此一次点按恰好一步。
 */
internal object StepperRepeat {
    /** 按住多久开始连发 */
    const val HOLD_DELAY_MS = 400L

    /** 连发间隔 */
    const val INTERVAL_MS = 90L

    /** 按住 [holdMs] 毫秒时，除首步之外应补充的连发次数 */
    fun repeatCount(holdMs: Long): Int =
        if (holdMs < HOLD_DELAY_MS) 0 else 1 + ((holdMs - HOLD_DELAY_MS) / INTERVAL_MS).toInt()
}

@Composable
private fun StepperKey(symbol: String, colors: ColorScheme, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // 首步交给 clickable 的抬起回调；此处只补「按住不放」的连发。
    // 旧实现在按下瞬间就 onClick() 一次，与 clickable 抬起那一次叠加 —— 一次点按步进两步
    LaunchedEffect(pressed) {
        if (!pressed) return@LaunchedEffect
        delay(StepperRepeat.HOLD_DELAY_MS)
        while (true) {
            onClick()
            delay(StepperRepeat.INTERVAL_MS)
        }
    }
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .pressScale(interaction, pressedScale = 0.9f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = colors.primary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 循环切换行：整行可点（热区 ≥48dp），右侧当前值 + 右箭头；
 * 点按只负责「下一档」，档位合法性由调用方决定
 */
@Composable
internal fun CycleLine(label: String, value: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val tick = rememberTickHaptic()
    // 行热区垂直内边距：13sp 文本裸行仅 ~18dp，密集相邻行腕上必误触邻行
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressScale(interaction, pressedScale = 0.985f)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button
            ) {
                tick()
                onClick()
            }
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.onSurface)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedValue(value) { current ->
                Text(current, style = MaterialTheme.typography.labelMedium, color = colors.primary)
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * 配色选择浮层单行：主题底色胶囊内嵌强调色圆点作色样（所见即所得），
 * 选中行强调描边 + 高亮文字；行热区 ≥48dp
 */
@Composable
internal fun ThemeOptionRow(
    mode: ThemeMode,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val scheme = watchColorSchemeOf(mode)
    val tick = rememberTickHaptic()
    // 选中描边与标题色随选中态过渡（swap 档），替代硬切换；
    // 未选中时描边透明化而非移除，BorderStroke 宽度恒定避免布局参数跳变
    val borderColor by animateColorAsState(
        targetValue = if (selected) colors.primary else Color.Transparent,
        animationSpec = tween(WatchMotion.DUR_SWAP_IN, easing = WatchMotion.EnterEasing),
        label = "themeOptionBorder"
    )
    val titleColor by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.onSurface,
        animationSpec = tween(WatchMotion.DUR_SWAP_IN, easing = WatchMotion.EnterEasing),
        label = "themeOptionTitle"
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(
                selected = selected,
                onClick = {
                    tick()
                    onSelect()
                },
                role = Role.RadioButton
            ),
        shape = RoundedCornerShape(WatchShapes.Row),
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.5.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 色样：底色胶囊 + 强调色圆点
            Box(
                modifier = Modifier
                    .size(width = 34.dp, height = 22.dp)
                    .clip(CircleShape)
                    .background(scheme.background)
                    .border(1.dp, colors.outline, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(scheme.primary)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = mode.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = titleColor
            )
            Spacer(modifier = Modifier.weight(1f))
            RadioButton(selected = selected, onClick = null)
        }
    }
}

/**
 * 阅读控制块：当前章节主卡 + 章节导航 + 2×2 快捷操作。
 * 入场错峰序号 0~3 由本块自己持有，页面只保证块的先后顺序
 */
@Composable
internal fun ReadingControlBlock(
    chapterTitle: String,
    readDurationSec: Long,
    hasPrevChapter: Boolean,
    hasNextChapter: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenRsvp: () -> Unit,
    onChapterListClick: () -> Unit,
    onOpenSearch: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    SectionLabel("阅读控制", modifier = Modifier.staggeredEnter(0), color = colors.primary)

    // ── 当前章节主卡（MD3 primaryContainer 强调卡） ──
    Card(
        modifier = Modifier
            .staggeredEnter(1)
            .fillMaxWidth(),
        shape = RoundedCornerShape(WatchShapes.Card),
        colors = CardDefaults.cardColors(containerColor = colors.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            AnimatedValue(chapterTitle.ifBlank { "当前章节" }) { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.onPrimaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = ReadDurationFormatter.format(readDurationSec),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onPrimaryContainer
            )
        }
    }

    // ── 章节导航 ──
    if (hasPrevChapter || hasNextChapter) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .staggeredEnter(2),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (hasPrevChapter) PillButton("上一章", Modifier.weight(1f), onClick = onPrevChapter)
            if (hasNextChapter) PillButton("下一章", Modifier.weight(1f), onClick = onNextChapter)
        }
    }

    // ── 快捷操作（2×2 网格：每键约 88×48dp，满足 Google 48dp 触控基线） ──
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .staggeredEnter(3),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("书签", Modifier.weight(1f), verticalPadding = 10.dp, onClick = onOpenBookmarks)
            PillButton("速读", Modifier.weight(1f), verticalPadding = 10.dp, onClick = onOpenRsvp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("目录", Modifier.weight(1f), verticalPadding = 10.dp, onClick = onChapterListClick)
            PillButton("搜索", Modifier.weight(1f), verticalPadding = 10.dp, onClick = onOpenSearch)
        }
    }
}

/** 滚动分区：自动滚屏开关 + 速度档 */
@Composable
internal fun ScrollSettingsBlock(
    isAutoScrolling: Boolean,
    autoScrollSpeed: Float,
    onToggleAutoScroll: () -> Unit,
    onAutoScrollSpeedChange: (Float) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    SettingSection("滚动", modifier = Modifier.staggeredEnter(4)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                AnimatedValue(if (isAutoScrolling) "自动滚屏中" else "自动滚屏") { label ->
                    Text(label, color = colors.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                AnimatedValue("${autoScrollSpeed.toInt()} px/s") { speed ->
                    Text(speed, color = colors.primary, fontSize = 11.sp)
                }
            }
            PillButton(
                if (isAutoScrolling) "暂停" else "开启",
                active = isAutoScrolling,
                verticalPadding = 8.dp,
                onClick = onToggleAutoScroll
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("减速", Modifier.weight(1f), verticalPadding = 8.dp) {
                onAutoScrollSpeedChange(AutoScrollSpeed.clamp(autoScrollSpeed - 10f))
            }
            PillButton("加速", Modifier.weight(1f), verticalPadding = 8.dp) {
                onAutoScrollSpeedChange(AutoScrollSpeed.clamp(autoScrollSpeed + 10f))
            }
        }
    }
}

/** 显示分区：字号步进、亮度步进与四档亮度预设 */
@Composable
internal fun DisplaySettingsBlock(
    fontSize: Int,
    onFontSizeChange: (Int) -> Unit,
    appBrightness: Float,
    onBrightnessChange: (Float) -> Unit
) {
    SettingSection("显示", modifier = Modifier.staggeredEnter(5)) {
        SettingLine("字号", "$fontSize") {
            Stepper(
                onMinus = { onFontSizeChange(fontSize - 1) },
                onPlus = { onFontSizeChange(fontSize + 1) }
            )
        }
        HairlineDivider()
        SettingLine("亮度", BrightnessManager.formatBrightnessText(appBrightness)) {
            Stepper(
                onMinus = {
                    val current = if (appBrightness < 0f) BrightnessManager.SYSTEM_STEP_BASE else appBrightness
                    onBrightnessChange((current - 0.05f).coerceIn(0.01f, 1f))
                },
                onPlus = {
                    val current = if (appBrightness < 0f) BrightnessManager.SYSTEM_STEP_BASE else appBrightness
                    onBrightnessChange((current + 0.05f).coerceIn(0.01f, 1f))
                }
            )
        }
        HairlineDivider()
        // 亮度预设 2×2 网格：避免四键并排把单键压到 48dp 以下
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("系统", Modifier.weight(1f), verticalPadding = 8.dp) { onBrightnessChange(BrightnessManager.BRIGHTNESS_SYSTEM_DEFAULT) }
                PillButton("暗", Modifier.weight(1f), verticalPadding = 8.dp) { onBrightnessChange(BrightnessManager.LEVEL_ULTRA_DARK) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("中", Modifier.weight(1f), verticalPadding = 8.dp) { onBrightnessChange(BrightnessManager.LEVEL_2_MEDIUM) }
                PillButton("亮", Modifier.weight(1f), verticalPadding = 8.dp) { onBrightnessChange(BrightnessManager.LEVEL_3_STRONG) }
            }
        }
    }
}

/** 偏好分区：主题（开浮层）/ 点按翻页 / 排版净化 / 字体 / 行距 / 字距的循环档位 */
@Composable
internal fun PreferenceSettingsBlock(
    themeMode: Int,
    onOpenThemePicker: () -> Unit,
    tapPageArea: Int,
    onCycleTapPageArea: () -> Unit,
    cleanTypography: Boolean,
    onToggleCleanTypography: () -> Unit,
    fontType: Int,
    onCycleFontType: () -> Unit,
    lineSpacing: Int,
    onCycleLineSpacing: () -> Unit,
    letterSpacing: Int,
    onCycleLetterSpacing: () -> Unit
) {
    val themeLabel = ThemeMode.fromValue(themeMode).label
    val tapLabel = when (TapPageArea.fromValue(tapPageArea)) {
        TapPageArea.TOP_BOTTOM -> "上下点按"
        TapPageArea.LEFT_RIGHT -> "左右点按"
        TapPageArea.DISABLED -> "已关闭"
    }
    val fontLabel = if (FontType.fromValue(fontType) == FontType.SERIF) "宋体" else "黑体"
    SettingSection("偏好", modifier = Modifier.staggeredEnter(6)) {
        CycleLine("主题", themeLabel) { onOpenThemePicker() }
        HairlineDivider()
        CycleLine("点按翻页", tapLabel) { onCycleTapPageArea() }
        HairlineDivider()
        CycleLine("排版净化", if (cleanTypography) "开启" else "关闭") {
            onToggleCleanTypography()
        }
        HairlineDivider()
        CycleLine("字体", fontLabel) { onCycleFontType() }
        HairlineDivider()
        CycleLine("行距", LineSpacingMode.fromValue(lineSpacing).label) { onCycleLineSpacing() }
        HairlineDivider()
        CycleLine("字距", LetterSpacingMode.fromValue(letterSpacing).label) { onCycleLetterSpacing() }
    }
}

/**
 * 配色选择浮层：全屏单选列表（底色 + 强调色色样直达观感），点击即换并关闭；
 * 进出场走 scale + fade（微内容时长档），右滑则内容跟手滑出后关闭；
 * 返回键先关浮层；浮层期表冠滚动注册抢占，直接寻址浮层列表。
 *
 * 由壳的 overlay 槽承载：留在 content 内会被遮罩与操作条压住，热区也抢不过操作条。
 */
@Composable
internal fun ThemePickerOverlay(
    visible: Boolean,
    selectedThemeMode: Int,
    onSelectThemeMode: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = WatchOverlay.Enter,
        exit = WatchOverlay.Exit,
    ) {
        val colors = MaterialTheme.colorScheme
        val pickerScroll = rememberScrollState()
        val pickerCrownContext = LocalContext.current
        rememberCrownScrollTarget(pickerScroll) { delta ->
            CrownScrollHelper.dispatchScroll(delta, pickerScroll, pickerCrownContext)
            true
        }
        val pickerTick = rememberTickHaptic()
        BackHandler(onBack = onDismiss)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                // 右滑关浮层：内容跟手平移并淡出，越过门限滑出后关闭，竖向滚动仍归列表
                .swipeDismissBack {
                    pickerTick()
                    onDismiss()
                }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(pickerScroll)
                    .padding(horizontal = 24.dp, vertical = 52.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SectionLabel("选择配色", color = colors.primary)
                ThemeMode.entries.forEach { mode ->
                    ThemeOptionRow(
                        mode = mode,
                        selected = mode.value == selectedThemeMode,
                        onSelect = {
                            onSelectThemeMode(mode.value)
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}
