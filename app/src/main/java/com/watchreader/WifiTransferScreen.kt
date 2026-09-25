package com.watchreader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 腕上无线传书界面 — 扫码直传极简排版
 *
 * 性能要点：
 * 1. 二维码位图在 Default 线程异步生成（produceState），入场首帧不再被同步编码阻塞掉帧；
 * 2. 传输进度弧线于 Canvas 绘制阶段读取动画值，百分比数字经 derivedStateOf 去重，
 *    浮点进度逐帧推进时仅整数百分比变化才重组。
 *
 * 排版要点（466px 圆屏）：
 * 内容整体垂直居中并控制总高，二维码、地址胶囊、状态行与操作按钮全部落于圆屏黄金安全区，
 * 不再被顶部锚定的长列挤出屏幕底部。
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

    val colorScheme = MaterialTheme.colorScheme
    val tick = rememberTickHaptic()

    // 就绪入场轻振：告知用户服务页已可用
    LaunchedEffect(Unit) {
        tick()
    }

    // 表冠空转反馈：页面无滚动内容，按系统 app 惯例每档给出齿轮微振，杜绝表冠完全无响应
    DisposableEffect(Unit) {
        val target = CrownScrollTarget { delta ->
            CrownScrollHelper.dispatchIdleTick(delta, null)
            true
        }
        CrownScrollTargetRegistry.activate(target)
        onDispose { CrownScrollTargetRegistry.deactivate(target) }
    }

    // 细致平滑进度过渡
    val animatedProgress by animateFloatAsState(
        targetValue = if (isTransferring) transferProgress.coerceIn(0.05f, 1.0f) else 0f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 500f),
        label = "transferProgress"
    )

    // 百分比整数去重：spring 逐帧重定向时仅在数字变化的那一帧重组
    val progressPercent by remember { derivedStateOf { (animatedProgress * 100).toInt() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 右滑手势退出（带累加阻尼防手抖误触，贴合 Wear OS 交互习惯）
            .pointerInput(isTransferring) {
                var dragAccumulator = 0f
                detectHorizontalDragGestures(
                    onDragEnd = { dragAccumulator = 0f },
                    onDragCancel = { dragAccumulator = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        dragAccumulator += dragAmount
                        if (dragAccumulator > 60f && !isTransferring) {
                            dragAccumulator = 0f
                            tick()
                            onBack()
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // 1. 沿屏幕边缘的极简纯色环形进度条（原生 CircularProgressIndicator，仅在传输时呈现）
        if (isTransferring) {
            CircularProgressIndicator(
                progress = animatedProgress,
                modifier = Modifier.fillMaxSize(),
                color = colorScheme.primary,
                trackColor = colorScheme.primary.copy(alpha = 0.12f),
                strokeWidth = 3.dp,
                strokeCap = StrokeCap.Round
            )
        }

        // 2. 视图 A：极简传输进度展示
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
                    text = "$progressPercent%",
                    style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace),
                    color = colorScheme.primary
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
                    text = if (progressPercent >= 99) "已存入书架" else "传输中…",
                    style = TextStyle(
                        fontSize = 9.5.sp,
                        color = colorScheme.outline
                    ),
                    textAlign = TextAlign.Center
                )
            }
        }

        // 3. 视图 B：扫码就绪 / 离线提示
        AnimatedVisibility(
            visible = !isTransferring,
            enter = fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(200))
        ) {
            val serverReady = isServerRunning && !ipAddress.isNullOrEmpty()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 30.dp, vertical = 4.dp)
                    .padding(top = 34.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (serverReady) {
                    QrCodePanel(ipAddress = ipAddress!!, port = port, uploadedCount = uploadedCount)
                } else {
                    OfflinePanel()
                }

                Spacer(modifier = Modifier.height(9.dp))

                // 底部操作行（服务运行中仅保留返回，避免双胶囊挤出圆屏下缘弧线）
                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!isServerRunning) {
                        PillButton(
                            label = "重启服务",
                            verticalPadding = 6.dp,
                            onClick = onToggleServer
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }

                    PillButton(
                        label = "‹ 返回书架",
                        emphasis = PillEmphasis.Primary,
                        verticalPadding = 6.dp,
                        onClick = onBack
                    )
                }
            }
        }

        // 顶部弧形标题
        CurvedChapterHeader(
            title = "无线传书",
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

/**
 * 扫码就绪面板：白底黑码二维码（手机相机毫秒级识别）+ 扫码取景角标 + 地址胶囊 + 就绪状态行
 */
@Composable
private fun QrCodePanel(
    ipAddress: String,
    port: Int,
    uploadedCount: Int
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val outlineColor = MaterialTheme.colorScheme.outline
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    // 二维码编码移出主线程：组合首帧零阻塞，生成期间以同尺寸占位保持布局稳定
    val qrBitmap by produceState<Bitmap?>(initialValue = null, ipAddress, port) {
        value = withContext(Dispatchers.Default) {
            QrCodeGenerator.generateQrCodeBitmap("http://$ipAddress:$port", 260)
        }
    }
    val qrImage = remember(qrBitmap) { qrBitmap?.asImageBitmap() }

    // 扫码取景框：白色圆角码卡 + 四角主色弧形角标（纯静态绘制，无逐帧动画开销）
    Box(
        modifier = Modifier
            .size(96.dp)
            .drawBehind {
                val corner = 12.dp.toPx()
                val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                drawArc(primaryColor, 180f, 90f, false, Offset.Zero, Size(corner * 2, corner * 2), style = stroke)
                drawArc(primaryColor, 270f, 90f, false, Offset(size.width - corner * 2, 0f), Size(corner * 2, corner * 2), style = stroke)
                drawArc(primaryColor, 0f, 90f, false, Offset(size.width - corner * 2, size.height - corner * 2), Size(corner * 2, corner * 2), style = stroke)
                drawArc(primaryColor, 90f, 90f, false, Offset(0f, size.height - corner * 2), Size(corner * 2, corner * 2), style = stroke)
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(86.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Color.White)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (qrImage != null) {
                Image(
                    bitmap = qrImage,
                    contentDescription = "扫码直传二维码",
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LoadingIndicator(size = 16.dp, strokeWidth = 2.dp, color = Color(0xFF444444))
            }
        }
    }

    Spacer(modifier = Modifier.height(5.dp))

    // 备用纯文本网址胶囊
    Box(
        modifier = Modifier
            .clip(WatchShapes.Pill)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "http://$ipAddress:$port",
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = primaryColor
            ),
            maxLines = 1,
            softWrap = false
        )
    }

    Spacer(modifier = Modifier.height(5.dp))

    // 就绪状态行（接收计数并入，省一行纵向空间）
    Row(verticalAlignment = Alignment.CenterVertically) {
        PulsingDot(color = primaryColor)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = if (uploadedCount > 0) "已接收 $uploadedCount 本 · 等待扫码" else "服务已开启 · 等待手机扫码",
            style = TextStyle(fontSize = 10.5.sp, color = onSurfaceVariant)
        )
    }
}

/**
 * 离线提示面板：Wi-Fi 未连接状态 + 连接指引卡片
 */
@Composable
private fun OfflinePanel() {
    val colorScheme = MaterialTheme.colorScheme

    Row(verticalAlignment = Alignment.CenterVertically) {
        StaticDot(color = colorScheme.error)
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

    Spacer(modifier = Modifier.height(9.dp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(WatchShapes.Row)
            .background(colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = "请在手表设置中连接 Wi-Fi",
                style = TextStyle(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurfaceVariant
                ),
                textAlign = TextAlign.Center
            )
            Text(
                text = "与手机处于同个局域网即可扫码传书",
                style = TextStyle(
                    fontSize = 9.sp,
                    color = colorScheme.outline
                ),
                textAlign = TextAlign.Center
            )
        }
    }
}
