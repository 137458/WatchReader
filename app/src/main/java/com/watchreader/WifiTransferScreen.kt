package com.watchreader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 腕上无线传书界面 — 466x466 圆屏黄金内接安全区 + 全屏环形边缘进度条 + 沉浸式传输动效
 */
@Composable
fun WifiTransferScreen(
    ipAddress: String?,
    port: Int = 8888,
    uploadedCount: Int,
    isServerRunning: Boolean,
    isTransferring: Boolean = false,
    transferProgress: Float = 0f,
    transferFileName: String = "",
    onToggleServer: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }

    // 进入页面时触发一次轻柔就绪触觉微振
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        RotaryHapticManager.performScrollTick(context, null)
    }

    // 平滑插值动画进度（从 0.0f 到 1.0f 丝滑过渡）
    val animatedProgress by animateFloatAsState(
        targetValue = if (isTransferring) transferProgress.coerceIn(0.05f, 1.0f) else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
        label = "transferProgress"
    )

    // 动态呼吸脉冲动画
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val waveScale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "waveScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .focusRequester(focusRequester)
            .focusable()
            // 右滑手势极速退出返回书架
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    if (dragAmount > 25f && !isTransferring) {
                        RotaryHapticManager.performScrollTick(context, null)
                        onBack()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // ═══════════════════════════════════════
        //  1. 沿着手表圆形边缘的全屏环形进度条 Canvas
        // ═══════════════════════════════════════
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 5.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2f
            val arcSize = Size(radius * 2, radius * 2)
            val topLeft = Offset((size.width - radius * 2) / 2f, (size.height - radius * 2) / 2f)

            if (isTransferring) {
                // 底环半透明暗轨
                drawArc(
                    color = Color(0x3300E676),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth)
                )

                // 动态高光渐变进度环
                val gradientBrush = Brush.sweepGradient(
                    colors = listOf(
                        Color(0xFF00E676),
                        Color(0xFF00B0FF),
                        Color(0xFF7C4DFF),
                        Color(0xFF00E676)
                    )
                )
                val sweep = animatedProgress * 360f
                drawArc(
                    brush = gradientBrush,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )

                // 进度头部的发光脉冲圆点
                val angleRad = Math.toRadians((sweep - 90.0))
                val center = Offset(size.width / 2f, size.height / 2f)
                val dotX = (center.x + radius * cos(angleRad)).toFloat()
                val dotY = (center.y + radius * sin(angleRad)).toFloat()

                drawCircle(
                    color = Color.White,
                    radius = 3.5.dp.toPx(),
                    center = Offset(dotX, dotY)
                )
            }
        }

        // ═══════════════════════════════════════
        //  2. 顶部弧形标题（在非传输状态下常驻）
        // ═══════════════════════════════════════
        if (!isTransferring) {
            CurvedChapterHeader(
                title = "📶 局域网无线传书",
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        // ═══════════════════════════════════════
        //  3. 视图 A：全屏传书沉浸动效（传输中触发）
        // ═══════════════════════════════════════
        AnimatedVisibility(
            visible = isTransferring,
            enter = fadeIn(animationSpec = tween(300)),
            exit = fadeOut(animationSpec = tween(300))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 中心发光波纹与电子书图标
                Box(
                    modifier = Modifier.size(68.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // 外发光扩散波纹
                    Box(
                        modifier = Modifier
                            .size(64.dp * waveScale)
                            .clip(CircleShape)
                            .background(Color(0xFF00E676).copy(alpha = (1f - (waveScale - 0.85f) / 0.4f).coerceIn(0f, 0.35f)))
                    )

                    // 核心图标底座
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(colorScheme.surfaceVariant)
                            .border(1.5.dp, Color(0xFF00E676), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (animatedProgress >= 0.99f) "✓" else "📖",
                            style = TextStyle(
                                fontSize = if (animatedProgress >= 0.99f) 24.sp else 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (animatedProgress >= 0.99f) Color(0xFF00E676) else colorScheme.primary
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 百分比大字
                Text(
                    text = "${(animatedProgress * 100).toInt()}%",
                    style = TextStyle(
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.Monospace,
                        color = if (animatedProgress >= 0.99f) Color(0xFF00E676) else colorScheme.primary,
                        letterSpacing = 0.5.sp
                    )
                )

                Spacer(modifier = Modifier.height(4.dp))

                // 当前传输小说文件名
                Text(
                    text = if (transferFileName.isNotEmpty()) transferFileName else "正在接收小说…",
                    style = TextStyle(
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurface
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = if (animatedProgress >= 0.99f) "已成功存入书架！" else "正在高速无线传输…",
                    style = TextStyle(
                        fontSize = 9.5.sp,
                        color = if (animatedProgress >= 0.99f) Color(0xFF00E676) else colorScheme.outline
                    ),
                    textAlign = TextAlign.Center
                )
            }
        }

        // ═══════════════════════════════════════
        //  4. 视图 B：就绪等待视图（展示 IP 网址与操作按钮）
        // ═══════════════════════════════════════
        AnimatedVisibility(
            visible = !isTransferring,
            enter = fadeIn(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(250))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 状态呼吸指示灯
                if (!ipAddress.isNullOrEmpty() && isServerRunning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00E676).copy(alpha = pulseAlpha))
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "传书服务已就绪",
                            style = TextStyle(
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E676)
                            )
                        )
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(colorScheme.error)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Wi-Fi 未连接",
                            style = TextStyle(
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.error
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 网址核心展示卡片（单行绝对不折行）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colorScheme.surfaceVariant.copy(alpha = 0.95f))
                        .border(1.dp, colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (!ipAddress.isNullOrEmpty() && isServerRunning) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "手机/电脑浏览器直接打开：",
                                style = TextStyle(
                                    fontSize = 10.sp,
                                    color = colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                                ),
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(5.dp))

                            // 高亮网址胶囊（单行绝对不折行）
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colorScheme.background)
                                    .padding(horizontal = 6.dp, vertical = 5.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "http://$ipAddress:$port",
                                    style = TextStyle(
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colorScheme.primary
                                    ),
                                    maxLines = 1,
                                    softWrap = false,
                                    textAlign = TextAlign.Center
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "支持 .txt 与 .epub 文件拖拽直传",
                                style = TextStyle(
                                    fontSize = 9.sp,
                                    color = colorScheme.outline
                                ),
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "请在手表设置中连接 Wi-Fi",
                                style = TextStyle(
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colorScheme.onSurfaceVariant
                                ),
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "确保手机与手表处于同个 Wi-Fi",
                                style = TextStyle(
                                    fontSize = 9.sp,
                                    color = colorScheme.outline
                                ),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // 动态接收成功计数
                if (uploadedCount > 0) {
                    Spacer(modifier = Modifier.height(7.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF00E676).copy(alpha = 0.16f))
                            .border(1.dp, Color(0xFF00E676).copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 12.dp, vertical = 3.5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🎉 已成功接收 $uploadedCount 本小说",
                            style = TextStyle(
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00E676)
                            ),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 底部操作胶囊
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isServerRunning) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(colorScheme.surfaceVariant)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    RotaryHapticManager.performScrollTick(context, null)
                                    onToggleServer()
                                }
                                .padding(horizontal = 11.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "重新启动",
                                style = TextStyle(
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colorScheme.onSurfaceVariant
                                )
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(colorScheme.primary)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                RotaryHapticManager.performScrollTick(context, null)
                                onBack()
                            }
                            .padding(horizontal = 20.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "‹ 完成返回",
                            style = TextStyle(
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.onPrimary
                            )
                        )
                    }
                }
            }
        }
    }
}
