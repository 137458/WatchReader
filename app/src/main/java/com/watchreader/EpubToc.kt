package com.watchreader

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URLDecoder

/**
 * EPUB 目录解析模块：EPUB2 的 NCX 与 EPUB3 的 nav 文档 → 章节条目列表。
 *
 * 一份输入（TOC 的 XML 文本 + 该 TOC 所在目录）对应一份输出（有序章节条目），
 * 两条路径的差异只留在各自的函数里；条目路径换算复用 EpubOpf 的归档路径原语。
 */
internal object EpubToc {

    // 沿用原日志 TAG：搬移不改变既有日志口径
    private const val TAG = "EpubParser"

    fun parseNcxToc(ncxXml: String, ncxDir: String): List<EpubChapterEntry> {
        val list = mutableListOf<EpubChapterEntry>()
        try {
            val parser = EpubOpf.createPullParser()
            parser.setInput(StringReader(ncxXml))

            var eventType = parser.eventType
            var currentTitle = ""
            var inText = false

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tagName = parser.name.lowercase()
                        if (tagName == "text") {
                            inText = true
                        } else if (tagName == "content") {
                            var src = ""
                            for (i in 0 until parser.attributeCount) {
                                if (parser.getAttributeName(i).equals("src", ignoreCase = true)) {
                                    src = parser.getAttributeValue(i)
                                    break
                                }
                            }
                            if (src.isNotEmpty()) {
                                val decodedSrc = try { URLDecoder.decode(src, "UTF-8") } catch (_: Exception) { src }
                                val parts = decodedSrc.split('#', limit = 2)
                                val path = EpubOpf.resolvePath(ncxDir, parts[0])
                                val anchor = if (parts.size > 1) parts[1] else ""
                                val title = currentTitle.trim().ifEmpty { "第 ${list.size + 1} 章" }
                                list.add(
                                    EpubChapterEntry(
                                        index = list.size,
                                        title = title,
                                        entryPath = path,
                                        anchor = anchor
                                    )
                                )
                                currentTitle = ""
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inText) {
                            currentTitle += parser.text
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tagName = parser.name.lowercase()
                        if (tagName == "text") {
                            inText = false
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error parsing NCX TOC XML", e)
        }

        return list
    }

    fun parseNavToc(navXml: String, navDir: String): List<EpubChapterEntry> {
        val list = mutableListOf<EpubChapterEntry>()
        try {
            val parser = EpubOpf.createPullParser()
            parser.setInput(StringReader(navXml))

            var eventType = parser.eventType
            var inAnchor = false
            var inIgnoredNav = false
            var currentHref = ""
            var currentTitle = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val tagName = parser.name.lowercase()
                        if (tagName == "nav") {
                            var epubType = ""
                            for (i in 0 until parser.attributeCount) {
                                if (parser.getAttributeName(i).contains("type", ignoreCase = true)) {
                                    epubType = parser.getAttributeValue(i)
                                    break
                                }
                            }
                            if (epubType.isNotEmpty() && !epubType.contains("toc", ignoreCase = true)) {
                                inIgnoredNav = true
                            }
                        } else if (!inIgnoredNav && tagName == "a") {
                            inAnchor = true
                            currentTitle = ""
                            currentHref = ""
                            for (i in 0 until parser.attributeCount) {
                                if (parser.getAttributeName(i).equals("href", ignoreCase = true)) {
                                    currentHref = parser.getAttributeValue(i)
                                    break
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inAnchor) {
                            currentTitle += parser.text
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val tagName = parser.name.lowercase()
                        if (tagName == "nav") {
                            inIgnoredNav = false
                        } else if (!inIgnoredNav && tagName == "a") {
                            inAnchor = false
                            if (currentHref.isNotEmpty()) {
                                val decodedHref = try { URLDecoder.decode(currentHref, "UTF-8") } catch (_: Exception) { currentHref }
                                val parts = decodedHref.split('#', limit = 2)
                                val path = EpubOpf.resolvePath(navDir, parts[0])
                                val anchor = if (parts.size > 1) parts[1] else ""
                                val title = currentTitle.trim().ifEmpty { "第 ${list.size + 1} 章" }
                                list.add(
                                    EpubChapterEntry(
                                        index = list.size,
                                        title = title,
                                        entryPath = path,
                                        anchor = anchor
                                    )
                                )
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error parsing EPUB3 Nav TOC XML", e)
        }

        return list
    }
}
