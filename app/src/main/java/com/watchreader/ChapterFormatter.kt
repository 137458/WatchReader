package com.watchreader


/**
 * 组装单个章节的正文排版与上下文信息（零中间切片、零 split 数组分配，极速单 pass 扫描）
 */
fun formatChapterRawText(
    rawText: String,
    chapters: List<Chapter>,
    chapterIndex: Int,
    startOffset: Int,
    endOffset: Int,
    cleanTypography: Boolean = true
): ChapterContent {
    if (chapters.isEmpty() || chapterIndex !in chapters.indices) {
        return ChapterContent(
            chapterIndex = 0,
            title = "",
            formattedBody = rawText,
            startCharOffset = 0,
            endCharOffset = rawText.length,
            hasPrevChapter = false,
            prevChapterTitle = "",
            hasNextChapter = false,
            nextChapterTitle = ""
        )
    }

    val currentChap = chapters[chapterIndex]
    val chapTitle = currentChap.title.trim()

    // 关闭净化 = 尊重源文件自身排版：行结构、空行与原缩进逐字保留。
    // 剥除标题首行使正文坐标整体前移 headerLen，故记录单点常量偏移映射
    // （bodyParagraphStarts=[0] → rawParagraphStarts=[headerLen]，无补入缩进按 1:1 换算），
    // 未剥除时正文与原文切片同域，ChapterOffsetMapper 退化为恒等
    if (!cleanTypography) {
        val body = stripChapterTitleHeader(rawText, chapTitle)
        val headerLen = rawText.length - body.length
        return ChapterContent(
            chapterIndex = chapterIndex,
            title = currentChap.title,
            formattedBody = body,
            startCharOffset = startOffset,
            endCharOffset = endOffset,
            hasPrevChapter = chapterIndex > 0,
            prevChapterTitle = if (chapterIndex > 0) chapters[chapterIndex - 1].title else "",
            hasNextChapter = chapterIndex + 1 < chapters.size,
            nextChapterTitle = if (chapterIndex + 1 < chapters.size) chapters[chapterIndex + 1].title else "",
            bodyParagraphStarts = if (headerLen > 0) intArrayOf(0) else IntArray(0),
            rawParagraphStarts = if (headerLen > 0) intArrayOf(headerLen) else IntArray(0),
            paragraphIndentChars = 0
        )
    }

    val sb = StringBuilder(rawText.length + 64)

    // 段落起点映射：正文坐标 ↔ 原文坐标的精确换算依据（持久化阅读位置必须落在原文坐标域）
    val bodyParaList = ArrayList<Int>(32)
    val rawParaList = ArrayList<Int>(32)

    var lineStart = 0
    val textLen = rawText.length
    while (lineStart < textLen) {
        var lineEnd = rawText.indexOf('\n', lineStart)
        if (lineEnd == -1) {
            lineEnd = textLen
        }

        var s = lineStart
        while (s < lineEnd && rawText[s].isWhitespace()) {
            s++
        }
        var e = lineEnd
        while (e > s && rawText[e - 1].isWhitespace()) {
            e--
        }

        if (s < e) {
            val lineLen = e - s
            val isTitleMatch = sb.isEmpty() && lineLen == chapTitle.length && rawText.regionMatches(s, chapTitle, 0, lineLen)
            if (!isTitleMatch) {
                if (sb.isNotEmpty()) {
                    sb.append("\n\n")
                }
                bodyParaList.add(sb.length)
                rawParaList.add(s)
                sb.append("\u3000\u3000").append(rawText, s, e)
            }
        }

        lineStart = lineEnd + 1
    }

    val hasPrev = chapterIndex > 0
    val prevTitle = if (hasPrev) chapters[chapterIndex - 1].title else ""
    val hasNext = chapterIndex + 1 < chapters.size
    val nextTitle = if (hasNext) chapters[chapterIndex + 1].title else ""

    return ChapterContent(
        chapterIndex = chapterIndex,
        title = currentChap.title,
        formattedBody = sb.toString(),
        startCharOffset = startOffset,
        endCharOffset = endOffset,
        hasPrevChapter = hasPrev,
        prevChapterTitle = prevTitle,
        hasNextChapter = hasNext,
        nextChapterTitle = nextTitle,
        bodyParagraphStarts = bodyParaList.toIntArray(),
        rawParagraphStarts = rawParaList.toIntArray()
    )
}

/**
 * 剥离与章节标题重复的首行及其紧邻空行（标题已由独立标题控件呈现），其余内容原样返回
 */
private fun stripChapterTitleHeader(rawText: String, chapTitle: String): String {
    if (chapTitle.isEmpty()) return rawText

    val firstLineEnd = rawText.indexOf('\n')
    val firstLine = rawText.substring(0, if (firstLineEnd == -1) rawText.length else firstLineEnd)
        .trim { it <= ' ' || it == '\u3000' }
    if (firstLine != chapTitle) return rawText

    var from = if (firstLineEnd == -1) rawText.length else firstLineEnd + 1
    while (from < rawText.length) {
        val lineEnd = rawText.indexOf('\n', from)
        val bound = if (lineEnd == -1) rawText.length else lineEnd
        if (rawText.substring(from, bound).isNotBlank()) break
        from = if (lineEnd == -1) rawText.length else lineEnd + 1
    }
    return rawText.substring(from)
}
