package com.watchreader

/**
 * EPUB 正文 HTML → 纯文本 的深模块。
 *
 * 输入一段 HTML 字符串（可能带锚点范围），输出可直接排版的正文；不触碰 Context / Uri / Zip，
 * 因此纯 JVM 单测即可驱动整条转换链：去注释与干扰标签、块级换行、实体解码、中文段落排版。
 *
 * 正则只在这里有一份：章节循环里的标题兜底（extractTitleFromHtml）与单章正文提取共用同一套标签口径，
 * 新增需要剔除的标签时改这一处，不会在两处 strip 规则之间漂移。
 *
 * [EpubParser.extractFormattedTextFromHtml] 与 [EpubParser.decodeHtmlEntities] 保留为对外委托入口。
 */
internal object EpubTextExtractor {
    // 预编译全局静态正则表达式（避免在章节提取循环中重复编译产生 GC 压力）
    private val REGEX_HEAD = Regex("""(?is)<head\b[^>]*>.*?</head>""")
    private val REGEX_STYLE = Regex("""(?is)<style\b[^>]*>.*?</style>""")
    private val REGEX_SCRIPT = Regex("""(?is)<script\b[^>]*>.*?</script>""")
    private val REGEX_SVG = Regex("""(?is)<svg\b[^>]*>.*?</svg>""")
    private val REGEX_NAV = Regex("""(?is)<nav\b[^>]*>.*?</nav>""")
    private val REGEX_COMMENTS = Regex("""(?s)<!--.*?-->""")
    private val REGEX_BR = Regex("""(?i)<br\s*/?>""")
    private val REGEX_HR = Regex("""(?i)<hr\s*/?>""")
    private val REGEX_BLOCK_TAGS = Regex("""(?i)</?(?:p|div|h[1-6]|tr|li|blockquote|section|article|pre|header|footer)\b[^>]*>""")
    private val REGEX_ALL_TAGS = Regex("""<[^>]+>""")
    private val REGEX_TITLE = Regex("""(?i)<title[^>]*>(.*?)</title>""")
    private val REGEX_H_TAG = Regex("""(?i)<h[1-3][^>]*>(.*?)</h[1-3]>""")
    private val REGEX_DECIMAL_ENTITY = Regex("""&#(\d+);""")
    private val REGEX_HEX_ENTITY = Regex("""&#x([0-9a-fA-F]+);""")

    fun extractTitleFromHtml(html: String): String {
        val titleMatch = REGEX_TITLE.find(html)
        if (titleMatch != null) {
            val t = titleMatch.groupValues[1].trim()
            if (t.isNotEmpty()) return decodeHtmlEntities(t)
        }
        val h1Match = REGEX_H_TAG.find(html)
        if (h1Match != null) {
            val t = stripTags(h1Match.groupValues[1]).trim()
            if (t.isNotEmpty()) return decodeHtmlEntities(t)
        }
        return ""
    }

    fun extractFormattedTextFromHtml(
        html: String,
        targetAnchor: String = "",
        nextAnchor: String = "",
        cleanTypography: Boolean = true
    ): String {
        if (html.isEmpty()) return ""

        var content = html

        // 1. 若有起始锚点定位
        if (targetAnchor.isNotEmpty()) {
            val anchorPattern = Regex("""(?i)(?:id|name)\s*=\s*["']${Regex.escape(targetAnchor)}["']""")
            val match = anchorPattern.find(content)
            if (match != null) {
                content = content.substring(match.range.first)
            }
        }

        // 2. 若有下一章节的结束锚点
        if (nextAnchor.isNotEmpty() && nextAnchor != targetAnchor) {
            val nextPattern = Regex("""(?i)(?:id|name)\s*=\s*["']${Regex.escape(nextAnchor)}["']""")
            val matchNext = nextPattern.find(content)
            if (matchNext != null && matchNext.range.first > 0) {
                content = content.substring(0, matchNext.range.first)
            }
        }

        // 3. 剔除 HTML 注释与干扰标签
        content = REGEX_COMMENTS.replace(content, "")
        content = REGEX_HEAD.replace(content, "")
        content = REGEX_STYLE.replace(content, "")
        content = REGEX_SCRIPT.replace(content, "")
        content = REGEX_SVG.replace(content, "")
        content = REGEX_NAV.replace(content, "")

        // 4. 将分割线和块级换行标签标准化为换行符
        content = REGEX_HR.replace(content, "\n—— ——\n")
        content = REGEX_BR.replace(content, "\n")
        content = REGEX_BLOCK_TAGS.replace(content, "\n")

        // 5. 剥离所有其余内联 HTML 标签
        content = REGEX_ALL_TAGS.replace(content, "")

        // 6. 解码 HTML 实体
        content = decodeHtmlEntities(content)

        // 7. 中文段落排版。净化态：剥离行尾空白与各类缩进、压缩连续空行，统一补标准全角双空格；
        //    原样态：保留提取文本的行结构（换行、空行与源缩进）逐字呈现，仅归一化换行符
        if (!cleanTypography) {
            return content.replace("\r\n", "\n").replace('\r', '\n')
        }

        val sb = java.lang.StringBuilder(content.length + 64)
        val lines = content.split('\n')
        for (rawLine in lines) {
            val line = rawLine.trim { it <= ' ' || it == '\u3000' }
            if (line.isNotEmpty()) {
                if (sb.isNotEmpty()) {
                    sb.append("\n\n")
                }
                sb.append("\u3000\u3000").append(line)
            }
        }

        return sb.toString()
    }

    private fun stripTags(text: String): String {
        return REGEX_ALL_TAGS.replace(text, "")
    }

    fun decodeHtmlEntities(input: String): String {
        if (!input.contains('&')) return input

        var text = input
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&lsquo;", "‘")
            .replace("&rsquo;", "’")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")
            .replace("&middot;", "·")
            .replace("&yen;", "¥")
            .replace("&copy;", "©")

        text = REGEX_DECIMAL_ENTITY.replace(text) { match ->
            try {
                val code = match.groupValues[1].toInt()
                code.toChar().toString()
            } catch (_: Exception) {
                match.value
            }
        }

        text = REGEX_HEX_ENTITY.replace(text) { match ->
            try {
                val code = match.groupValues[1].toInt(16)
                code.toChar().toString()
            } catch (_: Exception) {
                match.value
            }
        }

        return text
    }
}
