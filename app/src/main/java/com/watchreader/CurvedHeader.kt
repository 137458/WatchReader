package com.watchreader

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 沿 466x466 纯圆屏 12 点钟正上方对称绘制弧形章节名
 *
 * 性能优化核心：
 * 1. 消除 DrawScope 内部的一切对象分配（Paint、Path、RectF、PathMeasure 均在 remember 中复用）
 * 2. 避免快速滚动时引起的频繁 GC，保障 60/120 满帧绘制
 */
@Composable
fun CurvedChapterHeader(
    title: String,
    modifier: Modifier = Modifier
) {
    if (title.isEmpty()) return

    val primaryColor = MaterialTheme.colorScheme.primary
    val density = LocalDensity.current
    val textSizePx = with(density) { 11.5.sp.toPx() }
    val insetPx = with(density) { 2.dp.toPx() } // 紧贴圆盘上边缘 2dp
    val vOffsetPx = with(density) { 16.dp.toPx() } // 沿法线向内沉降 16dp，确保字顶距边缘 4dp

    val displayTitle = remember(title) {
        if (title.length > 20) title.take(19) + "…" else title
    }

    // 复用底层绘图对象，避免每帧分配
    val paint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            letterSpacing = 0.08f
        }
    }
    val path = remember { Path() }
    val ovalRect = remember { RectF() }
    val pathMeasure = remember { PathMeasure() }

    // 缓存上一次构建 Path 的尺寸参数
    val lastRadius = remember { FloatArray(1) { -1f } }

    Canvas(
        modifier = modifier.fillMaxSize()
    ) {
        val diameterPx = maxOf(size.width, size.height)
        if (diameterPx <= 0f) return@Canvas

        val radius = diameterPx / 2f
        val arcRadius = radius - insetPx
        val cx = size.width / 2f
        val cy = if (size.height < size.width) radius else size.height / 2f

        // 仅当尺寸变化时重建 Path，避免无谓重构
        if (lastRadius[0] != arcRadius) {
            lastRadius[0] = arcRadius
            path.reset()
            ovalRect.set(
                cx - arcRadius,
                cy - arcRadius,
                cx + arcRadius,
                cy + arcRadius
            )
            // 195° (左上方) 顺时针扫过 150° 至 345° (右上方)，正中恰好为 270° (12点钟正上方)
            path.addArc(ovalRect, 195f, 150f)
            pathMeasure.setPath(path, false)
        }

        paint.textSize = textSizePx
        paint.color = primaryColor.toArgb()

        drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas
            val pathLength = pathMeasure.length
            val textWidth = paint.measureText(displayTitle)
            // Align.LEFT 下：(弧长 - 文本宽度) / 2 确保文本中点精确落在 270° 处
            val hOffset = maxOf(0f, (pathLength - textWidth) / 2f)

            nativeCanvas.drawTextOnPath(displayTitle, path, hOffset, vOffsetPx, paint)
        }
    }
}

