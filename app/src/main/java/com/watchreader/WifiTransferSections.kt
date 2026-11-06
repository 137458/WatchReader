package com.watchreader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator as WearCircularProgressIndicator
import androidx.wear.compose.material3.LinearProgressIndicator as WearLinearProgressIndicator
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.OutlinedCard as WearOutlinedCard
import androidx.wear.compose.material3.ProgressIndicatorDefaults as WearProgressIndicatorDefaults
import androidx.wear.compose.material3.Text as WearText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 * 视图 A：极简传输进度展示（大号整数百分比 + 官方线性进度条 + 文件名 + 状态行）
 */
@Composable
internal fun TransferProgressPanel(
    progressPercent: Int,
    transferFileName: String,
    pendingExit: Boolean
) {
    val colors = WearMaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        WearText(
            text = "$progressPercent%",
            style = WearMaterialTheme.typography.displaySmall.copy(fontFamily = FontFamily.Monospace),
            color = colors.primary
        )

        Spacer(modifier = Modifier.height(6.dp))

        // 官方线性进度条：进度值以 lambda 传入，由组件内部按帧读取，避免逐帧重组
        WearLinearProgressIndicator(
            progress = { (progressPercent / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            colors = WearProgressIndicatorDefaults.colors(
                indicatorColor = colors.primary,
                trackColor = colors.outlineVariant
            )
        )

        Spacer(modifier = Modifier.height(6.dp))

        WearText(
            text = TransferPanelText.fileNameLabel(transferFileName),
            style = WearMaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Medium,
                color = colors.onSurface
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(2.dp))

        WearText(
            text = TransferPanelText.transferStatusLabel(progressPercent),
            style = WearMaterialTheme.typography.labelSmall.copy(
                color = colors.onSurfaceVariant
            ),
            textAlign = TextAlign.Center
        )

        if (pendingExit) {
            Spacer(modifier = Modifier.height(6.dp))
            WearText(
                text = "再按一次返回键将中断传输",
                style = WearMaterialTheme.typography.labelSmall.copy(
                    color = colors.error
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
    val colors = WearMaterialTheme.colorScheme
    val primaryColor = colors.primary
    val onSurfaceVariant = colors.onSurfaceVariant

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
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
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
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                WearCircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    colors = WearProgressIndicatorDefaults.colors(
                        indicatorColor = WatchFixed.QrPanelInk
                    )
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(5.dp))

    // 备用纯文本网址：地址与令牌分两行（带 token 的完整 URL ~42 字符，单行必被圆屏裁切，
    // 手机相机不可用时这里是唯一兜底通道，信息必须完整可抄）
    Box(
        modifier = Modifier
            .clip(WatchShapes.Pill)
            .background(colors.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            WearText(
                text = "http://$ipAddress:$port",
                style = WearMaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = primaryColor
                ),
                maxLines = 1,
                softWrap = false
            )
            if (!accessToken.isNullOrEmpty()) {
                WearText(
                    text = "令牌 $accessToken",
                    style = WearMaterialTheme.typography.labelSmall.copy(
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
        WearText(
            text = TransferPanelText.readyLine(uploadedCount),
            style = WearMaterialTheme.typography.labelSmall.copy(color = onSurfaceVariant)
        )
    }
}

/**
 * 贴圆屏边沿的环形进度：官方 CircularProgressIndicator 定值版（细轨 + 圆头进度弧），
 * 仅传输时呈现。进度值以 lambda 传入，组件内部按帧读取，不触发页面重组。
 */
@Composable
internal fun EdgeProgressRing(
    progress: Float,
    color: Color,
    trackColor: Color
) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter
    ) {
        WearCircularProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.size(56.dp),
            colors = WearProgressIndicatorDefaults.colors(
                indicatorColor = color,
                trackColor = trackColor
            )
        )
    }
}

/**
 * 离线提示面板：Wi-Fi 未连接状态 + 连接指引卡片（官方 OutlinedCard 承载指引文案）
 */
@Composable
internal fun OfflinePanel() {
    val colors = WearMaterialTheme.colorScheme

    Row(verticalAlignment = Alignment.CenterVertically) {
        StaticDot(color = colors.error)
        Spacer(modifier = Modifier.width(5.dp))
        WearText(
            text = "Wi-Fi 未连接",
            style = WearMaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.SemiBold,
                color = colors.error
            )
        )
    }

    Spacer(modifier = Modifier.height(9.dp))

    WearOutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            WearText(
                text = "请在手表设置中连接 Wi-Fi",
                style = WearMaterialTheme.typography.bodySmall.copy(
                    fontWeight = FontWeight.Medium,
                    color = colors.onSurfaceVariant
                ),
                textAlign = TextAlign.Center
            )
            WearText(
                text = "与手机处于同个局域网即可扫码传书",
                style = WearMaterialTheme.typography.labelSmall.copy(
                    color = colors.onSurfaceVariant
                ),
                textAlign = TextAlign.Center
            )
        }
    }
}
