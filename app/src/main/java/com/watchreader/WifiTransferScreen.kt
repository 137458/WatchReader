package com.watchreader

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 腕上无线传书界面 — 466x466 圆屏黄金安全区排版
 */
@Composable
fun WifiTransferScreen(
    ipAddress: String?,
    uploadedCount: Int,
    isServerRunning: Boolean,
    onToggleServer: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val colorScheme = MaterialTheme.colorScheme
    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 36.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 1. 标题与状态指示
            Text(
                text = "Wi-Fi 无线传书",
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.primary
                ),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 2. 状态卡片
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                if (!ipAddress.isNullOrEmpty() && isServerRunning) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "● 传书服务已就绪",
                            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF4CAF50)),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "请在同局域网手机/电脑浏览器打开：",
                            style = TextStyle(fontSize = 10.sp, color = colorScheme.onSurfaceVariant),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "http://$ipAddress:8888",
                            style = TextStyle(
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = colorScheme.primary,
                                letterSpacing = 0.5.sp
                            ),
                            textAlign = TextAlign.Center
                        )
                        if (uploadedCount > 0) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "已成功传输 $uploadedCount 本小说",
                                style = TextStyle(fontSize = 10.5.sp, fontWeight = FontWeight.Medium, color = colorScheme.secondary),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "未连接到 Wi-Fi 网络",
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colorScheme.error),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "请在手表设置中连接 Wi-Fi 后重试",
                            style = TextStyle(fontSize = 10.sp, color = colorScheme.onSurfaceVariant),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 3. 操作按钮栏
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(colorScheme.surfaceVariant)
                        .clickable { onToggleServer() }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isServerRunning) "停止服务" else "重新启动",
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colorScheme.onSurfaceVariant)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(colorScheme.primary)
                        .clickable { onBack() }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "完成返回",
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colorScheme.onPrimary)
                    )
                }
            }
        }
    }
}
