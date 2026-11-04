package com.watchreader

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.StandardCharsets

/**
 * HTML → 行式纯文本模块：流式标签剥离（[HtmlTextStripper]）、字符集探测与增量解码喂入。
 *
 * 职责边界：MOBI 正文与 .html/.htm/.xhtml 文件共用同一条剥离流水线；字符集判定顺序固定为
 * BOM → meta charset → 既有 UTF-8/GB18030 启发式，返回值携带需要跳过的 BOM 字节数。
 * 本模块不认识任何电子书容器格式：解码器与字节来源都由调用方提供。
 */
internal object HtmlFormat {

    private val HTML_META_CHARSET_REGEX =
        Regex("""(?i)charset\s*=\s*["']?\s*([A-Za-z0-9_.:-]+)""")

    fun convertToText(input: InputStream, out: Appendable, charsetName: String?) {
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
        } ?: detectCharset(sample, sampleLen)
        buffered.reset()
        if (bomSkip > 0) skipBom(buffered, bomSkip)

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

    /** 采样头部若干字节把 BOM 读掉；BOM 字节数来自探测结果，最多 3 字节 */
    private fun skipBom(stream: InputStream, count: Int) {
        repeat(count) {
            if (stream.read() < 0) return
        }
    }

    fun detectCharset(sample: ByteArray, length: Int): Pair<Charset, Int> {
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

    fun feedDecoded(
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
}

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
    private var pendingBrackets = 0

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
                // ']' 先按待定态挂着：CDATA 的正确出口是 "]]>"，逐字立即追加会把
                // 收尾的两个 ']' 漏进正文（旧实现在此多输出 "]]"，属真实缺陷）
                c == ']' -> pendingBrackets++
                c == '>' && pendingBrackets >= 2 -> {
                    mode = Mode.TEXT
                    pendingBrackets = 0
                }
                else -> {
                    if (pendingBrackets > 0) {
                        val held = pendingBrackets
                        pendingBrackets = 0
                        if (skipUntil == null) repeat(held) { text.append(']') }
                    }
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
        out.append('\n')
    }

    private fun appendSpace() {
        if (text.isNotEmpty() && text[text.length - 1] != ' ') text.append(' ')
    }

    private fun flushText() {
        if (text.isEmpty()) return
        val decoded = EpubParser.decodeHtmlEntities(text.toString())
        out.append(decoded)
        text.setLength(0)
    }
}
