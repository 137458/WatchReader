package com.watchreader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ListHeader as WearListHeader
import androidx.wear.compose.material3.RadioButton as WearRadioButton
import androidx.wear.compose.material3.Text as WearText

/**
 * 菜单浮层：配色选择。
 *
 * 从 MenuBlocks.kt 分出 —— 浮层的生命周期（进出场、返回键、右滑关闭、表冠寻址）与
 * 设置行骨架无关，唯一共用的只有列表头与排版本体，因此单独一层，
 * 由菜单页的 overlay 槽承载（留在 content 内会被遮罩与操作条压住）。
 */

/**
 * 配色选择浮层单行：Wear RadioButton（官方单选控件）承载选中态与点按，
 * icon 槽放「底色胶囊 + 强调色圆点」色样（所见即所得），标题随选中态过渡强调色。
 * 行热区 ≥48dp
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
    // 标题色随选中态过渡（swap 档），替代硬切换
    val titleColor by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.onSurface,
        animationSpec = tween(WatchMotion.DUR_SWAP_IN, easing = WatchMotion.EnterEasing),
        label = "themeOptionTitle"
    )
    WearRadioButton(
        selected = selected,
        onSelect = {
            tick()
            onSelect()
        },
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        icon = {
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
        },
        label = {
            WearText(
                text = mode.label,
                style = WearType.labelLarge,
                color = titleColor
            )
        }
    )
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
                WearListHeader(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                    contentColor = colors.primary
                ) {
                    WearText(
                        text = "选择配色",
                        style = WearType.titleLarge,
                        color = colors.primary
                    )
                }
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
