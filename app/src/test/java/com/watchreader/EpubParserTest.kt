package com.watchreader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {

    @Test
    fun testHtmlEntityDecoding() {
        val raw = "&ldquo;Hello&nbsp;&amp;&nbsp;World&rdquo;&mdash;&#20320;&#22909;&#xFF01;&copy;2026"
        val decoded = EpubParser.decodeHtmlEntities(raw)
        assertEquals("“Hello & World”—你好！©2026", decoded)
    }

    @Test
    fun testHtmlFormattedTextExtraction() {
        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <title>Chapter 1</title>
                <style>body { color: red; }</style>
                <script>console.log('ignored');</script>
            </head>
            <body>
                <!-- 这是内部注释，应被剔除 -->
                <h1>第一章 启程</h1>
                <p>夜幕降临，繁星点点。</p>
                <hr/>
                <p>少年背上行囊，踏上了未知的旅途。<br/>前路漫漫，风雪交加。</p>
            </body>
            </html>
        """.trimIndent()

        val plain = EpubParser.extractFormattedTextFromHtml(html)
        assertTrue(plain.contains("\u3000\u3000第一章 启程"))
        assertTrue(plain.contains("\u3000\u3000夜幕降临，繁星点点。"))
        assertTrue(plain.contains("—— ——"))
        assertTrue(plain.contains("\u3000\u3000少年背上行囊，踏上了未知的旅途。"))
        assertTrue(plain.contains("前路漫漫，风雪交加。"))
        // 确认没有 style、script、注释遗留
        assertTrue(!plain.contains("color: red"))
        assertTrue(!plain.contains("ignored"))
        assertTrue(!plain.contains("这是内部注释"))
    }

    @Test
    fun testHtmlAnchorRangeSlice() {
        val html = """
            <div>
                <h2 id="sec1">第一节 启蒙</h2>
                <p>这是第一节的专属内容。</p>
                <h2 id="sec2">第二节 破境</h2>
                <p>这是第二节的专属内容。</p>
                <h2 id="sec3">第三节 飞升</h2>
                <p>这是第三节的专属内容。</p>
            </div>
        """.trimIndent()

        // 截取第 2 节（从 sec2 到 sec3 之间）
        val plainSec2 = EpubParser.extractFormattedTextFromHtml(html, "sec2", "sec3")
        assertTrue(plainSec2.contains("第二节 破境"))
        assertTrue(plainSec2.contains("这是第二节的专属内容"))
        assertTrue(!plainSec2.contains("第一节 启蒙"))
        assertTrue(!plainSec2.contains("第三节 飞升"))

        // 截取最后一节（从 sec3 到末尾）
        val plainSec3 = EpubParser.extractFormattedTextFromHtml(html, "sec3")
        assertTrue(plainSec3.contains("第三节 飞升"))
        assertTrue(plainSec3.contains("这是第三节的专属内容"))
        assertTrue(!plainSec3.contains("第一节 启蒙"))
        assertTrue(!plainSec3.contains("第二节 破境"))
    }

    @Test
    fun testCleanBookTitle() {
        assertEquals("凡人修仙传", EpubParser.cleanBookTitle("凡人修仙传.epub"))
        assertEquals("斗破苍穹", EpubParser.cleanBookTitle("斗破苍穹.txt"))
        assertEquals("雪中悍刀行", EpubParser.cleanBookTitle("雪中悍刀行.EPUB"))
        assertEquals("剑来", EpubParser.cleanBookTitle("剑来.TXT"))
        assertEquals("普通小说", EpubParser.cleanBookTitle("普通小说"))
    }

    @Test
    fun testSyntheticEpub2Parsing() {
        val containerXml = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                </rootfiles>
            </container>
        """.trimIndent()

        val opfXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>测试修仙传.epub</dc:title>
                    <dc:creator>测试作者</dc:creator>
                </metadata>
                <manifest>
                    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                    <item id="ch1" href="Text/ch1.xhtml" media-type="application/xhtml+xml"/>
                    <item id="ch2" href="Text/ch2.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine toc="ncx">
                    <itemref idref="ch1"/>
                    <itemref idref="ch2"/>
                </spine>
            </package>
        """.trimIndent()

        val ncxXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
                <navMap>
                    <navPoint id="np-1" playOrder="1">
                        <navLabel><text>第一章 觉醒</text></navLabel>
                        <content src="Text/ch1.xhtml"/>
                    </navPoint>
                    <navPoint id="np-2" playOrder="2">
                        <navLabel><text>第二章 破境</text></navLabel>
                        <content src="Text/ch2.xhtml"/>
                    </navPoint>
                </navMap>
            </ncx>
        """.trimIndent()

        val ch1Xhtml = """
            <html><body><h1>第一章 觉醒</h1><p>灵气复苏的清晨。</p></body></html>
        """.trimIndent()

        val ch2Xhtml = """
            <html><body><h1>第二章 破境</h1><p>丹田之中金光大盛。</p></body></html>
        """.trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(containerXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zos.write(opfXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/toc.ncx"))
            zos.write(ncxXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/Text/ch1.xhtml"))
            zos.write(ch1Xhtml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("OEBPS/Text/ch2.xhtml"))
            zos.write(ch2Xhtml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val zipBytes = baos.toByteArray()
        val meta = EpubParser.parseEpubFromStream({ ByteArrayInputStream(zipBytes) }, "默认书名")

        // 验证书名自动净化
        assertEquals("测试修仙传", meta.title)
        assertEquals("测试作者", meta.author)
        assertEquals(2, meta.chapters.size)
        assertEquals("第一章 觉醒", meta.chapters[0].title)
        assertEquals("第二章 破境", meta.chapters[1].title)
        assertEquals(0, meta.chapters[0].charOffset)
        assertTrue(meta.totalChars > 0)
    }

    @Test
    fun testSyntheticEpub3ParsingWithLandmarksFilter() {
        val containerXml = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles>
                    <rootfile full-path="package.opf" media-type="application/oebps-package+xml"/>
                </rootfiles>
            </container>
        """.trimIndent()

        val opfXml = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>EPUB3星际纪元</dc:title>
                </metadata>
                <manifest>
                    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                    <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
                </manifest>
                <spine>
                    <itemref idref="c1"/>
                </spine>
            </package>
        """.trimIndent()

        val navXml = """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
            <body>
                <!-- landmarks 导航应被自动忽略 -->
                <nav epub:type="landmarks">
                    <h2>导览</h2>
                    <ol>
                        <li><a href="cover.xhtml">封面</a></li>
                    </ol>
                </nav>
                <!-- 正确的 toc 导航 -->
                <nav epub:type="toc">
                    <h2>目录</h2>
                    <ol>
                        <li><a href="c1.xhtml">序章 跃迁</a></li>
                    </ol>
                </nav>
            </body>
            </html>
        """.trimIndent()

        val c1Xml = """
            <html><body><p>飞船正在准备跃迁。</p></body></html>
        """.trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(containerXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("package.opf"))
            zos.write(opfXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("nav.xhtml"))
            zos.write(navXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("c1.xhtml"))
            zos.write(c1Xml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val zipBytes = baos.toByteArray()
        val meta = EpubParser.parseEpubFromStream({ ByteArrayInputStream(zipBytes) })

        assertEquals("EPUB3星际纪元", meta.title)
        assertEquals(1, meta.chapters.size)
        assertEquals("序章 跃迁", meta.chapters[0].title)
    }

    @Test
    fun testSpineFallbackParsing() {
        val containerXml = """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                <rootfiles>
                    <rootfile full-path="content.opf"/>
                </rootfiles>
            </container>
        """.trimIndent()

        val opfXml = """
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
                <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:title>无目录小说</dc:title>
                </metadata>
                <manifest>
                    <item id="page1" href="page1.html" media-type="text/html"/>
                    <item id="page2" href="page2.html" media-type="text/html"/>
                </manifest>
                <spine>
                    <itemref idref="page1"/>
                    <itemref idref="page2"/>
                </spine>
            </package>
        """.trimIndent()

        val p1 = "<html><head><title>第一节 偶遇</title></head><body><p>故事由此展开。</p></body></html>"
        val p2 = "<html><body><h1>第二节 告别</h1><p>故事结束。</p></body></html>"

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("META-INF/container.xml"))
            zos.write(containerXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("content.opf"))
            zos.write(opfXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("page1.html"))
            zos.write(p1.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("page2.html"))
            zos.write(p2.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val zipBytes = baos.toByteArray()
        val meta = EpubParser.parseEpubFromStream({ ByteArrayInputStream(zipBytes) })

        assertEquals("无目录小说", meta.title)
        assertEquals(2, meta.chapters.size)
        assertEquals("第一节 偶遇", meta.chapters[0].title)
        assertEquals("第二节 告别", meta.chapters[1].title)
    }
}
