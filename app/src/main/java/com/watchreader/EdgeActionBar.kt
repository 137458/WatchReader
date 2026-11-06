package com.watchreader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.OutlinedButton
import androidx.wear.compose.material3.Text as WearText

/**
 * 底部操作条（原贴边弧形扇带，ADR-020 视觉口径已由本文件替换）
 *
 * 改用 Wear M3 官方 Button / FilledTonalButton / OutlinedButton：尺寸、圆角、
 * 按压反馈与无障碍语义全部走组件默认值，不再自绘弧线与文字轨道。
 * [EdgeButtonSpec] / [PillEmphasis] 保持原签名，五处页面调用点零改动。
 *
 * 位置：距屏底 56dp。466px 圆屏上按钮行中线约在 y=306px（距圆心 73px），
 * 该高度弦宽约 220dp，三键仍完整落在圆内；方屏由 Row 宽度自适应。
 */
enum class PillEmphasis { Tonal, Primary, Outline }

/**
 * 底部操作条按钮规格：标签、外观与点击回调。
 * [focused] 供表冠焦点轮选高亮，不参与点击态。
 */
data class EdgeButtonSpec(
    val label: String,
    val emphasis: PillEmphasis = PillEmphasis.Tonal,
    val active: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

@Composable
fun EdgeActionBar(
    vararg buttons: EdgeButtonSpec,
    modifier: Modifier = Modifier
) {
    if (buttons.isEmpty()) return
    val tick = rememberTickHaptic()
    Box(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            buttons.forEach { spec ->
                EdgeActionButton(
                    spec = spec,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        tick()
                        spec.onClick()
                    }
                )
            }
        }
    }
}

@Composable
private fun EdgeActionButton(
    spec: EdgeButtonSpec,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val focusBorder = if (spec.focused && spec.enabled) {
        BorderStroke(1.5.dp, scheme.primary)
    } else {
        null
    }
    val label: @Composable RowScope.() -> Unit = {
        WearText(
            text = spec.label,
            style = WearMaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
    when (spec.emphasis) {
        PillEmphasis.Primary -> Button(
            onClick = onClick,
            modifier = modifier,
            enabled = spec.enabled,
            colors = if (spec.active) {
                ButtonDefaults.buttonColors(
                    containerColor = scheme.primaryContainer,
                    contentColor = scheme.onPrimaryContainer
                )
            } else {
                ButtonDefaults.buttonColors()
            },
            border = focusBorder,
            content = label
        )

        PillEmphasis.Tonal -> FilledTonalButton(
            onClick = onClick,
            modifier = modifier,
            enabled = spec.enabled,
            colors = if (spec.active) {
                ButtonDefaults.buttonColors(
                    containerColor = scheme.primaryContainer,
                    contentColor = scheme.onPrimaryContainer
                )
            } else {
                ButtonDefaults.filledTonalButtonColors()
            },
            border = focusBorder,
            content = label
        )

        PillEmphasis.Outline -> OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = spec.enabled,
            border = focusBorder ?: BorderStroke(1.dp, scheme.outline),
            content = label
        )
    }
}
