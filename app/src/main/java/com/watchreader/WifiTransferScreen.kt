package com.watchreader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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

/**
 * 腕上无线传书界面 — 极简高级纯粹排版（无花哨光晕与多余动画）
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

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        RotaryHapticManager.performScrollTick(context, null)
    }

    // 细致平滑进度过渡
    val animatedProgress by animateFloatAsState(
        targetValue = if (isTransferring) transferProgress.coerceIn(0.05f, 1.0f) else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f),
        label = "transferProgress"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .focusRequester(focusRequester)
            .focusable()
            // 右滑手势退出
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
        // 1. 沿屏幕边缘的极简纯色环形进度条（仅在传输时精细呈现）
        if (isTransferring) {
            val primaryColor = colorScheme.primary
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 3.5.dp.toPx()
                val radius = (size.minDimension - strokeWidth) / 2f
                val arcSize = Size(radius * 2, radius * 2)
                val topLeft = Offset((size.width - radius * 2) / 2f, (size.height - radius * 2) / 2f)

                // 底轨
                drawArc(
                    color = primaryColor.copy(alpha = 0.15f),
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth)
                )

                // 进度弧
                drawArc(
                    color = primaryColor,
                    startAngle = -90f,
                    sweepAngle = animatedProgress * 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }

        // 2. 顶部弧形标题
        CurvedChapterHeader(
            title = "无线传书",
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // 3. 视图 A：极简传输进度展示
        AnimatedVisibility(
            visible = isTransferring,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "${(animatedProgress * 100).toInt()}%",
                    style = TextStyle(
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = colorScheme.primary
                    )
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = if (transferFileName.isNotEmpty()) transferFileName else "正在接收…",
                    style = TextStyle(
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.onSurface
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = if (animatedProgress >= 0.99f) "已存入书架" else "传输中…",
                    style = TextStyle(
                        fontSize = 9.5.sp,
                        color = colorScheme.outline
                    ),
                    textAlign = TextAlign.Center
                )
            }
        }

        // 4. 视图 B：极简就绪卡片
        AnimatedVisibility(
            visible = !isTransferring,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 状态指示
                if (!ipAddress.isNullOrEmpty() && isServerRunning) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "服务已就绪",
                            style = TextStyle(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colorScheme.onSurfaceVariant
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
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(colorScheme.error)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = "Wi-Fi 未连接",
                            style = TextStyle(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colorScheme.error
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 网址卡片
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colorScheme.surfaceVariant.copy(alpha = 0.85f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (!ipAddress.isNullOrEmpty() && isServerRunning) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "手机/电脑浏览器打开：",
                                style = TextStyle(
                                    fontSize = 10.sp,
                                    color = colorScheme.onSurfaceVariant
                                ),
                                textAlign = TextAlign.Center
                            )

                            Spacer(modifier = Modifier.height(5.dp))

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
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
                                text = "支持 .txt 与 .epub 直传",
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
                                text = "保持处于同个局域网",
                                style = TextStyle(
                                    fontSize = 9.sp,
                                    color = colorScheme.outline
                                ),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // 接收计数
                if (uploadedCount > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "已接收 $uploadedCount 本",
                        style = TextStyle(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.primary
                        ),
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 底部按钮
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isServerRunning) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(colorScheme.surfaceVariant)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    RotaryHapticManager.performScrollTick(context, null)
                                    onToggleServer()
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "重启服务",
                                style = TextStyle(
                                    fontSize = 10.sp,
                                    color = colorScheme.onSurfaceVariant
                                )
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(colorScheme.primary)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                RotaryHapticManager.performScrollTick(context, null)
                                onBack()
                            }
                            .padding(horizontal = 18.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "‹ 返回书架",
                            style = TextStyle(
                                fontSize = 11.sp,
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
