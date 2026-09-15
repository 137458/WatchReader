package com.watchreader

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap

/**
 * 轻量二维码位图生成工具（基于 ZXing Core）
 *
 * 优化特性：
 * 1. 纯 Java 字节矩阵生成与渲染，0 NDK 开销；
 * 2. 采用 RGB_565 双字节位图格式，相比 ARGB_8888 内存减半，贴合手表 466x466 屏幕；
 * 3. 默认 ErrorCorrectionLevel.M 纠错等级与紧凑安全边距，兼顾暗光扫码识别率与显示面积。
 */
object QrCodeGenerator {

    /**
     * 将文本内容编码为布尔矩阵 (true 为黑色前景色，false 为白色背景色)
     * 独立于 Android Framework 图形层，方便 JVM 脱机单测
     */
    fun encodeToBitMatrix(
        content: String,
        width: Int,
        height: Int,
        margin: Int = 1
    ): BitMatrix {
        require(content.isNotEmpty()) { "Content cannot be empty" }
        require(width > 0 && height > 0) { "Dimensions must be positive" }

        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
            put(EncodeHintType.MARGIN, margin)
        }

        val writer = QRCodeWriter()
        return writer.encode(content, BarcodeFormat.QR_CODE, width, height, hints)
    }

    /**
     * 生成适合手表高对比度显示的黑白二维码 Bitmap
     */
    fun generateQrCodeBitmap(
        content: String,
        sizePx: Int,
        margin: Int = 1
    ): Bitmap? {
        if (content.isEmpty() || sizePx <= 0) return null
        return try {
            val bitMatrix = encodeToBitMatrix(content, sizePx, sizePx, margin)
            val w = bitMatrix.width
            val h = bitMatrix.height
            val pixels = IntArray(w * h)

            for (y in 0 until h) {
                val offset = y * w
                for (x in 0 until w) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }

            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
            bitmap
        } catch (_: Exception) {
            null
        }
    }
}
