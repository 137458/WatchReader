package com.watchreader

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/**
 * FB2（FictionBook XML）正文抽取模块：单趟 XmlPullParser 流解析。
 *
 * 职责边界：跳过 description 元数据、base64 [binary] 与非正文本体 [body]（如 notes），
 * 只把主 body 内的段落文本按行输出；主 body 结束即提前终止，不为后续注释与封面浪费解析时间。
 */
internal object Fb2Format {

    fun convert(input: InputStream, out: Appendable) {
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
}
