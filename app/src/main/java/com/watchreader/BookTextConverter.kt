package com.watchreader

import android.content.Context
import android.net.Uri
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Writer
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharsetDecoder
import java.nio.charset.StandardCharsets
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
 * 多格式电子书流式转换引擎：MOBI/AZW3、FB2、HTML → 纯文本
 *
 * 性能设计（大文件恒定内存，不整书驻留）：
 * - MOBI：按 PDB 记录表逐条读取（每条约 4KB），先剥尾随数据（参考 KindleUnpack 算法）
 *   再 PalmDOC 解压，经 CharsetDecoder 增量解码（多字节字符跨记录自动续接），喂入流式
 *   HTML 剥离器边解析边输出；DRM 与 HUFF/CDIC 压缩给出明确失败而不尝试。
 * - FB2：XmlPullParser 单趟流解析，跳过 description 元数据、base64 二进制与非正文本体，
 *   主 body 结束即提前终止，避免为注释与封面数据浪费解析时间。
 * - HTML：采样头部探测字符集（BOM / meta charset / 编码启发式），流式标签剥离输出。
 * - 转换产物为 UTF-8 文本缓存，二次打开零转换；章节索引、封顶分节与分块读取
 *   完全复用既有 TXT 流式内核，峰值内存与 TXT 路径同级。
 */
object BookTextConverter {

    private const val MOBI_PDB_HEADER_SIZE = 78
    private const val MOBI_RECORD_ENTRY_SIZE = 8
    private const val MAX_DECOMPRESSED_RECORD_BYTES = 1 shl 22

    private val HTML_META_CHARSET_REGEX =
        Regex("""(?i)charset\s*=\s*["']?\s*([A-Za-z0-9_.:-]+)""")

    /** 按扩展名 + 可选头部魔数识别格式；未识别回落 TXT（保持既有行为） */
    fun resolveFormat(fileName: String, header: ByteArray? = null): BookFormat {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        BookFormat.fromExtension(ext)?.let { return it }

        if (header != null) {
            if (header.size >= 68 && hasAsciiTag(header, 60, "BOOK") && hasAsciiTag(header, 64, "MOBI")) {
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
            BookFormat.MOBI -> convertMobiToText(input, out)
            BookFormat.FB2 -> convertFb2ToText(input, out)
            BookFormat.HTML -> convertHtmlToText(input, out, null)
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
                tmp.copyTo(cache, overwrite = true)
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

    // ═════════════════════════════════════════════════════════════
    //  MOBI / AZW3（PalmDOC 容器）
    // ═════════════════════════════════════════════════════════════

    private fun convertMobiToText(input: InputStream, out: Writer) {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, 65536)

        val pdbHeader = readExactly(buffered, MOBI_PDB_HEADER_SIZE) { "文件过小，不是有效的 MOBI/AZW3 文件" }
        if (!hasAsciiTag(pdbHeader, 60, "BOOK") || !hasAsciiTag(pdbHeader, 64, "MOBI")) {
            throw BookFormatException("不是有效的 MOBI/AZW3 文件")
        }
        val numRecords = u16be(pdbHeader, 76)
        if (numRecords < 2) throw BookFormatException("MOBI 记录表为空")

        val tableBytes = readExactly(buffered, numRecords * MOBI_RECORD_ENTRY_SIZE) { "MOBI 记录表被截断" }
        val offsets = IntArray(numRecords)
        for (i in 0 until numRecords) {
            val off = u32be(tableBytes, i * MOBI_RECORD_ENTRY_SIZE)
            if (off < 0) throw BookFormatException("MOBI 文件过大，无法解析")
            offsets[i] = off
        }
        val expectedTableEnd = MOBI_PDB_HEADER_SIZE + numRecords * MOBI_RECORD_ENTRY_SIZE
        if (offsets[0] < expectedTableEnd) throw BookFormatException("MOBI 记录表损坏")
        if (offsets[0] > expectedTableEnd) skipFully(buffered, (offsets[0] - expectedTableEnd).toLong())

        val rec0 = readRange(buffered, offsets, 0)
        if (rec0.size < 16) throw BookFormatException("MOBI 头部损坏")

        val compression = u16be(rec0, 0)
        val textRecordCount = u16be(rec0, 8)
        val crypto = u16be(rec0, 12)
        if (crypto != 0) throw BookFormatException("文件受 DRM 保护，无法阅读")
        when (compression) {
            1, 2 -> {}
            0x4448 -> throw BookFormatException("暂不支持 HUFF/CDIC 压缩的 MOBI 文件，请用 Calibre 重新转换后导入")
            else -> throw BookFormatException("不支持的 MOBI 压缩类型: $compression")
        }

        var extraFlags = 0
        var charset = StandardCharsets.UTF_8
        if (rec0.size >= 40 && hasAsciiTag(rec0, 16, "MOBI")) {
            val mobiLength = u32be(rec0, 20)
            // codepage 1252 → windows-1252，65001 → UTF-8（其余回落 UTF-8）
            if (u32be(rec0, 28) == 1252) charset = Charset.forName("windows-1252")
            // 额外记录数据标志（参考 KindleUnpack：mobi_length >= 0xE4 且 min version >= 5）
            if (mobiLength >= 0xE4 && rec0.size >= 108 && u32be(rec0, 104) >= 5 && rec0.size >= 244) {
                extraFlags = u16be(rec0, 242)
            }
        }

        val count = minOf(textRecordCount, numRecords - 1)
        if (count < 1) throw BookFormatException("MOBI 无正文记录")

        val stripper = HtmlTextStripper(out)
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        var carry = ByteArray(0)
        for (i in 1..count) {
            val record = readRange(buffered, offsets, i)
            val trimmed = if (extraFlags != 0) trimTrailingData(record, extraFlags) else record
            val raw = if (compression == 2) palmDocDecompress(trimmed) else trimmed
            if (raw.isEmpty()) continue
            carry = decodeFeed(decoder, carry + raw, false, stripper)
        }
        if (carry.isNotEmpty()) decodeFeed(decoder, carry, true, stripper)
        stripper.finish()
        // 正文记录之后的资源记录（图片/字体等）不读取，流由调用方关闭
    }

    /** 增量解码并喂给剥离器；endOfInput=false 时未完结的多字节序列留在返回值中跨记录续接 */
    private fun decodeFeed(
        decoder: CharsetDecoder,
        bytes: ByteArray,
        endOfInput: Boolean,
        stripper: HtmlTextStripper
    ): ByteArray {
        val inBuf = ByteBuffer.wrap(bytes)
        val charBuf = CharArray(8192)
        while (true) {
            val outBuf = CharBuffer.wrap(charBuf)
            val result = decoder.decode(inBuf, outBuf, endOfInput)
            if (outBuf.position() > 0) stripper.feed(charBuf, outBuf.position())
            if (result.isUnderflow) break
            if (!result.isOverflow) break
        }
        if (endOfInput) {
            val outBuf = CharBuffer.wrap(charBuf)
            decoder.flush(outBuf)
            if (outBuf.position() > 0) stripper.feed(charBuf, outBuf.position())
        }
        val remaining = inBuf.remaining()
        return if (remaining > 0) {
            val tail = ByteArray(remaining)
            inBuf.get(tail)
            tail
        } else {
            ByteArray(0)
        }
    }

    /**
     * PalmDOC LZ77 解压（参考 KindleUnpack PalmdocReader）：
     * 1~8 → 后续 n 字节为字面串；<128 → 字面；>=192 → 空格 + (c^0x80)；
     * 128~191 → 双字节回引 (distance=c12>>3 & 0x7FF, length=(c12&7)+3)，允许重叠拷贝。
     */
    internal fun palmDocDecompress(data: ByteArray): ByteArray {
        var buf = ByteArray(data.size * 2 + 4096)
        var len = 0

        fun ensure(extra: Int) {
            if (len + extra > buf.size) {
                var newSize = buf.size * 2
                while (newSize < len + extra) newSize *= 2
                buf = buf.copyOf(newSize)
            }
        }

        var p = 0
        val n = data.size
        while (p < n) {
            if (len > MAX_DECOMPRESSED_RECORD_BYTES) throw BookFormatException("MOBI 记录解压异常膨胀")
            val c = data[p].toInt() and 0xFF
            p++
            when {
                c in 1..8 -> {
                    val take = minOf(c, n - p)
                    if (take > 0) {
                        ensure(take)
                        System.arraycopy(data, p, buf, len, take)
                        len += take
                        p += take
                    }
                }
                c < 128 -> {
                    ensure(1)
                    buf[len++] = c.toByte()
                }
                c >= 192 -> {
                    ensure(2)
                    buf[len++] = 0x20
                    buf[len++] = (c and 0x7F).toByte()
                }
                else -> {
                    if (p >= n) break
                    val pair = (c shl 8) or (data[p].toInt() and 0xFF)
                    p++
                    val distance = (pair shr 3) and 0x7FF
                    val length = (pair and 7) + 3
                    if (distance in 1..len) {
                        ensure(length)
                        repeat(length) {
                            buf[len] = buf[len - distance]
                            len++
                        }
                    }
                    // distance 越界视为损坏数据，跳过该回引
                }
            }
        }
        return buf.copyOf(len)
    }

    /**
     * 剥离文本记录的尾随数据（参考 KindleUnpack getRawML）：
     * extraFlags 低位起每个置位（除 bit0）对应一个反向变长整数描述的尾随条目；
     * bit0 为 multibyte 重叠标记，按末字节低 2 位 +1 剥离。顺序：先条目后 multibyte。
     */
    internal fun trimTrailingData(data: ByteArray, extraFlags: Int): ByteArray {
        var end = data.size

        fun sizeOfTrailingEntry(): Int {
            // 参考实现按末 4 字节从左到右累加，遇高位字节重置
            var num = 0
            val start = maxOf(0, end - 4)
            for (i in start until end) {
                val v = data[i].toInt() and 0xFF
                if (v and 0x80 != 0) num = 0
                num = (num shl 7) or (v and 0x7F)
            }
            return num
        }

        var flags = extraFlags
        var trailers = 0
        while (flags > 1) {
            if (flags and 2 != 0) trailers++
            flags = flags shr 1
        }
        repeat(trailers) {
            if (end <= 0) return@repeat
            end -= sizeOfTrailingEntry()
            if (end < 0) end = 0
        }
        if (extraFlags and 1 != 0 && end > 0) {
            end -= (data[end - 1].toInt() and 3) + 1
            if (end < 0) end = 0
        }
        return if (end == data.size) data else data.copyOf(end)
    }

    // ═════════════════════════════════════════════════════════════
    //  流式 HTML 标签剥离器（MOBI 正文与 HTML 文件共用）
    // ═════════════════════════════════════════════════════════════

    /**
     * 增量把 HTML 转为行式纯文本：块级标签与 <br> 输出换行，script/style/title 内容
     * 连同注释整体剔除，文本中的换行与制表符折叠为空格。逐字符状态机，内存占用恒定。
     */
    internal class HtmlTextStripper(private val out: Appendable) {

        private enum class Mode { TEXT, TAG, COMMENT, CDATA }

        private val blockTagNames = setOf(
            "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "tr", "li", "blockquote",
            "section", "article", "pre", "header", "footer", "table", "td", "th",
            "ul", "ol", "dl", "dd", "dt", "body", "html", "head", "figure", "figcaption",
            "main", "nav", "aside", "form", "center", "pagebreak"
        )
        private val rawSkipTags = setOf("script", "style", "title")

        private val text = java.lang.StringBuilder(256)
        private val tagBuf = java.lang.StringBuilder(32)
        private var mode = Mode.TEXT
        private var skipUntil: String? = null
        private var prev1 = ' '
        private var prev2 = ' '

        fun feed(chars: CharArray, length: Int) {
            for (i in 0 until length) feedChar(chars[i])
        }

        fun finish() {
            flushText()
        }

        private fun feedChar(c: Char) {
            when (mode) {
                Mode.TEXT -> when {
                    c == '<' -> {
                        flushText()
                        mode = Mode.TAG
                        tagBuf.setLength(0)
                        tagBuf.append(c)
                    }
                    skipUntil != null -> {}
                    c == '\n' || c == '\r' || c == '\t' -> appendSpace()
                    else -> text.append(c)
                }
                Mode.TAG -> {
                    tagBuf.append(c)
                    when {
                        tagBuf.length == 4 && isSequence(tagBuf, "<!--") -> {
                            mode = Mode.COMMENT
                            prev1 = ' '; prev2 = ' '
                        }
                        tagBuf.length == 9 && tagBuf.toString().equals("<![CDATA[", ignoreCase = true) -> {
                            mode = Mode.CDATA
                            prev1 = ' '; prev2 = ' '
                        }
                        c == '>' -> {
                            processTag(tagBuf.toString())
                            if (mode == Mode.TAG) mode = Mode.TEXT
                        }
                        tagBuf.length > 512 -> {
                            // 孤立 '<' 误入标签态：按字面文本回收，避免吞掉余下正文
                            text.append(tagBuf)
                            mode = Mode.TEXT
                        }
                    }
                }
                Mode.COMMENT -> when {
                    prev1 == '-' && prev2 == '-' && c == '>' -> {
                        mode = Mode.TEXT
                        prev1 = ' '; prev2 = ' '
                    }
                    else -> {
                        prev2 = prev1
                        prev1 = c
                    }
                }
                Mode.CDATA -> when {
                    prev1 == ']' && prev2 == ']' && c == '>' -> {
                        mode = Mode.TEXT
                        prev1 = ' '; prev2 = ' '
                    }
                    else -> {
                        prev2 = prev1
                        prev1 = c
                        if (skipUntil == null) {
                            if (c != '\n' && c != '\r' && c != '\t') text.append(c) else appendSpace()
                        }
                    }
                }
            }
        }

        private fun isSequence(sb: java.lang.StringBuilder, expected: String): Boolean =
            sb.toString() == expected

        private fun processTag(raw: String) {
            if (raw.length < 2) return
            var s = raw.substring(1, raw.length - 1)
            var selfClosed = false
            if (s.endsWith("/")) {
                selfClosed = true
                s = s.dropLast(1)
            }
            var i = 0
            var isClose = false
            if (i < s.length && s[i] == '/') {
                isClose = true
                i++
            }
            val nameSb = StringBuilder(16)
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == ':' || s[i] == '_' || s[i] == '-')) {
                nameSb.append(s[i])
                i++
            }
            val fullName = nameSb.toString().lowercase()
            if (fullName.isEmpty()) return
            val localName = fullName.substringAfterLast(':')

            if (isClose) {
                if (skipUntil == fullName || skipUntil == localName) skipUntil = null
                return
            }
            // 跳过态中的内嵌标签不产生任何输出
            if (skipUntil != null) return

            when (localName) {
                "br" -> emitNewline()
                "hr" -> {
                    emitNewline()
                    text.append("—— ——")
                    emitNewline()
                }
                in rawSkipTags -> if (!selfClosed) skipUntil = localName
                else -> if (localName in blockTagNames) emitNewline()
            }
        }

        private fun emitNewline() {
            flushText()
            try {
                out.append('\n')
            } catch (_: Exception) {
            }
        }

        private fun appendSpace() {
            if (text.isNotEmpty() && text[text.length - 1] != ' ') text.append(' ')
        }

        private fun flushText() {
            if (text.isEmpty()) return
            val decoded = EpubParser.decodeHtmlEntities(text.toString())
            try {
                out.append(decoded)
            } catch (_: Exception) {
            }
            text.setLength(0)
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  FB2（FictionBook XML）
    // ═════════════════════════════════════════════════════════════

    private fun convertFb2ToText(input: InputStream, out: Writer) {
        val parser = createPullParser()
        parser.setInput(input, null)

        val paragraphTags = setOf("p", "v", "text-author")
        // -1 表示未处于跳过区；跳过时 parser.depth > skipBelowDepth 的内容全部丢弃
        var skipBelowDepth = -1
        var mainBodyDepth = -1
        var captureDepth = Int.MAX_VALUE
        val text = java.lang.StringBuilder(256)

        fun localName(): String = parser.name.substringAfterLast(':').lowercase()

        fun attributeName(name: String): String? {
            for (i in 0 until parser.attributeCount) {
                if (parser.getAttributeName(i).equals(name, ignoreCase = true)) {
                    return parser.getAttributeValue(i)
                }
            }
            return null
        }

        fun emitParagraph() {
            val sb = java.lang.StringBuilder(text.length)
            var lastWasSpace = false
            for (i in 0 until text.length) {
                val ch = text[i]
                if (ch.isWhitespace()) {
                    lastWasSpace = true
                } else {
                    if (lastWasSpace && sb.isNotEmpty()) sb.append(' ')
                    sb.append(ch)
                    lastWasSpace = false
                }
            }
            out.append(sb)
            out.append('\n')
        }

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            val skipping = skipBelowDepth != -1 && parser.depth > skipBelowDepth
            when (event) {
                XmlPullParser.START_TAG -> when (localName()) {
                    "binary" -> if (skipBelowDepth == -1) skipBelowDepth = parser.depth - 1
                    "body" -> when {
                        mainBodyDepth == -1 && attributeName("name").isNullOrEmpty() ->
                            mainBodyDepth = parser.depth
                        skipBelowDepth == -1 -> skipBelowDepth = parser.depth - 1
                    }
                    "empty-line" -> if (!skipping && mainBodyDepth != -1 && parser.depth > mainBodyDepth) {
                        out.append('\n')
                    }
                    "p", "v", "text-author" ->
                        if (!skipping && mainBodyDepth != -1 && parser.depth > mainBodyDepth) {
                            captureDepth = parser.depth
                            text.setLength(0)
                        }
                }
                XmlPullParser.TEXT -> if (!skipping && parser.depth >= captureDepth) {
                    text.append(parser.text)
                }
                XmlPullParser.END_TAG -> {
                    val name = localName()
                    if (!skipping && parser.depth >= captureDepth && name in paragraphTags) {
                        emitParagraph()
                        captureDepth = Int.MAX_VALUE
                        text.setLength(0)
                    }
                    when (name) {
                        "binary" -> if (parser.depth == skipBelowDepth + 1) skipBelowDepth = -1
                        "body" -> when {
                            parser.depth == mainBodyDepth ->
                                // 主 body 结束即终止：跳过其后的 notes 本体与 base64 二进制
                                return
                            parser.depth == skipBelowDepth + 1 -> skipBelowDepth = -1
                        }
                    }
                }
            }
            event = parser.next()
        }
    }

    // ═════════════════════════════════════════════════════════════
    //  HTML 文件
    // ═════════════════════════════════════════════════════════════

    private fun convertHtmlToText(input: InputStream, out: Writer, charsetName: String?) {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, 65536)

        buffered.mark(8200)
        val sample = ByteArray(2048)
        var sampleLen = 0
        while (sampleLen < sample.size) {
            val n = buffered.read(sample, sampleLen, sample.size - sampleLen)
            if (n <= 0) break
            sampleLen += n
        }
        val (charset, bomSkip) = charsetName?.let {
            runCatching { Charset.forName(it) }.getOrNull()?.let { cs -> cs to 0 }
        } ?: detectHtmlCharset(sample, sampleLen)
        buffered.reset()
        if (bomSkip > 0) skipFully(buffered, bomSkip.toLong())

        val reader = InputStreamReader(buffered, charset)
        val stripper = HtmlTextStripper(out)
        val buf = CharArray(16384)
        while (true) {
            val n = reader.read(buf)
            if (n <= 0) break
            stripper.feed(buf, n)
        }
        stripper.finish()
    }

    /** BOM → meta charset → 既有 UTF-8/GB18030 启发式，返回（字符集, 需跳过的 BOM 字节数） */
    private fun detectHtmlCharset(sample: ByteArray, length: Int): Pair<Charset, Int> {
        if (length >= 3 && sample[0] == 0xEF.toByte() && sample[1] == 0xBB.toByte() && sample[2] == 0xBF.toByte()) {
            return StandardCharsets.UTF_8 to 3
        }
        if (length >= 2 && sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte()) {
            return StandardCharsets.UTF_16LE to 2
        }
        if (length >= 2 && sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte()) {
            return StandardCharsets.UTF_16BE to 2
        }
        val head = String(sample, 0, minOf(length, sample.size), StandardCharsets.ISO_8859_1)
        HTML_META_CHARSET_REGEX.find(head)?.let { match ->
            runCatching { Charset.forName(match.groupValues[1].lowercase()) }.getOrNull()?.let { return it to 0 }
        }
        val guessed = detectEncoding(sample, length)
        return runCatching { Charset.forName(guessed) }.getOrDefault(StandardCharsets.UTF_8) to 0
    }

    // ═════════════════════════════════════════════════════════════
    //  底层字节流工具
    // ═════════════════════════════════════════════════════════════

    private fun createPullParser(): XmlPullParser {
        return try {
            val kxmlClass = Class.forName("org.kxml2.io.KXmlParser")
            kxmlClass.getDeclaredConstructor().newInstance() as XmlPullParser
        } catch (_: Throwable) {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            factory.newPullParser()
        }
    }

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

    private inline fun readExactly(stream: InputStream, count: Int, onError: () -> String): ByteArray {
        val out = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val n = stream.read(out, filled, count - filled)
            if (n <= 0) throw BookFormatException(onError())
            filled += n
        }
        return out
    }

    /** 依据记录表边界从流中精确读取第 [index] 条记录（最后一条读到 EOF） */
    private fun readRange(stream: InputStream, offsets: IntArray, index: Int): ByteArray {
        val start = offsets[index]
        val end = if (index + 1 < offsets.size) offsets[index + 1] else -1
        if (end >= 0) {
            if (end < start) throw BookFormatException("MOBI 记录表损坏")
            return readExactly(stream, end - start) { "MOBI 文件被截断" }
        }
        val buf = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val n = stream.read(chunk)
            if (n <= 0) break
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    private fun skipFully(stream: InputStream, total: Long) {
        var remaining = total
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val n = stream.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (n <= 0) throw BookFormatException("MOBI 文件被截断")
            remaining -= n
        }
    }

    private fun hasAsciiTag(data: ByteArray, at: Int, tag: String): Boolean {
        if (at + tag.length > data.size) return false
        for (i in tag.indices) {
            if (data[at + i] != tag[i].code.toByte()) return false
        }
        return true
    }

    private fun u16be(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    private fun u32be(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
                ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}
