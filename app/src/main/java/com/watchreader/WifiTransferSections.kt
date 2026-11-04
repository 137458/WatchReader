package com.watchreader

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * 传书页的判定与文案口径：全部为纯函数，不引用任何 Android / Compose 类型，
 * 便于 JVM 单测锁定（二维码地址拼装、就绪判定、进度钳制与文案）。
 */
internal object TransferPanelText {
    /** 二维码/网址栏载荷：地址 + 端口，有令牌时附 ?token=（与网页鉴权口径一致） */
    fun pageUrl(ipAddress: String, port: Int, accessToken: String?): String {
        val url = buildString {
            append("http://").append(ipAddress).append(':').append(port)
            if (!accessToken.isNullOrEmpty()) {
                append("/?token=").append(accessToken)
            }
        }
        return url
    }

    /** 服务就绪：服务在跑且已拿到非空局域网地址 */
    fun serverReady(isServerRunning: Boolean, ipAddress: String?): Boolean =
        isServerRunning && !ipAddress.isNullOrEmpty()

    /** 进度弧目标值：传输中至少留 5% 视觉起量，非传输态归零 */
    fun targetProgress(isTransferring: Boolean, progress: Float): Float =
        if (isTransferring) progress.coerceIn(0.05f, 1.0f) else 0f

    /** 整数百分比（派生状态去重用） */
    fun percentOf(progress: Float): Int = (progress * 100).toInt()

    /** 传输中文件名占位 */
    fun fileNameLabel(fileName: String): String =
        if (fileName.isNotEmpty()) fileName else "正在接收…"

    /** 传输状态行：接近完成时改口径 */
    fun transferStatusLabel(percent: Int): String =
        if (percent >= 99) "已存入书架" else "传输中…"

    /** 就绪状态行：接收计数并入，省一行纵向空间 */
    fun readyLine(uploadedCount: Int): String =
        if (uploadedCount > 0) "已接收 $uploadedCount 本 · 等待扫码" else "服务已开启 · 等待手机扫码"
}

/**
 * 底部弧形操作条接线：未运行服务时补「重启服务」键，返回键恒在末位承接焦点高亮。
 * 纯装配（无 Compose 调用），键序与焦点下标口径逐字沿用页面原实现。
 */
internal fun transferBarSpecs(
    isServerRunning: Boolean,
    focusedIndex: Int,
    focusCount: Int,
    onToggleServer: () -> Unit,
    onBack: () -> Unit
): List<EdgeButtonSpec> = buildList {
    if (!isServerRunning) {
        add(
            EdgeButtonSpec(
                label = "重启服务",
                focused = focusedIndex == 0,
                onClick = onToggleServer
            )
        )
    }
    add(
        EdgeButtonSpec(
            label = "‹ 返回书架",
            emphasis = PillEmphasis.Primary,
            focused = focusedIndex == focusCount - 1,
            onClick = onBack
        )
    )
}

/**
 * 视图 A：极简传输进度展示（大号整数百分比 + 文件名 + 状态行）
 */
@Composable
internal fun TransferProgressPanel(
    progressPercent: Int,
    transferFileName: String,
    pendingExit: Boolean
) {
    val colorScheme = MaterialTheme.colorScheme

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
            text = TransferPanelText.fileNameLabel(transferFileName),
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = TransferPanelText.transferStatusLabel(progressPercent),
            style = MaterialTheme.typography.labelSmall.copy(
                color = colorScheme.onSurfaceVariant
            ),
            textAlign = TextAlign.Center
        )

        if (pendingExit) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "再按一次返回键将中断传输",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = colorScheme.error
                ),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 扫码就绪面板：白底黑码二维码（手机相机毫秒级识别）+ 扫码取景角标 + 地址胶囊 + 就绪状态行
 */
@Composable
internal fun QrCodePanel(
    ipAddress: String,
    port: Int,
    accessToken: String?,
    uploadedCount: Int
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    // 二维码载荷携带访问令牌：网页与全部接口调用均凭 token 鉴权，
    // 防止同网段未授权设备或网页直接访问传书服务
    val pageUrl = TransferPanelText.pageUrl(ipAddress, port, accessToken)

    // 二维码编码移出主线程：组合首帧零阻塞，生成期间以同尺寸占位保持布局稳定
    val qrBitmap by produceState<Bitmap?>(initialValue = null, pageUrl) {
        value = withContext(Dispatchers.Default) {
            QrCodeGenerator.generateQrCodeBitmap(pageUrl, 260)
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
                .background(WatchFixed.QrPanelBackground)
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
                LoadingIndicator(size = 16.dp, strokeWidth = 2.dp, color = WatchFixed.QrPanelInk)
            }
        }
    }

    Spacer(modifier = Modifier.height(5.dp))

    // 备用纯文本网址：地址与令牌分两行（带 token 的完整 URL ~42 字符，单行必被圆屏裁切，
    // 手机相机不可用时这里是唯一兜底通道，信息必须完整可抄）
    Box(
        modifier = Modifier
            .clip(WatchShapes.Pill)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "http://${ipAddress}:$port",
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = primaryColor
                ),
                maxLines = 1,
                softWrap = false
            )
            if (!accessToken.isNullOrEmpty()) {
                Text(
                    text = "令牌 $accessToken",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = onSurfaceVariant
                    ),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(5.dp))

    // 就绪状态行（接收计数并入，省一行纵向空间）
    Row(verticalAlignment = Alignment.CenterVertically) {
        PulsingDot(color = primaryColor)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = TransferPanelText.readyLine(uploadedCount),
            style = MaterialTheme.typography.labelSmall.copy(color = onSurfaceVariant)
        )
    }
}

/**
 * 贴圆屏边沿的 MD3 环形进度：细轨 + 圆头进度弧，仅传输时呈现。
 * 纯 Canvas 绘制，进度值在绘制阶段读取，不触发页面重组。
 */
@Composable
internal fun EdgeProgressRing(
    progress: Float,
    color: Color,
    trackColor: Color
) {
    val strokeWidth = 3.dp
    Canvas(modifier = Modifier.fillMaxSize()) {
        val strokePx = strokeWidth.toPx()
        val radius = min(size.width, size.height) / 2f - strokePx / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            color = trackColor,
            radius = radius,
            center = center,
            style = Stroke(width = strokePx)
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = progress.coerceIn(0f, 1f) * 360f,
            useCenter = false,
            topLeft = Offset(center.x - radius, center.y - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )
    }
}

/**
 * 离线提示面板：Wi-Fi 未连接状态 + 连接指引卡片
 */
@Composable
internal fun OfflinePanel() {
    val colorScheme = MaterialTheme.colorScheme

    Row(verticalAlignment = Alignment.CenterVertically) {
        StaticDot(color = colorScheme.error)
        Spacer(modifier = Modifier.width(5.dp))
        Text(
            text = "Wi-Fi 未连接",
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                color = colorScheme.error
            )
        )
    }

    Spacer(modifier = Modifier.height(9.dp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(WatchShapes.Row))
            .background(colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = "请在手表设置中连接 Wi-Fi",
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.onSurfaceVariant
                ),
                textAlign = TextAlign.Center
            )
            Text(
                text = "与手机处于同个局域网即可扫码传书",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = colorScheme.onSurfaceVariant
                ),
                textAlign = TextAlign.Center
            )
        }
    }
}
