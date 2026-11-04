package com.watchreader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * 阅读菜单 — 设计系统统一排版：分区卡片 + 发丝分隔 + 弹簧按压反馈
 *
 * 本文件只做「页」：安全区、滚动、表冠寻址、右滑返回、底部弧形操作条与配色浮层槽位。
 * 设置行骨架与各分区内容在 MenuBlocks.kt，阅读统计在 ReadingStatsWeek.kt。
 */
@Composable
fun MenuScreen(
    chapterTitle: String = "",
    fontSize: Int,
    autoScrollSpeed: Float,
    isAutoScrolling: Boolean,
    appBrightness: Float,
    hasPrevChapter: Boolean,
    hasNextChapter: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onToggleAutoScroll: () -> Unit,
    onAutoScrollSpeedChange: (Float) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenRsvp: () -> Unit,
    onChapterListClick: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onBack: () -> Unit,
    onHome: () -> Unit,
    themeMode: Int = 0,
    onThemeModeChange: (Int) -> Unit = {},
    tapPageArea: Int = 0,
    onTapPageAreaChange: (Int) -> Unit = {},
    cleanTypography: Boolean = true,
    onCleanTypographyChange: (Boolean) -> Unit = {},
    fontType: Int = 0,
    onFontTypeChange: (Int) -> Unit = {},
    readDurationSec: Long = 0L,
    readDays: Map<String, Long> = emptyMap(),
    readGoalMinutes: Int = 0,
    finishedCount: Int = 0,
    onReadGoalChange: (Int) -> Unit = {},
    lineSpacing: Int = LineSpacingMode.STANDARD.value,
    letterSpacing: Int = LetterSpacingMode.STANDARD.value,
    onLineSpacingChange: (Int) -> Unit = {},
    onLetterSpacingChange: (Int) -> Unit = {}
) {
    BackHandler(onBack = onBack)
    val scrollState = rememberScrollState()
    // 主题选择浮层：配色一步直达，替代逐档循环点按
    var showThemePicker by remember { mutableStateOf(false) }

    // 表冠滚动目标注册：Activity 顶层管线直接寻址菜单滚动（连续跟手 + 距离门限微振）
    val context = LocalContext.current
    rememberCrownScrollTarget(scrollState) { delta ->
        CrownScrollHelper.dispatchScroll(delta, scrollState, context)
        true
    }

    // 右滑退出：菜单页右滑回阅读页；配色浮层开启期由浮层自己接管右滑，此处停摆
    val backTick = rememberTickHaptic()
    // 底色由 Window decorView 统一承载，此处不再整屏填充（少一层全屏 overdraw）
    val insets = rememberWatchSafeInsets()
    WatchScreenScaffold(
        swipeEnabled = !showThemePicker,
        onDismiss = {
            backTick()
            onBack()
        },
        fades = WatchScreenFades(top = 48.dp, bottom = 56.dp),
        actionBar = {
            // ── 底部弧形贴边主导航：扇带处处贴合圆屏下缘 ──
            EdgeActionBar(
                EdgeButtonSpec("书架", onClick = onHome),
                EdgeButtonSpec(
                    "继续阅读",
                    emphasis = PillEmphasis.Primary,
                    onClick = onBack
                )
            )
        },
        // 浮层必须以 overlay 槽交给壳：留在 content 内会被遮罩与操作条压住，热区也抢不过操作条
        overlay = {
            ThemePickerOverlay(
                visible = showThemePicker,
                selectedThemeMode = themeMode,
                onSelectThemeMode = onThemeModeChange,
                onDismiss = { showThemePicker = false }
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = insets.horizontal)
                .padding(top = insets.top, bottom = insets.bottom),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            ReadingControlBlock(
                chapterTitle = chapterTitle,
                readDurationSec = readDurationSec,
                hasPrevChapter = hasPrevChapter,
                hasNextChapter = hasNextChapter,
                onPrevChapter = onPrevChapter,
                onNextChapter = onNextChapter,
                onOpenBookmarks = onOpenBookmarks,
                onOpenRsvp = onOpenRsvp,
                onChapterListClick = onChapterListClick,
                onOpenSearch = onOpenSearch
            )
            ScrollSettingsBlock(
                isAutoScrolling = isAutoScrolling,
                autoScrollSpeed = autoScrollSpeed,
                onToggleAutoScroll = onToggleAutoScroll,
                onAutoScrollSpeedChange = onAutoScrollSpeedChange
            )
            DisplaySettingsBlock(
                fontSize = fontSize,
                onFontSizeChange = onFontSizeChange,
                appBrightness = appBrightness,
                onBrightnessChange = onBrightnessChange
            )
            // 档位取模公式与原实现一致：档位值 +1 后对枚举/选项数量取模
            PreferenceSettingsBlock(
                themeMode = themeMode,
                onOpenThemePicker = { showThemePicker = true },
                tapPageArea = tapPageArea,
                onCycleTapPageArea = {
                    onTapPageAreaChange((tapPageArea + 1) % TapPageArea.entries.size)
                },
                cleanTypography = cleanTypography,
                onToggleCleanTypography = { onCleanTypographyChange(!cleanTypography) },
                fontType = fontType,
                onCycleFontType = {
                    onFontTypeChange(
                        if (FontType.fromValue(fontType) == FontType.SERIF) {
                            FontType.SANS_SERIF.value
                        } else {
                            FontType.SERIF.value
                        }
                    )
                },
                lineSpacing = lineSpacing,
                onCycleLineSpacing = {
                    onLineSpacingChange((lineSpacing + 1) % LineSpacingMode.entries.size)
                },
                letterSpacing = letterSpacing,
                onCycleLetterSpacing = {
                    onLetterSpacingChange((letterSpacing + 1) % LetterSpacingMode.entries.size)
                }
            )
            StatsSettingsBlock(
                readDays = readDays,
                finishedCount = finishedCount,
                readGoalMinutes = readGoalMinutes,
                onReadGoalChange = onReadGoalChange
            )
        }
    }
}
