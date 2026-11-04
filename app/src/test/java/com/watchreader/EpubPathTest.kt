package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 归档内路径换算（EpubOpf.normalizePath / resolvePath）与 EPUB 目录解析（EpubToc）。
 *
 * 两块都是纯字符串进出：不需要 Context / Uri / ZipFile，因此可以在纯 JVM 上用最小 XML
 * 直接钉住条目路径、标题与锚点。
 *
 * 注：EpubToc 的用例与本文件同处（写作用域只给了两个测试文件），文件内按小节分区。
 */
class EpubPathTest {

    // ── normalizePath ────────────────────────────────────────────────────

    @Test
    fun normalizePathUnifiesSeparatorsAndRootSlash() {
        assertEquals("META-INF/container.xml", EpubOpf.normalizePath("META-INF/container.xml"))
        assertEquals("OEBPS/content.opf", EpubOpf.normalizePath("\\OEBPS\\content.opf"))
        assertEquals("OEBPS/text/ch1.xhtml", EpubOpf.normalizePath("/OEBPS/text/ch1.xhtml"))
        assertEquals("", EpubOpf.normalizePath(""))
    }

    @Test
    fun normalizePathCollapsesDotSegments() {
        assertEquals("OEBPS/text/ch1.xhtml", EpubOpf.normalizePath("OEBPS//text/./ch1.xhtml"))
        assertEquals("OEBPS/content.opf", EpubOpf.normalizePath("OEBPS/text/../content.opf"))
        assertEquals("a.xhtml", EpubOpf.normalizePath("../a.xhtml"))
        assertEquals("c.xml", EpubOpf.normalizePath("a/b/../../c.xml"))
    }

    // ── resolvePath ──────────────────────────────────────────────────────

    @Test
    fun resolvePathJoinsAgainstDirectory() {
        assertEquals("OEBPS/text/ch1.xhtml", EpubOpf.resolvePath("OEBPS/", "text/ch1.xhtml"))
        assertEquals("OEBPS/content.opf", EpubOpf.resolvePath("OEBPS", "content.opf"))
        assertEquals("ch1.xhtml", EpubOpf.resolvePath("", "ch1.xhtml"))
    }

    @Test
    fun resolvePathEscapesAndNormalizesDotDot() {
        assertEquals("OEBPS/content.opf", EpubOpf.resolvePath("OEBPS/text/", "../content.opf"))
        assertEquals("abs.xhtml", EpubOpf.resolvePath("OEBPS/", "/abs.xhtml"))
        assertEquals("a.xhtml", EpubOpf.resolvePath("OEBPS/", "../a.xhtml"))
    }

    @Test
    fun resolvePathTrimsRelativeSide() {
        assertEquals("OEBPS/text/ch1.xhtml", EpubOpf.resolvePath("OEBPS/", "  text/ch1.xhtml  "))
    }

    // ── EpubToc：NCX ─────────────────────────────────────────────────────

    @Test
    fun ncxTocYieldsTitlesPathsAndAnchors() {
        val ncx = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint id="n1" playOrder="1">
                  <navLabel><text>第一章 启程</text></navLabel>
                  <content src="text/ch1.xhtml#sec1"/>
                </navPoint>
                <navPoint id="n2" playOrder="2">
                  <navLabel><text>第二章 破境</text></navLabel>
                  <content src="text/ch2.xhtml"/>
                </navPoint>
              </navMap>
            </ncx>
        """.trimIndent()

        val entries = EpubToc.parseNcxToc(ncx, "OEBPS/")

        assertEquals(2, entries.size)
        assertEquals("第一章 启程", entries[0].title)
        assertEquals("OEBPS/text/ch1.xhtml", entries[0].entryPath)
        assertEquals("sec1", entries[0].anchor)
        assertEquals(0, entries[0].index)
        assertEquals("第二章 破境", entries[1].title)
        assertEquals("OEBPS/text/ch2.xhtml", entries[1].entryPath)
        assertEquals("", entries[1].anchor)
        assertEquals(1, entries[1].index)
    }

    @Test
    fun ncxTocFallsBackToOrdinalTitleWhenLabelBlank() {
        val ncx = """
            <ncx><navMap>
              <navPoint><navLabel><text>   </text></navLabel><content src="text/ch1.xhtml"/></navPoint>
            </navMap></ncx>
        """.trimIndent()

        val entries = EpubToc.parseNcxToc(ncx, "OEBPS/")

        assertEquals(1, entries.size)
        assertEquals("第 1 章", entries[0].title)
    }

    // ── EpubToc：EPUB3 nav ───────────────────────────────────────────────

    @Test
    fun navTocKeepsTocAnchorsAndSkipsLandmarks() {
        val nav = """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
            <body>
              <nav epub:type="toc">
                <ol>
                  <li><a href="text/ch1.xhtml#s1">第一章</a></li>
                  <li><a href="text/ch2.xhtml">第二章</a></li>
                </ol>
              </nav>
              <nav epub:type="landmarks">
                <ol><li><a href="cover.xhtml">封面</a></li></ol>
              </nav>
            </body>
            </html>
        """.trimIndent()

        val entries = EpubToc.parseNavToc(nav, "OEBPS/")

        assertEquals(2, entries.size)
        assertEquals("第一章", entries[0].title)
        assertEquals("OEBPS/text/ch1.xhtml", entries[0].entryPath)
        assertEquals("s1", entries[0].anchor)
        assertEquals("第二章", entries[1].title)
        assertEquals("", entries[1].anchor)
        assertTrue(
            "landmarks 里的入口不是正文章节，不能进目录",
            entries.none { it.entryPath.contains("cover") }
        )
    }

    @Test
    fun malformedTocXmlYieldsEmptyListInsteadOfThrowing() {
        assertEquals(0, EpubToc.parseNcxToc("<ncx><navMap", "OEBPS/").size)
    }
}
