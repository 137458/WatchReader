package com.watchreader

import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLDecoder

/**
 * EPUB 容器与 OPF 包文档解析模块，外加归档内路径换算原语。
 *
 * 职责边界：从 container.xml 拿到 OPF 路径，从 OPF 拿到书名/作者/NCX/nav/spine 条目路径。
 * normalizePath / resolvePath 是从 href 到 zip 条目名的唯一定义处，TOC 与归档访问都走这里，
 * 避免三个模块各自拼一次相对路径。
 */
internal object EpubOpf {

    // 沿用原日志 TAG：搬移不改变既有日志口径
    private const val TAG = "EpubParser"

    internal data class OpfInfo(
        val title: String,
        val author: String,
        val ncxPath: String?,
        val navPath: String?,
        val spinePaths: List<String>
    )

    fun createPullParser(): XmlPullParser {
        return try {
            val kxmlClass = Class.forName("org.kxml2.io.KXmlParser")
            kxmlClass.getDeclaredConstructor().newInstance() as XmlPullParser
        } catch (_: Throwable) {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            factory.newPullParser()
        }
    }

    fun normalizePath(path: String): String {
        val normalized = path.replace('\\', '/').trimStart('/')
        if (!normalized.contains("..") && !normalized.contains("./")) {
            return normalized
        }

        val segments = normalized.split('/')
        val stack = mutableListOf<String>()
        for (seg in segments) {
            when (seg) {
                "", "." -> {}
                ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                else -> stack.add(seg)
            }
        }
        return stack.joinToString("/")
    }

    fun resolvePath(baseDir: String, relativePath: String): String {
        val trimmed = relativePath.trim()
        if (trimmed.startsWith("/")) {
            return normalizePath(trimmed)
        }
        val cleanBase = baseDir.replace('\\', '/').trimStart('/')
        val combined = if (cleanBase.isEmpty()) trimmed else {
            if (cleanBase.endsWith('/')) cleanBase + trimmed else "$cleanBase/$trimmed"
        }
        return normalizePath(combined)
    }

    fun parseOpfPathFromContainer(containerXml: String): String? {
        return try {
            val parser = createPullParser()
            parser.setInput(StringReader(containerXml))
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name.equals("rootfile", ignoreCase = true)) {
                    for (i in 0 until parser.attributeCount) {
                        if (parser.getAttributeName(i).equals("full-path", ignoreCase = true)) {
                            return normalizePath(parser.getAttributeValue(i))
                        }
                    }
                }
                eventType = parser.next()
            }
            null
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to parse container.xml", e)
            null
        }
    }

    fun parseOpf(opfXml: String, opfDir: String): OpfInfo {
        var title = ""
        var author = ""
        var ncxId: String? = null
        var navHref: String? = null
        var ncxHref: String? = null

        val manifestItems = mutableMapOf<String, String>()
        val spineIdRefs = mutableListOf<String>()

        try {
            val parser = createPullParser()
            parser.setInput(StringReader(opfXml))

            var eventType = parser.eventType
            var currentTag = ""

            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        currentTag = parser.name.lowercase()
                        when (currentTag) {
                            "item" -> {
                                var id = ""
                                var href = ""
                                var mediaType = ""
                                var properties = ""

                                for (i in 0 until parser.attributeCount) {
                                    val attrName = parser.getAttributeName(i).lowercase()
                                    val attrVal = parser.getAttributeValue(i)
                                    when (attrName) {
                                        "id" -> id = attrVal
                                        "href" -> href = attrVal
                                        "media-type" -> mediaType = attrVal
                                        "properties" -> properties = attrVal
                                    }
                                }

                                if (id.isNotEmpty() && href.isNotEmpty()) {
                                    val decodedHref = try { URLDecoder.decode(href, "UTF-8") } catch (_: Exception) { href }
                                    manifestItems[id] = decodedHref

                                    if (mediaType.equals("application/x-dtbncx+xml", ignoreCase = true) || id.equals("ncx", ignoreCase = true)) {
                                        ncxHref = decodedHref
                                    }
                                    if (properties.contains("nav", ignoreCase = true)) {
                                        navHref = decodedHref
                                    }
                                }
                            }
                            "spine" -> {
                                for (i in 0 until parser.attributeCount) {
                                    if (parser.getAttributeName(i).equals("toc", ignoreCase = true)) {
                                        ncxId = parser.getAttributeValue(i)
                                    }
                                }
                            }
                            "itemref" -> {
                                for (i in 0 until parser.attributeCount) {
                                    if (parser.getAttributeName(i).equals("idref", ignoreCase = true)) {
                                        spineIdRefs.add(parser.getAttributeValue(i))
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text.trim()
                        if (text.isNotEmpty()) {
                            if (currentTag == "dc:title" || currentTag == "title") {
                                if (title.isEmpty()) title = text
                            } else if (currentTag == "dc:creator" || currentTag == "creator") {
                                if (author.isEmpty()) author = text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }

            if (ncxHref == null && ncxId != null) {
                ncxHref = manifestItems[ncxId]
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error parsing OPF package XML", e)
        }

        val fullNcxPath = ncxHref?.let { resolvePath(opfDir, it) }
        val fullNavPath = navHref?.let { resolvePath(opfDir, it) }
        val spinePaths = spineIdRefs.mapNotNull { id ->
            manifestItems[id]?.let { resolvePath(opfDir, it) }
        }

        return OpfInfo(
            title = title,
            author = author,
            ncxPath = fullNcxPath,
            navPath = fullNavPath,
            spinePaths = spinePaths
        )
    }
}
