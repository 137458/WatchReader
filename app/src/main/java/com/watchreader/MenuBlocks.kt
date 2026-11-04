package com.watchreader

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 菜单骨架：卡片 / 设置行 / 步进器 / 循环行这套排版词汇。
 *
 * 骨架只认 MaterialTheme 令牌与 48dp 热区，不知道菜单有哪些项：[SettingSection] 定卡片与
 * 分区标题，[SettingLine] 定「标题 + 强调值 + 尾部控件」，[CycleLine] 定整行可点的循环档，
 * [Stepper] 定双键步进与长按连发，[AnimatedValue] 定换档时的交叉淡入。
 *
 * 因此菜单自己的内容都在别处分层：阅读控制与三段设置分区在 MenuSections.kt，
 * 配色浮层在 MenuOverlays.kt，阅读统计在 ReadingStatsWeek.kt —— 它们只按顺序调骨架，
 * 不再逐行重复「卡片 + 内边距 + 发丝线」。
 */

/**
 * 设置分区骨架：卡片 + 12dp 内边距 + 分区标题 + 10dp 行距。
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
