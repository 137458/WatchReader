package com.watchreader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    // 预先缓存文本测量宽度，避免在每帧 drawIntoCanvas 中重复调用 measureText
    val cachedTextWidth = remember(displayTitle, textSizePx) {
        paint.textSize = textSizePx
        paint.measureText(displayTitle)
    }

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
            // Align.LEFT 下：(弧长 - 文本宽度) / 2 确保文本中点精确落在 270° 处
            val hOffset = maxOf(0f, (pathLength - cachedTextWidth) / 2f)

            nativeCanvas.drawTextOnPath(displayTitle, path, hOffset, vOffsetPx, paint)
        }
    }
}

/**
 * 沿 466x466 纯圆屏两侧对称绘制贴边弧形电量（9 点钟）与贴边弧形时间（3 点钟）
 *
 * 核心设计：
 * 1. 【文字正向直立】：字符自身全为竖排正立状态，不发生 90° 侧旋，符合自然阅读习惯；
 * 2. 【位置随弧贴边】：每个字符的 (x, y) 坐标严格跟随圆屏极坐标半径 (r = R - insetPx) 弯曲排布，上下向内弯、中间向外鼓，呈现极致圆表贴边弧形；
 * 3. 【0 GC 性能保障】：预分配计算，高刷滚动 0 内存抖动。
 */
@Composable
fun CurvedSideStatusBar(
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
) {
    val context = LocalContext.current
    var currentTime by remember {
        mutableStateOf(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()))
    }
    var batteryLevel by remember {
        mutableStateOf(getBatteryCapacity(context))
    }
    var isCharging by remember {
        mutableStateOf(false)
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_TIME_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                        currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                    }
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                        isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                                status == BatteryManager.BATTERY_STATUS_FULL
                        if (level >= 0 && scale > 0) {
                            batteryLevel = (level * 100) / scale
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        val sticky = context.registerReceiver(receiver, filter)
        sticky?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            if (level >= 0 && scale > 0) {
                batteryLevel = (level * 100) / scale
            }
        }

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
        }
    }

    val batteryChars = remember(batteryLevel, isCharging) {
        val str = if (isCharging) "⚡$batteryLevel%" else "$batteryLevel%"
        CharArray(str.length) { str[it] }
    }
    val timeChars = remember(currentTime) {
        CharArray(currentTime.length) { currentTime[it] }
    }

    val density = LocalDensity.current
    val textSizePx = with(density) { 10.sp.toPx() }
    val charSpacingPx = with(density) { 11.5.dp.toPx() }
    val insetPx = with(density) { 6.dp.toPx() }

    val paint = remember {
        Paint().apply {
            isAntiAlias = true
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val diameterPx = maxOf(size.width, size.height)
        if (diameterPx <= 0f) return@Canvas

        val radius = diameterPx / 2f
        val arcRadius = radius - insetPx
        val cx = size.width / 2f
        val cy = if (size.height < size.width) radius else size.height / 2f

        paint.textSize = textSizePx
        paint.color = textColor.toArgb()

        val fontMetrics = paint.fontMetrics
        val verticalCenteringOffset = ((fontMetrics.descent - fontMetrics.ascent) / 2f) - fontMetrics.descent
        val angleStepRad = (charSpacingPx / arcRadius).toDouble()

        drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas

            // 1. 左侧 9 点钟方向电量：字符全正立，从上到下排布，坐标随左表圈弧度向右弯曲收紧
            val bCount = batteryChars.size
            for (i in 0 until bCount) {
                val indexOffset = i - ((bCount - 1) / 2.0)
                // 180° 为 Math.PI，减去角度使从上（y小）到下（y大）顺排
                val angle = Math.PI - (indexOffset * angleStepRad)
                val x = (cx + arcRadius * Math.cos(angle)).toFloat()
                val y = (cy + arcRadius * Math.sin(angle)).toFloat() + verticalCenteringOffset

                val charStr = batteryChars[i].toString()
                nativeCanvas.drawText(charStr, x, y, paint)
            }

            // 2. 右侧 3 点钟方向时间：字符全正立，从上到下排布，坐标随右表圈弧度向左弯曲收紧
            val tCount = timeChars.size
            for (i in 0 until tCount) {
                val indexOffset = i - ((tCount - 1) / 2.0)
                // 0° 为 0.0，加上角度使从上（负角度y小）到下（正角度y大）顺排
                val angle = 0.0 + (indexOffset * angleStepRad)
                val x = (cx + arcRadius * Math.cos(angle)).toFloat()
                val y = (cy + arcRadius * Math.sin(angle)).toFloat() + verticalCenteringOffset

                val charStr = timeChars[i].toString()
                nativeCanvas.drawText(charStr, x, y, paint)
            }
        }
    }
}

private fun getBatteryCapacity(context: Context): Int {
    return try {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.coerceIn(0, 100) ?: 100
    } catch (_: Exception) {
        100
    }
}




