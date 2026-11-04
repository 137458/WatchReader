package com.watchreader

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * BookTextConverter 拆分后三个格式模块的单元测试：HtmlFormat（字符集探测 / 解码喂入 / 标签剥离）、
 * MobiFormat（PalmDOC 解压 / 尾随数据裁剪 / 魔数）、Fb2Format（流式正文抽取）与门面委托。
 * 全部基于纯 JVM 字节流，不依赖 Android 框架。
 */
class BookTextModulesTest {

    private fun utf8(s: String) = s.toByteArray(StandardCharsets.UTF_8)

    private fun htmlText(html: String, charsetName: String? = null): String {
        val out = StringBuilder()
        HtmlFormat.convertToText(ByteArrayInputStream(utf8(html)), out, charsetName)
        return out.toString()
    }

    // ═══════════════════════ HtmlFormat：字符集探测 ═══════════════════════

    @Test
    fun utf8BomIsDetectedWithThreeByteSkip() {
        val sample = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'h'.code.toByte())
        val (charset, skip) = HtmlFormat.detectCharset(sample, sample.size)
        assertEquals(StandardCharsets.UTF_8, charset)
        assertEquals(3, skip)
    }

    @Test
    fun utf16BomsAreDetectedWithTwoByteSkip() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41, 0x00)
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x00, 0x41)
        assertEquals(StandardCharsets.UTF_16LE, HtmlFormat.detectCharset(le, le.size).first)
        assertEquals(2, HtmlFormat.detectCharset(le, le.size).second)
        assertEquals(StandardCharsets.UTF_16BE, HtmlFormat.detectCharset(be, be.size).first)
        assertEquals(2, HtmlFormat.detectCharset(be, be.size).second)
    }

    @Test
    fun metaCharsetWinsOverByteHeuristic() {
        val bytes = utf8("<meta charset=\"iso-8859-1\"><p>caf</p>")
        val (charset, skip) = HtmlFormat.detectCharset(bytes, bytes.size)
        assertEquals(Charset.forName("ISO-8859-1"), charset)
        assertEquals(0, skip)
    }

    @Test
    fun plainAsciiWithoutBomFallsBackToUtf8() {
        val sample = utf8("<p>hello</p>")
        val (charset, skip) = HtmlFormat.detectCharset(sample, sample.size)
        assertEquals(StandardCharsets.UTF_8, charset)
        assertEquals(0, skip)
    }

    @Test
    fun bomIsStrippedBeforeDecoding() {
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8("<p>\u6807\u9898</p>")
        val out = StringBuilder()
        HtmlFormat.convertToText(ByteArrayInputStream(withBom), out, null)
        assertEquals("\u6807\u9898", out.toString().trim())
        assertFalse(out.toString().contains('\uFEFF'))
    }

    @Test
    fun metaCharsetDrivesActualDecoding() {
        val bytes = utf8("<meta charset=\"iso-8859-1\"><p>caf") +
            byteArrayOf(0xE9.toByte()) + utf8("</p>")
        val out = StringBuilder()
        HtmlFormat.convertToText(ByteArrayInputStream(bytes), out, null)
        assertEquals("caf\u00e9", out.toString().trim())
    }

    // ═══════════════════════ HtmlFormat：标签剥离与增量解码 ═══════════════════════

    @Test
    fun tagsAreStrippedAndEntitiesDecoded() {
        val html = "<html><head><title>T</title><script>var x=1</script></head><body><p>A &amp; B</p></body></html>"
        val out = htmlText(html, "UTF-8")
        assertEquals("A & B", out.trim())
        assertFalse(out.contains("var x"))
    }

    @Test
    fun blockTagsBreakLinesBeforeTheirText() {
        val out = StringBuilder()
        val stripper = HtmlTextStripper(out)
        val html = "<p>a</p><p>b</p>"
        stripper.feed(html.toCharArray(), html.length)
        stripper.finish()
        assertEquals("\na\nb", out.toString())
    }

    @Test
    fun commentsAndCdataAreHandled() {
        val out = StringBuilder()
        val stripper = HtmlTextStripper(out)
        val html = "<p><!-- hidden -->A<![CDATA[B]]>C</p>"
        stripper.feed(html.toCharArray(), html.length)
        stripper.finish()
        assertEquals("ABC", out.toString().trim())
    }

    @Test
    fun splitMultiByteCharsSurviveChunkBoundaries() {
        val bytes = utf8("\u4e2d\u6587")
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val out = StringBuilder()
        val stripper = HtmlTextStripper(out)
        val carry = HtmlFormat.feedDecoded(decoder, bytes.copyOfRange(0, 4), false, stripper)
        assertEquals(1, carry.size)
        val rest = HtmlFormat.feedDecoded(decoder, carry + bytes.copyOfRange(4, bytes.size), true, stripper)
        assertEquals(0, rest.size)
        stripper.finish()
        assertEquals("\u4e2d\u6587", out.toString())
    }

    // ═══════════════════════ MobiFormat：PalmDOC 与尾随数据 ═══════════════════════

    @Test
    fun literalRunCoversFollowingBytes() {
        val data = byteArrayOf(0x05) + utf8("Hello")
        assertArrayEquals(utf8("Hello"), MobiFormat.palmDocDecompress(data))
    }

    @Test
    fun highByteExpandsToSpacePlusChar() {
        assertArrayEquals(utf8(" A"), MobiFormat.palmDocDecompress(byteArrayOf(0xC1.toByte())))
    }

    @Test
    fun backReferenceCopiesEarlierBytes() {
        val data = byteArrayOf(0x41, 0x42, 0x43, 0x80.toByte(), 0x18)
        assertArrayEquals(utf8("ABCABC"), MobiFormat.palmDocDecompress(data))
    }

    @Test
    fun outOfRangeBackReferenceIsSkipped() {
        assertEquals(0, MobiFormat.palmDocDecompress(byteArrayOf(0x80.toByte(), 0x18)).size)
    }

    @Test
    fun trimTrailingDataIsIdentityWithoutFlags() {
        val data = utf8("Hello")
        assertSame(data, MobiFormat.trimTrailingData(data, 0))
    }

    @Test
    fun multibyteOverlapByteIsTrimmed() {
        val data = utf8("Hello") + byteArrayOf(0x01)
        assertArrayEquals(utf8("Hell"), MobiFormat.trimTrailingData(data, 1))
    }

    @Test
    fun varintTrailerEntriesAreTrimmed() {
        val data = utf8("Hi") + byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x03)
        assertArrayEquals(utf8("Hi"), MobiFormat.trimTrailingData(data, 2))
    }

    @Test
    fun overlongTrailerClampsToEmpty() {
        assertEquals(0, MobiFormat.trimTrailingData(byteArrayOf(0x7F), 2).size)
    }

    @Test
    fun asciiTagMatchesOnlyInBounds() {
        val header = ByteArray(68)
        "BOOK".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 60)
        assertTrue(MobiFormat.hasAsciiTag(header, 60, "BOOK"))
        assertFalse(MobiFormat.hasAsciiTag(header, 60, "MOBI"))
        assertFalse(MobiFormat.hasAsciiTag(ByteArray(3), 2, "BOOK"))
    }

    @Test
    fun truncatedFileIsReportedAsFormatFailure() {
        try {
            MobiFormat.convert(ByteArrayInputStream(ByteArray(10)), StringBuilder())
            fail("预期 BookFormatException")
        } catch (e: BookFormatException) {
            assertTrue(e.message!!.contains("\u8fc7\u5c0f"))
        }
    }

    // ═══════════════════════ Fb2Format ═══════════════════════

    @Test
    fun fb2ExtractsMainBodyAndStopsBeforeNotes() {
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<FictionBook><description><title-info><book-title>B</book-title></title-info></description>" +
            "<body><section><title><p>Chapter 1</p></title><p>first  text</p><empty-line/>" +
            "<p>Second   <emphasis>para</emphasis></p></section></body>" +
            "<body name=\"notes\"><section><p>note</p></section></body>" +
            "<binary id=\"c\">AAAA</binary></FictionBook>"
        val out = StringBuilder()
        Fb2Format.convert(ByteArrayInputStream(utf8(xml)), out)
        assertEquals("Chapter 1\nfirst text\n\nSecond para\n", out.toString())
    }

    @Test
    fun fb2HandlesPrefixedNamespacedTags() {
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<fb:FictionBook xmlns:fb=\"urn:x\"><fb:body><fb:section>" +
            "<fb:p>Hi</fb:p></fb:section></fb:body></fb:FictionBook>"
        val out = StringBuilder()
        Fb2Format.convert(ByteArrayInputStream(utf8(xml)), out)
        assertEquals("Hi\n", out.toString())
    }
}