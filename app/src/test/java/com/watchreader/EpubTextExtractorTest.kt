package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EpubTextExtractor 的行为契约：HTML → 纯文本、实体解码、标题提取。
 *
 * 断言尽量落到最终字符串（精确 equals），而不是 contains 抽查——搬移前后任何排版口径漂移
 * 都会在这里变红；EpubParser 上的公开委托入口也用一条用例钉住，防止委托链断开。
 */
class EpubTextExtractorTest {

    // ── decodeHtmlEntities ───────────────────────────────────────────────

    @Test
    fun namedEntitiesAreDecoded() {
        assertEquals(" ", EpubTextExtractor.decodeHtmlEntities("&nbsp;"))
        assertEquals("—…“引号”", EpubTextExtractor.decodeHtmlEntities("&mdash;&hellip;&ldquo;引号&rdquo;"))
        assertEquals("·¥©", EpubTextExtractor.decodeHtmlEntities("&middot;&yen;&copy;"))
    }

    @Test
    fun decimalAndHexEntitiesAreDecoded() {
        assertEquals("你好", EpubTextExtractor.decodeHtmlEntities("&#20320;&#22909;"))
        assertEquals("你好！", EpubTextExtractor.decodeHtmlEntities("&#x4F60;&#x597D;&#xFF01;"))
        assertEquals("A", EpubTextExtractor.decodeHtmlEntities("&#x41;"))
    }

    @Test
    fun unknownOrMalformedEntitiesStayLiteral() {
        assertEquals("&#xZZ;", EpubTextExtractor.decodeHtmlEntities("&#xZZ;"))
        assertEquals("&#99999999999;", EpubTextExtractor.decodeHtmlEntities("&#99999999999;"))
        assertEquals("&unknown;", EpubTextExtractor.decodeHtmlEntities("&unknown;"))
        assertEquals("a & b", EpubTextExtractor.decodeHtmlEntities("a & b"))
    }

    @Test
    fun textWithoutAmpersandIsReturnedUnchanged() {
        assertEquals("纯文本没有实体", EpubTextExtractor.decodeHtmlEntities("纯文本没有实体"))
    }

    // ── extractFormattedTextFromHtml ─────────────────────────────────────

    @Test
    fun cleanTypographyCollapsesParagraphsAndIndents() {
        val html = """<html><head><title>T</title><style>p{color:red}</style></head>""" +
            """<body><!--注释--><p>第一段</p><p>第二段</p></body></html>"""
        assertEquals(
            "\u3000\u3000第一段\n\n\u3000\u3000第二段",
            EpubTextExtractor.extractFormattedTextFromHtml(html)
        )
    }

    @Test
    fun rawModeKeepsSourceLineStructure() {
        val html = """<html><body><p>第一段</p><p>第二段</p></body></html>"""
        assertEquals(
            "\n第一段\n\n第二段\n",
            EpubTextExtractor.extractFormattedTextFromHtml(html, cleanTypography = false)
        )
    }

    @Test
    fun anchorRangeSlicesBodyInRawMode() {
        val html = """<div><h2 id="sec1">第一节</h2><p>ALPHA</p>""" +
            """<h2 id="sec2">第二节</h2><p>BRAVO</p></div>"""
        val fromSec2 = EpubTextExtractor.extractFormattedTextFromHtml(html, "sec2", "", false)
        assertTrue("起始锚点之后的内容必须保留", fromSec2.contains("BRAVO"))
        assertFalse("起始锚点之前的内容必须剔除", fromSec2.contains("ALPHA"))

        val beforeSec2 = EpubTextExtractor.extractFormattedTextFromHtml(html, "", "sec2", false)
        assertTrue("下一锚点之前的内容必须保留", beforeSec2.contains("ALPHA"))
        assertFalse("下一锚点之后的内容必须剔除", beforeSec2.contains("BRAVO"))
    }

    @Test
    fun carriageReturnsAreNormalizedInRawMode() {
        val html = "<html><body><p>一</p>\r\n<p>二</p>\r<p>三</p></body></html>"
        val raw = EpubTextExtractor.extractFormattedTextFromHtml(html, cleanTypography = false)
        assertFalse("原样态不得残留 CR 控制符", raw.contains('\r'))
        assertTrue(raw.contains("一"))
        assertTrue(raw.contains("三"))
    }

    @Test
    fun emptyHtmlYieldsEmptyBody() {
        assertEquals("", EpubTextExtractor.extractFormattedTextFromHtml(""))
    }

    // ── extractTitleFromHtml ─────────────────────────────────────────────

    @Test
    fun titleComesFromTitleTagWithEntitiesDecoded() {
        assertEquals(
            "三体 & 地球",
            EpubTextExtractor.extractTitleFromHtml("<html><head><title>三体 &amp; 地球</title></head></html>")
        )
    }

    @Test
    fun titleFallsBackToHeadingTag() {
        assertEquals(
            "第一章 启程",
            EpubTextExtractor.extractTitleFromHtml("<body><h1>第一章 <b>启程</b></h1></body>")
        )
    }

    @Test
    fun missingTitleYieldsEmptyString() {
        assertEquals("", EpubTextExtractor.extractTitleFromHtml("<p>只有正文</p>"))
        assertEquals("", EpubTextExtractor.extractTitleFromHtml(""))
    }

    // ── EpubParser 对外委托入口 ──────────────────────────────────────────

    @Test
    fun publicParserEntryDelegatesToExtractor() {
        val html = "<p>甲</p><p>乙</p>"
        assertEquals(
            EpubTextExtractor.extractFormattedTextFromHtml(html),
            EpubParser.extractFormattedTextFromHtml(html)
        )
        assertEquals(
            EpubTextExtractor.decodeHtmlEntities("&amp;"),
            EpubParser.decodeHtmlEntities("&amp;")
        )
    }
}
