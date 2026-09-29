package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.CharArrayWriter
import java.nio.charset.StandardCharsets

/**
 * BookTextConverter 单元测试：PalmDOC 解压、MOBI 容器、FB2/HTML 流式转换与格式识别。
 * 全部测试基于纯 JVM 字节流，不依赖 Android 框架。
 */
class BookTextConverterTest {

    // ═══════════════════════ 测试夹具构建 ═══════════════════════

    private fun u16(v: Int) = byteArrayOf(
        ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
    )

    private fun u32(v: Int) = byteArrayOf(
        ((v shr 24) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte()
    )

    private fun put(src: ByteArray, dst: ByteArray, at: Int) {
        System.arraycopy(src, 0, dst, at, src.size)
    }

    /** 测试用 PalmDOC 压缩器：仅字面编码（9~127 原样，其余 0x01 转义），保证解压可还原 */
    private fun palmDocCompressSimple(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 16)
        for (b in data) {
            val v = b.toInt() and 0xFF
            if (v in 9..127) {
                out.write(v)
            } else {
                out.write(1)
                out.write(v)
            }
        }
        return out.toByteArray()
    }

    /**
     * 构建最小合法 MOBI 文件。
     *
     * @param plainChunkSize 压缩前明文分块大小（真实格式为 4096，测试允许更小以构造多记录）
     * @param perRecordPost  对每条正文记录追加尾随数据等干扰字节
     */
    private fun buildMobi(
        rawMl: ByteArray,
        compression: Int = 1,
        codepage: Int = 65001,
        crypto: Int = 0,
        extraFlags: Int = 0,
        plainChunkSize: Int = 4096,
        perRecordPost: ((ByteArray) -> ByteArray)? = null
    ): ByteArray {
        val plainChunks = rawMl.toList().chunked(plainChunkSize).map { it.toByteArray() }
        val textRecords = plainChunks.map { chunk ->
            val body = if (compression == 2) palmDocCompressSimple(chunk) else chunk
            perRecordPost?.invoke(body) ?: body
        }

        // record 0：PalmDOC 头 + MOBI 头（长度 232，0xF2 处写 extraFlags）
        val rec0 = ByteArray(248)
        put(u16(compression), rec0, 0)
        put(u32(rawMl.size), rec0, 4)
        put(u16(textRecords.size), rec0, 8)
        put(u16(4096), rec0, 10)
        put(u16(crypto), rec0, 12)
        put("MOBI".toByteArray(StandardCharsets.US_ASCII), rec0, 16)
        put(u32(232), rec0, 20)
        put(u32(2), rec0, 24)
        put(u32(codepage), rec0, 28)
        put(u32(6), rec0, 36)
        put(u32(6), rec0, 104)
        put(u16(extraFlags), rec0, 242)

        val records = listOf(rec0) + textRecords
        val tableSize = 78 + 8 * records.size

        val out = ByteArrayOutputStream()
        val name = "testbook".toByteArray(StandardCharsets.US_ASCII).copyOf(32)
        out.write(name)
        out.write(ByteArray(60 - 32))
        out.write("BOOK".toByteArray(StandardCharsets.US_ASCII))
        out.write("MOBI".toByteArray(StandardCharsets.US_ASCII))
        out.write(ByteArray(76 - 68))
        out.write(u16(records.size))

        var offset = tableSize
        records.forEach { rec ->
            out.write(u32(offset))
            out.write(byteArrayOf(0, 0, 0, 0))
            offset += rec.size
        }
        records.forEach { rec -> out.write(rec) }
        return out.toByteArray()
    }

    private fun convert(format: BookFormat, bytes: ByteArray): String {
        val writer = CharArrayWriter()
        BookTextConverter.convertToText(ByteArrayInputStream(bytes), format, writer)
        return writer.toString()
    }

    // ═══════════════════════ PalmDOC 解压 ═══════════════════════

    @Test
    fun testPalmDocLiteralRun() {
        val data = byteArrayOf(0x05, 0x48, 0x65, 0x6C, 0x6C, 0x6F) // 5 字面 + "Hello"
        assertEquals("Hello", String(BookTextConverter.palmDocDecompress(data), StandardCharsets.ISO_8859_1))
    }

    @Test
    fun testPalmDocRawLiterals() {
        assertEquals("A", String(BookTextConverter.palmDocDecompress(byteArrayOf(0x41))))
        assertEquals("\u0000", String(BookTextConverter.palmDocDecompress(byteArrayOf(0x00))))
    }

    @Test
    fun testPalmDocSpacePair() {
        // 0xC1 → 空格 + (0xC1 ^ 0x80) = " A"
        assertEquals(" A", String(BookTextConverter.palmDocDecompress(byteArrayOf(0xC1.toByte()))))
    }

    @Test
    fun testPalmDocLzCopy() {
        // "abc" + 回引 (distance=3, len=3) → "abcabc"
        val data = byteArrayOf(0x03, 0x61, 0x62, 0x63, 0x80.toByte(), 0x18)
        assertEquals("abcabc", String(BookTextConverter.palmDocDecompress(data), StandardCharsets.ISO_8859_1))
    }

    @Test
    fun testPalmDocOverlapRle() {
        // "a" + 回引 (distance=1, len=4) → "aaaaa"
        val data = byteArrayOf(0x01, 0x61, 0x80.toByte(), 0x09)
        assertEquals("aaaaa", String(BookTextConverter.palmDocDecompress(data), StandardCharsets.ISO_8859_1))
    }

    @Test
    fun testPalmDocLiteralRunAfterLz() {
        // LZ 回引后紧跟字面串，验证游标推进正确
        val data = byteArrayOf(0x03, 0x61, 0x62, 0x63, 0x80.toByte(), 0x18, 0x02, 0x64, 0x65)
        assertEquals("abcabcde", String(BookTextConverter.palmDocDecompress(data), StandardCharsets.ISO_8859_1))
    }

    // ═══════════════════════ 尾随数据裁剪 ═══════════════════════

    @Test
    fun testTrimNoFlags() {
        val data = "hello".toByteArray()
        assertTrue(BookTextConverter.trimTrailingData(data, 0).contentEquals(data))
    }

    @Test
    fun testTrimSingleTrailerEntry() {
        // 尾随条目大小 1（varint 0x81），本体 "hello"
        val data = "hello".toByteArray() + byteArrayOf(0x81.toByte())
        assertEquals("hello", String(BookTextConverter.trimTrailingData(data, 0b10)))
    }

    @Test
    fun testTrimSizedTrailerEntry() {
        // 尾随条目大小 3（varint 0x83）：剥掉 0x83、'b'、'a'
        val data = "helloab".toByteArray() + byteArrayOf(0x83.toByte())
        assertEquals("hello", String(BookTextConverter.trimTrailingData(data, 0b10)))
    }

    @Test
    fun testTrimMultibyteOverlap() {
        // multibyte 标记 (0x00 → (0&3)+1=1) 只剥掉标记自身
        val data = "hello".toByteArray() + byteArrayOf(0x00)
        assertEquals("hello", String(BookTextConverter.trimTrailingData(data, 0b01)))
    }

    @Test
    fun testTrimTrailerThenMultibyte() {
        // 先剥尾随条目（0x81 → 1），再剥 multibyte（0x00 → 1）
        val data = "hello".toByteArray() + byteArrayOf(0x00, 0x81.toByte())
        assertEquals("hello", String(BookTextConverter.trimTrailingData(data, 0b11)))
    }

    // ═══════════════════════ MOBI 容器转换 ═══════════════════════

    @Test
    fun testMobiUncompressedUtf8Conversion() {
        val rawMl = "<html><body>" +
                "<h2>第一章 初见</h2><p>夜幕降临，繁星点点。</p>" +
                "<mbp:pagebreak/>" +
                "<h2>第二章 重逢</h2><p>少年背上行囊。</p>" +
                "</body></html>"
        val mobi = buildMobi(rawMl.toByteArray(StandardCharsets.UTF_8))
        val text = convert(BookFormat.MOBI, mobi)

        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue("应包含第一章标题行", lines.contains("第一章 初见"))
        assertTrue(lines.contains("第二章 重逢"))
        assertTrue(lines.contains("夜幕降临，繁星点点。"))
        assertTrue(lines.contains("少年背上行囊。"))
        assertFalse("不得残留 HTML 标签", text.contains('<'))
        assertFalse("不得残留 pagebreak 字样", text.contains("pagebreak", ignoreCase = true))
    }

    @Test
    fun testMobiConvertedTextFeedsChapterDetector() {
        val rawMl = "<p>序章引言内容。</p>" +
                "<h2>第一章 初见</h2><p>第一段。</p>" +
                "<h2>第二章 重逢</h2><p>第二段。</p>"
        val mobi = buildMobi(rawMl.toByteArray(StandardCharsets.UTF_8))
        val text = convert(BookFormat.MOBI, mobi)

        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val (chapters, total) = detectChaptersFromInputStream(ByteArrayInputStream(bytes), "UTF-8")
        assertTrue("章节检测应识别出章标题，实际: $chapters", chapters.size >= 2)
        assertTrue(chapters.any { it.title == "第一章 初见" })
        assertTrue(chapters.any { it.title == "第二章 重逢" })
        assertTrue(total > 0)
    }

    @Test
    fun testMobiPalmDocCompression() {
        val rawMl = "<p>Compression round trip: 亚洲文明对话大会。</p><p>Second paragraph.</p>"
        val mobi = buildMobi(rawMl.toByteArray(StandardCharsets.UTF_8), compression = 2, plainChunkSize = 2048)
        val text = convert(BookFormat.MOBI, mobi)
        assertTrue(text.contains("Compression round trip: 亚洲文明对话大会。"))
        assertTrue(text.contains("Second paragraph."))
    }

    @Test
    fun testMobiWindows1252Codepage() {
        val rawMl = "<p>Café naïve — déjà vu</p>"
        val mobi = buildMobi(rawMl.toByteArray(charset("windows-1252")), codepage = 1252)
        val text = convert(BookFormat.MOBI, mobi)
        assertTrue(text.contains("Café naïve — déjà vu"))
    }

    @Test
    fun testMobiMultibyteCharSpanningRecordBoundary() {
        // 构造第二个多字节字符恰好被 4096 记录边界劈开的字节布局
        val prefix = "<p>" + "x".repeat(4091) // 3 + 4091 = 4094，『好』横跨 4094~4096
        val rawMl = prefix + "好".repeat(3) + "</p>"
        val mobi = buildMobi(rawMl.toByteArray(StandardCharsets.UTF_8))
        val text = convert(BookFormat.MOBI, mobi)

        assertFalse("跨记录字符不得解码为替换符", text.contains('\uFFFD'))
        val goodCount = text.count { it == '好' }
        assertEquals("三个『好』必须完整保留", 3, goodCount)
    }

    @Test
    fun testMobiTrailingDataStripped() {
        val rawMl = "<p>纯净正文内容。</p>"
        val mobi = buildMobi(
            rawMl.toByteArray(StandardCharsets.UTF_8),
            extraFlags = 0b10,
            perRecordPost = { it + byteArrayOf(0x81.toByte()) }
        )
        val text = convert(BookFormat.MOBI, mobi)
        assertTrue(text.contains("纯净正文内容。"))
        assertFalse("尾随数据不得污染正文", text.contains('\u0000'))
    }

    @Test
    fun testMobiDrmRejected() {
        val mobi = buildMobi("<p>x</p>".toByteArray(), crypto = 2)
        try {
            convert(BookFormat.MOBI, mobi)
            fail("DRM 文件必须抛出 BookFormatException")
        } catch (e: BookFormatException) {
            assertTrue(e.message!!.contains("DRM"))
        }
    }

    @Test
    fun testMobiHuffCdicRejected() {
        val mobi = buildMobi("<p>x</p>".toByteArray(), compression = 0x4448)
        try {
            convert(BookFormat.MOBI, mobi)
            fail("HUFF/CDIC 压缩必须抛出 BookFormatException")
        } catch (e: BookFormatException) {
            assertTrue(e.message!!.contains("HUFF"))
        }
    }

    @Test
    fun testMobiInvalidContainerRejected() {
        try {
            convert(BookFormat.MOBI, "这不是一个 MOBI 文件".toByteArray())
            fail("非 MOBI 容器必须抛出 BookFormatException")
        } catch (e: BookFormatException) {
            // 预期路径
        }
    }

    // ═══════════════════════ FB2 转换 ═══════════════════════

    @Test
    fun testFb2Conversion() {
        val fb2 = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
              <description>
                <title-info>
                  <book-title>测试书名</book-title>
                  <author><first-name>张</first-name><last-name>三</last-name></author>
                </title-info>
              </description>
              <body>
                <section>
                  <title><p>第一章 启程</p></title>
                  <p>  第一段   内容  </p>
                  <empty-line/>
                  <p>第二段<emphasis>强调词</emphasis>结尾</p>
                </section>
                <section>
                  <title><p>第二章 归途</p></title>
                  <p>内容丙</p>
                </section>
              </body>
              <body name="notes">
                <section><p>注释内容不应出现</p></section>
              </body>
              <binary id="cover.jpg" content-type="image/jpeg">QUJDREVGRw==</binary>
            </FictionBook>
        """.trimIndent()

        val text = convert(BookFormat.FB2, fb2.toByteArray(StandardCharsets.UTF_8))
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

        assertTrue(lines.contains("第一章 启程"))
        assertTrue(lines.contains("第一段 内容"))
        assertTrue(lines.contains("第二段强调词结尾"))
        assertTrue(lines.contains("第二章 归途"))
        assertTrue(lines.contains("内容丙"))
        assertFalse("description 元数据不得混入正文", text.contains("测试书名"))
        assertFalse("作者信息不得混入正文", text.contains("张三"))
        assertFalse("notes 本体必须跳过", text.contains("注释内容不应出现"))
        assertFalse("base64 二进制必须跳过", text.contains("QUJDREVGRw=="))
    }

    @Test
    fun testFb2NestedSectionFlattened() {
        val fb2 = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook>
              <body>
                <section>
                  <title><p>第一卷</p></title>
                  <section>
                    <title><p>第一章 起</p></title>
                    <p>嵌套章节正文</p>
                  </section>
                </section>
              </body>
            </FictionBook>
        """.trimIndent()

        val text = convert(BookFormat.FB2, fb2.toByteArray(StandardCharsets.UTF_8))
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue(lines.contains("第一卷"))
        assertTrue(lines.contains("第一章 起"))
        assertTrue(lines.contains("嵌套章节正文"))
    }

    @Test
    fun testFb2BodyWithoutSections() {
        val fb2 = """
            <?xml version="1.0" encoding="utf-8"?>
            <FictionBook>
              <body><p>无章节正文甲</p><p>无章节正文乙</p></body>
            </FictionBook>
        """.trimIndent()
        val text = convert(BookFormat.FB2, fb2.toByteArray(StandardCharsets.UTF_8))
        assertTrue(text.contains("无章节正文甲"))
        assertTrue(text.contains("无章节正文乙"))
    }

    // ═══════════════════════ HTML 转换 ═══════════════════════

    @Test
    fun testHtmlConversion() {
        val html = """
            <html>
              <head><title>页面标题</title><style>body { color: red; }</style></head>
              <body>
                <h1>第一章 网页</h1>
                <p>段落一&amp;二</p>
                <script>var a = "<p>不该出现</p>";</script>
                <p>段落三</p>
              </body>
            </html>
        """.trimIndent()

        val text = convert(BookFormat.HTML, html.toByteArray(StandardCharsets.UTF_8))
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue(lines.contains("第一章 网页"))
        assertTrue(lines.contains("段落一&二"))
        assertTrue(lines.contains("段落三"))
        assertFalse("title 文本必须剔除", text.contains("页面标题"))
        assertFalse("style 内容必须剔除", text.contains("color: red"))
        assertFalse("script 内容必须剔除", text.contains("不该出现"))
    }

    @Test
    fun testHtmlCommentAndDoctypeDropped() {
        val html = "<!DOCTYPE html><!-- 注释内容 --><p>可见正文</p>"
        val text = convert(BookFormat.HTML, html.toByteArray(StandardCharsets.UTF_8))
        assertFalse(text.contains("注释内容"))
        assertFalse(text.contains("DOCTYPE"))
        assertTrue(text.contains("可见正文"))
    }

    @Test
    fun testHtmlCharsetDetectionGb2312() {
        val html = "<html><head><meta charset=\"gb2312\"></head><body><p>中文内容测试</p></body></html>"
        val bytes = html.toByteArray(charset("GB2312"))
        val text = convert(BookFormat.HTML, bytes)
        assertTrue("GB2312 页面必须正确解码", text.contains("中文内容测试"))
    }

    @Test
    fun testHtmlUtf8Bom() {
        val html = "<html><body><p>BOM正文</p></body></html>"
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + html.toByteArray(StandardCharsets.UTF_8)
        val text = convert(BookFormat.HTML, bytes)
        assertTrue(text.contains("BOM正文"))
        assertFalse("BOM 不得输出为正文", text.contains('\uFEFF'))
    }

    // ═══════════════════════ 格式识别 ═══════════════════════

    @Test
    fun testResolveFormatByExtension() {
        assertEquals(BookFormat.EPUB, BookTextConverter.resolveFormat("三体.epub", null))
        assertEquals(BookFormat.MOBI, BookTextConverter.resolveFormat("三体.MOBI", null))
        assertEquals(BookFormat.MOBI, BookTextConverter.resolveFormat("book.azw3", null))
        assertEquals(BookFormat.MOBI, BookTextConverter.resolveFormat("book.azw", null))
        assertEquals(BookFormat.MOBI, BookTextConverter.resolveFormat("book.prc", null))
        assertEquals(BookFormat.FB2, BookTextConverter.resolveFormat("书.FB2", null))
        assertEquals(BookFormat.HTML, BookTextConverter.resolveFormat("page.Html", null))
        assertEquals(BookFormat.HTML, BookTextConverter.resolveFormat("page.htm", null))
        assertEquals(BookFormat.TXT, BookTextConverter.resolveFormat("novel.txt", null))
        assertEquals(BookFormat.TXT, BookTextConverter.resolveFormat("无扩展名", null))
    }

    @Test
    fun testResolveFormatByMagic() {
        val mobiHeader = ByteArray(68)
        System.arraycopy("BOOKMOBI".toByteArray(), 0, mobiHeader, 60, 8)
        assertEquals(BookFormat.MOBI, BookTextConverter.resolveFormat("无扩展名", mobiHeader))

        val zipHeader = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
        assertEquals(BookFormat.EPUB, BookTextConverter.resolveFormat("无扩展名", zipHeader))
    }

    @Test
    fun testSupportedFileNameWhitelist() {
        assertTrue(BookTextConverter.isSupportedFileName("a.txt"))
        assertTrue(BookTextConverter.isSupportedFileName("a.epub"))
        assertTrue(BookTextConverter.isSupportedFileName("a.mobi"))
        assertTrue(BookTextConverter.isSupportedFileName("a.azw3"))
        assertTrue(BookTextConverter.isSupportedFileName("a.fb2"))
        assertTrue(BookTextConverter.isSupportedFileName("a.html"))
        assertFalse(BookTextConverter.isSupportedFileName("a.pdf"))
        assertFalse(BookTextConverter.isSupportedFileName("a.docx"))
        assertFalse(BookTextConverter.isSupportedFileName("noext"))
    }

    @Test
    fun testFormatBadge() {
        assertEquals("EPUB", BookTextConverter.formatBadgeOf("三体.epub"))
        assertEquals("MOBI", BookTextConverter.formatBadgeOf("三体.mobi"))
        assertEquals("FB2", BookTextConverter.formatBadgeOf("书.fb2"))
        assertEquals("HTML", BookTextConverter.formatBadgeOf("page.html"))
        assertEquals("TXT", BookTextConverter.formatBadgeOf("novel.txt"))
    }

    @Test
    fun testCleanBookTitleStripsNewSuffixes() {
        assertEquals("三体", EpubParser.cleanBookTitle("三体.mobi"))
        assertEquals("三体", EpubParser.cleanBookTitle("三体.MOBI"))
        assertEquals("三体", EpubParser.cleanBookTitle("三体.azw3"))
        assertEquals("三体", EpubParser.cleanBookTitle("三体.FB2"))
        assertEquals("三体", EpubParser.cleanBookTitle("三体.html"))
        assertEquals("三体", EpubParser.cleanBookTitle("三体.txt"))
        // 叠加后缀逐层剥除
        assertEquals("三体", EpubParser.cleanBookTitle("三体.fb2.txt"))
        assertEquals("chapter1", EpubParser.cleanBookTitle("chapter1"))
        // 纯后缀名不得被剥成空串
        assertEquals(".txt", EpubParser.cleanBookTitle(".txt"))
    }
}
