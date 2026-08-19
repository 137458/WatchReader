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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * 腕上无线传书界面 — 466x466 圆屏黄金安全区排版 + 呼吸光晕 + 触觉震感
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
    val scrollState = rememberScrollState()
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
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
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
            // 1. 表冠物理旋转无缝滚动 + 线性马达微振
            .onRotaryScrollEvent { event ->
                val delta = event.verticalScrollPixels
                if (abs(delta) > 1f) {
                    CrownScrollHelper.dispatchScroll(delta, scrollState, context)
                    true
                } else false
            }
            // 2. 右滑手势极速退出返回书架
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
        // 顶部圆周弧形标题
        CurvedChapterHeader(
            title = "📶 腕上无线传书",
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // 侧边弧形电量与时间
        CurvedSideStatusBar(
            modifier = Modifier.fillMaxSize(),
            textColor = colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
        )

        // 核心滚动内容区域（466x466 圆屏黄金安全区排版）
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 42.dp)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 1. 服务状态指示灯与标题
            if (!ipAddress.isNullOrEmpty() && isServerRunning) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E676).copy(alpha = pulseAlpha))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "传书服务就绪",
                        style = TextStyle(
                            fontSize = 12.sp,
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
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(colorScheme.error)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Wi-Fi 未连接",
                        style = TextStyle(
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.error
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 2. 网址展示核心卡片
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(colorScheme.surfaceVariant.copy(alpha = 0.92f))
                    .border(1.dp, colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                if (!ipAddress.isNullOrEmpty() && isServerRunning) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "同一局域网手机/电脑浏览器打开：",
                            style = TextStyle(
                                fontSize = 10.sp,
                                color = colorScheme.onSurfaceVariant.copy(alpha = 0.9f)
                            ),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        // 高亮网址胶囊
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(colorScheme.background.copy(alpha = 0.85f))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "http://$ipAddress:$port",
                                style = TextStyle(
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = colorScheme.primary,
                                    letterSpacing = 0.6.sp
                                ),
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "支持 .txt 与 .epub 文件拖拽直传",
                            style = TextStyle(
                                fontSize = 9.5.sp,
                                color = colorScheme.outline
                            ),
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "请在手表系统设置中连接 Wi-Fi",
                            style = TextStyle(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = colorScheme.onSurfaceVariant
                            ),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "确保手机/电脑与手表处于同一局域网",
                            style = TextStyle(
                                fontSize = 9.5.sp,
                                color = colorScheme.outline
                            ),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // 3. 动态接收成功徽章（当有书籍传入时呈现）
            if (uploadedCount > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFF00E676).copy(alpha = 0.2f),
                                    Color(0xFF00B0FF).copy(alpha = 0.2f)
                                )
                            )
                        )
                        .border(1.dp, Color(0xFF00E676).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "🎉 已成功接收 $uploadedCount 本小说",
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00E676)
                        ),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 4. 底部操作按钮栏
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                        text = if (isServerRunning) "停止服务" else "重新启动",
                        style = TextStyle(
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.onSurfaceVariant
                        )
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

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
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "‹ 完成返回",
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
