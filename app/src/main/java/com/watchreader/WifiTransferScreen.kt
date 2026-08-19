package com.watchreader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 腕上无线传书界面 — 466x466 圆屏黄金内接安全区极简精致排版
 */
@Composable
fun WifiTransferScreen(
    ipAddress: String?,
    port: Int = 8888,
    uploadedCount: Int,
    isServerRunning: Boolean,
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

    // 动态呼吸光晕动画（服务就绪时绿光脉冲）
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .focusRequester(focusRequester)
            .focusable()
            // 右滑手势极速退出返回书架
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    if (dragAmount > 25f) {
                        RotaryHapticManager.performScrollTick(context, null)
                        onBack()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        // 1. 顶部圆周弧形标题
        CurvedChapterHeader(
            title = "📶 局域网无线传书",
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // 2. 中心黄金安全区卡片
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

            // 网址核心展示卡片
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

                        // 高亮网址胶囊
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
