package com.watchreader

/**
 * 可交互控件与状态指示基元：胶囊按钮、进度轨、加载环、角标、状态点
 *
 * 全部只读 MaterialTheme 令牌与 WatchShapes/WatchMotion，不感知页面；
 * 容器与装饰基元（卡片、浮层进出场、分区标签、发丝分隔、错峰入场、边缘羽化）在 WatchComponents.kt。
 */

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 胶囊按钮三档强调级共用的容器 / 内容色契约 */
@Composable
internal fun pillColors(
    emphasis: PillEmphasis,
    active: Boolean,
    enabled: Boolean
): Pair<Color, Color> {
    val colors = MaterialTheme.colorScheme
    val isFilled = emphasis == PillEmphasis.Primary || active
    val container = when {
        !enabled -> colors.surfaceContainer
        isFilled -> colors.primary
        emphasis == PillEmphasis.Outline -> Color.Transparent
        else -> colors.surfaceContainerHigh
    }
    val content = when {
        !enabled -> colors.onSurfaceVariant
        isFilled -> colors.onPrimary
        emphasis == PillEmphasis.Outline -> colors.primary
        else -> colors.onSurface
    }
    return container to content
}

/**
 * 胶囊按钮：primary（filled）/ tonal（surfaceContainerHigh）/ outline（描边）三种强调级。
 *
 * 不直接套 `Button` / 可点击 `Surface`：两者都会施加 Material 3 的最小交互尺寸
 * （Button 内容层 58dp、Surface 48dp），而圆屏最窄弦宽下四键并排的可用宽度只有约 185dp，
 * 必然溢出并被圆屏弧边裁切。本实现保留 M3 色彩角色、胶囊 shape、按压回弹与触觉反馈，
 * 最小触控高度 48dp（Google 无障碍基线），宽度完全服从父布局权重。
 */
@Composable
fun PillButton(
    label: String,
    modifier: Modifier = Modifier,
    emphasis: PillEmphasis = PillEmphasis.Tonal,
    active: Boolean = false,
    verticalPadding: Dp = 10.dp,
    horizontalPadding: Dp = 14.dp,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val tick = rememberTickHaptic()
    val interaction = remember { MutableInteractionSource() }
    val colors = MaterialTheme.colorScheme
    val (containerTarget, contentTarget) = pillColors(emphasis, active, enabled)
    // 强调级 / 选中 / 禁用切换时容器与内容色按 swap 档时长平滑过渡，避免状态直切闪变
    val containerColor by animateColorAsState(
        targetValue = containerTarget,
        animationSpec = tween(WatchMotion.DUR_SWAP_IN, easing = WatchMotion.EnterEasing),
        label = "pillContainer"
    )
    val contentColor by animateColorAsState(
        targetValue = contentTarget,
        animationSpec = tween(WatchMotion.DUR_SWAP_IN, easing = WatchMotion.EnterEasing),
        label = "pillContent"
    )
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .pressScale(interaction)
            .clip(WatchShapes.Pill)
            .background(containerColor)
            .then(
                if (emphasis == PillEmphasis.Outline) {
                    Modifier.border(
                        1.dp,
                        if (enabled) colors.outline else colors.outlineVariant,
                        WatchShapes.Pill
                    )
                } else {
                    Modifier
                }
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button
            ) {
                tick()
                onClick()
            }
            .padding(PaddingValues(horizontal = horizontalPadding, vertical = verticalPadding)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 细进度轨：MD3 LinearProgressIndicator（圆头轨、无轨道间隙）；
 * 动画值经 animateFloatAsState 一次性推进（开卷/进度刷新等一次性场景）
 */
@Composable
fun ProgressTrack(
    progress: Float,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    fillColor: Color = MaterialTheme.colorScheme.primary,
    height: Dp = 4.dp
) {
    val animated by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(520, easing = WatchMotion.EnterEasing),
        label = "progress-track"
    )
    LinearProgressIndicator(
        progress = { animated },
        modifier = modifier.fillMaxWidth().height(height),
        color = fillColor,
        trackColor = trackColor,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {}
    )
}

/** MD3 环形加载指示（indeterminate） */
@Composable
fun LoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    strokeWidth: Dp = 2.4.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    CircularProgressIndicator(
        modifier = modifier.size(size),
        color = color,
        strokeWidth = strokeWidth,
        trackColor = trackColor,
        strokeCap = StrokeCap.Round
    )
}

/**
 * 格式角标（TXT / EPUB）：MD3 tonal badge（容器底 + 配对内容色，无描边）。
 * 填充走 MD3 容器角色（primaryContainer / secondaryContainer 对），
 * 替代旧版「同色 10% alpha 自底」——亮主题下自衬对不达标且产生影子令牌。
 */
@Composable
fun TextBadge(
    text: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer
) {
    Surface(
        modifier = modifier,
        shape = WatchShapes.Badge,
        color = container,
        contentColor = content
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            letterSpacing = 0.6.sp
        )
    }
}

/** 呼吸状态点：服务就绪等活态指示（6dp 点级开销，仅图层失效） */
@Composable
fun PulsingDot(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse-dot")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            tween(1200, easing = WatchMotion.EnterEasing),
            androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "pulse-alpha"
    )
    Box(
        modifier = modifier
            .size(6.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(color)
    )
}

/** 静态状态点 */
@Composable
fun StaticDot(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Box(
        modifier = modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(color)
    )
}
