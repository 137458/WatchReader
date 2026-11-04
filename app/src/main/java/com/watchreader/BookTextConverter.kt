package com.watchreader

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.Writer
import kotlin.math.abs

/**
 * 电子书格式标识
 *
 * [extensions] 驱动扩展名识别与 Wi-Fi 传书白名单；[badge] 用于书架格式徽标展示。
 */
enum class BookFormat(val extensions: List<String>, val badge: String) {
    TXT(listOf("txt", "md", "log"), "TXT"),
    EPUB(listOf("epub"), "EPUB"),
    MOBI(listOf("mobi", "azw3", "azw", "prc"), "MOBI"),
    FB2(listOf("fb2"), "FB2"),
    HTML(listOf("html", "htm", "xhtml"), "HTML");

    companion object {
        private val byExtension: Map<String, BookFormat> =
            entries.flatMap { f -> f.extensions.map { it to f } }.toMap()

        fun fromExtension(extension: String): BookFormat? = byExtension[extension.lowercase()]
    }
}

/** 转换过程中的格式级失败（DRM 保护、不支持压缩、容器损坏等），消息直接面向用户展示 */
class BookFormatException(message: String) : IOException(message)

/**
 * 多格式电子书流式转换引擎门面：格式判定、缓存落盘与按格式分派。
 *
 * 实现分三个模块：MOBI/AZW3 与 PalmDOC 解压见 [MobiFormat]，FB2 见 [Fb2Format]，
 * HTML 与流式标签剥离见 [HtmlFormat]。转换产物为 UTF-8 文本缓存，二次打开零转换；
 * 章节索引、封顶分节与分块读取完全复用既有 TXT 流式内核，峰值内存与 TXT 路径同级。
 */
object BookTextConverter {

    /** 按扩展名 + 可选头部魔数识别格式；未识别回落 TXT（保持既有行为） */
    fun resolveFormat(fileName: String, header: ByteArray? = null): BookFormat {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        BookFormat.fromExtension(ext)?.let { return it }

        if (header != null) {
            if (header.size >= 68 && MobiFormat.hasAsciiTag(header, 60, "BOOK") &&
                MobiFormat.hasAsciiTag(header, 64, "MOBI")
            ) {
                return BookFormat.MOBI
            }
            if (header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
                header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
            ) {
                return BookFormat.EPUB
            }
        }
        return BookFormat.TXT
    }

    /** 基于 URI 的格式识别（扩展名未知时采样 68 字节头部做魔数校验） */
    fun resolveFormat(context: Context, uri: Uri): BookFormat {
        val fileName = getFileName(context, uri)
        val ext = fileName.substringAfterLast('.', "")
        if (ext.isNotEmpty()) {
            BookFormat.fromExtension(ext)?.let { return it }
        }
        return resolveFormat(fileName, readHeaderBytes(context, uri, 68))
    }

    /** Wi-Fi 传书等入口的扩展名白名单 */
    fun isSupportedFileName(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return BookFormat.fromExtension(ext) != null
    }

    /** 书架徽标文案（EPUB/MOBI/FB2/HTML/TXT） */
    fun formatBadgeOf(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return BookFormat.fromExtension(ext)?.badge ?: BookFormat.TXT.badge
    }

    /** 将 [format] 书籍流式转换为纯文本并写入 [out]（UTF-8 缓存的落盘编码由调用方决定） */
    fun convertToText(input: InputStream, format: BookFormat, out: Writer) {
        when (format) {
            BookFormat.MOBI -> MobiFormat.convert(input, out)
            BookFormat.FB2 -> Fb2Format.convert(input, out)
            BookFormat.HTML -> HtmlFormat.convertToText(input, out, null)
            BookFormat.TXT, BookFormat.EPUB ->
                throw IllegalArgumentException("格式 $format 无需文本转换")
        }
    }

    /** 转换为磁盘 UTF-8 文本缓存并返回缓存文件（存在即复用，临时文件原子改名落盘） */
    fun convertToCacheFile(context: Context, uri: Uri, format: BookFormat): File {
        val size = getFileSize(context, uri)
        val cache = File(
            context.cacheDir,
            "txt_cache_${abs(uri.toString().hashCode())}_${size}_${format.name.lowercase()}.txt"
        )
        if (cache.exists() && cache.length() > 0) return cache

        val tmp = File(context.cacheDir, cache.name + ".tmp")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw BookFormatException("无法打开文件流")
            input.use { stream ->
                java.io.BufferedWriter(
                    java.io.OutputStreamWriter(tmp.outputStream(), Charsets.UTF_8),
                    65536
                ).use { writer ->
                    convertToText(stream, format, writer)
                    writer.flush()
                }
            }
            if (tmp.length() == 0L) throw BookFormatException("转换结果为空，文件可能已损坏")
            if (!tmp.renameTo(cache)) {
                try {
                    tmp.copyTo(cache, overwrite = true)
                } catch (e: Exception) {
                    // 覆盖写可能已截断旧缓存：宁可删掉残缺产物，也不能留下 length > 0 的
                    // 半份正文被下次 "存在且非空即复用" 直接命中
                    cache.delete()
                    throw e
                }
                tmp.delete()
            }
        } catch (e: BookFormatException) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            throw BookFormatException("格式转换失败: ${e.message ?: "未知错误"}")
        }
        return cache
    }

    /** PalmDOC LZ77 解压，实现见 [MobiFormat.palmDocDecompress] */
    internal fun palmDocDecompress(data: ByteArray): ByteArray = MobiFormat.palmDocDecompress(data)

    /** 剥离文本记录尾随数据，实现见 [MobiFormat.trimTrailingData] */
    internal fun trimTrailingData(data: ByteArray, extraFlags: Int): ByteArray =
        MobiFormat.trimTrailingData(data, extraFlags)

    internal fun readHeaderBytes(context: Context, uri: Uri, count: Int): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                val buffered = if (raw is BufferedInputStream) raw else BufferedInputStream(raw, count)
                val header = ByteArray(count)
                var filled = 0
                while (filled < count) {
                    val n = buffered.read(header, filled, count - filled)
                    if (n <= 0) break
                    filled += n
                }
                if (filled <= 0) null else header.copyOf(filled)
            }
        } catch (_: Exception) {
            null
        }
    }
}
