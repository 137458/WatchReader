package com.watchreader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Card as WearCard
import androidx.wear.compose.material3.CardDefaults as WearCardDefaults
import androidx.wear.compose.material3.Icon as WearIcon
import androidx.wear.compose.material3.ListSubHeader as WearListSubHeader
import androidx.wear.compose.material3.SliderDefaults as WearSliderDefaults
import androidx.wear.compose.material3.Stepper as WearStepper
import androidx.wear.compose.material3.StepperDefaults as WearStepperDefaults
import androidx.wear.compose.material3.Text as WearText

/**
 * 菜单骨架：卡片 / 设置行 / 步进器 / 循环行这套排版词汇。
 *
 * 骨架只认 MaterialTheme 令牌与 48dp 热区，不知道菜单有哪些项：[SettingSection] 定卡片与
 * 分区标题，[SettingLine] 定「标题 + 强调值 + 尾部控件」，[CycleLine] 定整行可点的循环档，
 * [Stepper] 定双键步进，[AnimatedValue] 定换档时的交叉淡入。
 *
 * 组件口径（ADR-021）：卡片、列表头、步进器、图标、文本一律走 Wear Compose Material 3
 * 官方组件（Card / ListSubHeader / Stepper / Icon / Text），与书架页保持同一套视觉体系。
 *
 * 因此菜单自己的内容都在别处分层：阅读控制与三段设置分区在 MenuSections.kt，
 * 配色浮层在 MenuOverlays.kt，阅读统计在 ReadingStatsWeek.kt —— 它们只按顺序调骨架，
 * 不再逐行重复「卡片 + 内边距 + 发丝线」。
 */

/**
 * 设置分区骨架：Wear Card + Wear ListSubHeader 标题 + 行距。
 *
 * 四个设置分区与统计分区此前是同一段 Card{Column{SectionLabel}} 的五份复制，差异只有标题
 * 与入场序号；收成一处后新增分区不再复制排版常量。modifier 走 then 追加，调用点的
 * 「先铺满宽度、再错峰入场」顺序与原实现一致。
 */
@Composable
internal fun SettingSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = MaterialTheme.colorScheme
    WearCard(
        modifier = Modifier.fillMaxWidth().then(modifier),
        shape = RoundedCornerShape(WatchShapes.Card),
        colors = WearCardDefaults.cardColors(
            containerColor = colors.surfaceContainer,
            contentColor = colors.onSurface
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) {
        WearListSubHeader(
            modifier = Modifier.fillMaxWidth(),
            contentColor = colors.primary,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
        ) {
            WearText(
                text = title,
                style = WearType.titleSmall,
                color = colors.primary
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            WearText(
                text = label,
                style = WearType.bodyMedium,
                color = colors.onSurface
            )
            AnimatedValue(value) { current ->
                WearText(
                    text = current,
                    style = WearType.labelMedium,
                    color = colors.primary,
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
 * 整数步进器：Wear M3 官方 Stepper（双键 + 中央数值槽），离散档位走 IntProgression。
 *
 * 官方 Stepper 自带长按连发（按住 400ms 后按固定节奏推进），与原先手写双键的行为一致，
 * 且按钮尺寸（60x48dp）、按压回弹与禁用态都由官方实现负责。
 */
@Composable
internal fun Stepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    valueProgression: IntProgression,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    WearStepper(
        value = value,
        onValueChange = onValueChange,
        valueProgression = valueProgression,
        modifier = modifier,
        decreaseIcon = { WearSliderDefaults.DecreaseIcon() },
        increaseIcon = { WearSliderDefaults.IncreaseIcon() },
        colors = WearStepperDefaults.colors(
            contentColor = colors.onSurface,
            buttonContainerColor = colors.surfaceContainerHigh,
            buttonIconColor = colors.primary
        )
    ) {
        WearText(
            text = "$value",
            style = WearType.labelLarge,
            color = colors.onSurface
        )
    }
}

/**
 * 浮点步进器：Wear M3 官方 Stepper 的 Float 版，值域与分段数由调用方给定。
 */
@Composable
internal fun Stepper(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    modifier: Modifier = Modifier,
    valueLabel: String? = null
) {
    val colors = MaterialTheme.colorScheme
    WearStepper(
        value = value,
        onValueChange = onValueChange,
        steps = steps,
        valueRange = valueRange,
        modifier = modifier,
        decreaseIcon = { WearSliderDefaults.DecreaseIcon() },
        increaseIcon = { WearSliderDefaults.IncreaseIcon() },
        colors = WearStepperDefaults.colors(
            contentColor = colors.onSurface,
            buttonContainerColor = colors.surfaceContainerHigh,
            buttonIconColor = colors.primary
        )
    ) {
        WearText(
            text = valueLabel ?: value.toInt().toString(),
            style = WearType.labelLarge,
            color = colors.onSurface
        )
    }
}

/**
 * 步进键连发策略（由 MenuStepperRepeatTest 锁定）：离散步进只由 clickable 在抬起时派发一次，
 * 本对象只描述「按住超过 [HOLD_DELAY_MS] 之后」的补充连发，因此一次点按恰好一步。
 *
 * 现由 Wear 官方 Stepper 承担长按连发，本对象保留为连发时序的公开口径（测试锁定其常量）。
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

/**
 * 循环切换行：整行可点（热区 ≥48dp），右侧当前值 + 右箭头；
 * 点按只负责「下一档」，档位合法性由调用方决定
 */
@Composable
internal fun CycleLine(label: String, value: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val tick = rememberTickHaptic()
    // 行热区垂直内边距：12sp 文本裸行仅 ~18dp，密集相邻行腕上必误触邻行
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
        WearText(
            text = label,
            style = WearType.bodyMedium,
            color = colors.onSurface
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedValue(value) { current ->
                WearText(
                    text = current,
                    style = WearType.labelMedium,
                    color = colors.primary
                )
            }
            Spacer(Modifier.width(4.dp))
            WearIcon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
