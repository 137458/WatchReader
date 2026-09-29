package com.watchreader

import androidx.compose.runtime.Immutable

/**
 * 章节检测器 — 从全文中高性能提取章节目录与分节索引
 */

/**
 * 单章渲染字数封顶。整章正文常驻单个 TextView，StaticLayout 构建成本与字数线性相关
 * （实测 4.7 万字 ≈ 400ms 主线程冻结），超长章节必须二次分节。
 */
const val MAX_CHAPTER_RENDER_CHARS = 4000

/** 单个章节的信息（@Immutable 保障 Compose 稳定跳过重组） */
@Immutable
data class Chapter(
    val index: Int = 0,
    val title: String,      // 章节标题文本
    val charOffset: Int     // 该章节在全文中的字符偏移量
)

/**
 * 章节正则 — 匹配各类中文与英文网文章节
 *
 * 匹配格式：
 * - "第1章 章节名" / "第 202 章 操练星子" / "第一千二百三十四章" / "第1回" / "第1卷"
 * - "序章" / "楔子" / "引子" / "前言" / "终章" / "后记" / "尾声" / "番外"
 * - "Chapter 1 The Beginning"
 */
val CHAPTER_REGEX = Regex(
    """(?i)^\s*第\s*[\d〇零一二两三四五六七八九十百千万壹贰叁肆伍陆柒捌玖拾佰仟]+\s*[章节卷集部篇回].*""" +
    """|^\s*(?:[引楔]子|正文(?!完|结)|[引序前]言|[序终]章|扉页|[上中下][部篇卷]|卷首语|后记|尾声|番外).*""" +
    """|^\s*Chapter\s*\d+.*"""
)

/**
 * 候选首字快速剪枝预检
 */
@Suppress("NOTHING_TO_INLINE")
private inline fun isChapterCandidateChar(firstChar: Char): Boolean {
    return firstChar == '第' || firstChar == '序' || firstChar == '楔' ||
            firstChar == '引' || firstChar == '前' || firstChar == '终' ||
            firstChar == '后' || firstChar == '尾' || firstChar == '番' ||
            firstChar == '扉' || firstChar == '卷' || firstChar == '正' ||
            firstChar == 'C' || firstChar == 'c' ||
            firstChar == '上' || firstChar == '中' || firstChar == '下'
}

/**
 * 流式零内存峰值章节检测器（直接从 URI / 流分块扫描，内存占用恒定 < 64KB）
 *
 * @param context Android 上下文
 * @param uri 目标 TXT 文件 URI
 * @param encoding 字符编码（如 UTF-8, GB18030 等）
 * @return Pair(章节列表, 总字符数)
 */
fun detectChaptersStream(
    context: android.content.Context,
    uri: android.net.Uri,
    encoding: String
): Pair<List<Chapter>, Int> {
    val cr = context.contentResolver

    // 优先尝试 FileChannel 直读流（使用 AutoCloseInputStream 托管 PFD 生命周期防泄漏）
    val fis: java.io.InputStream = try {
        cr.openFileDescriptor(uri, "r")?.let { pfd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
        } ?: cr.openInputStream(uri) ?: throw java.io.FileNotFoundException("无法打开文件流")
    } catch (_: Exception) {
        cr.openInputStream(uri) ?: throw java.io.FileNotFoundException("无法打开文件流")
    }

    return detectChaptersFromInputStream(fis, encoding)
}

/**
 * 从 InputStream 纯流式解析章节目录与计算总字符数（0 全文内存分配）
 */
fun detectChaptersFromInputStream(
    inputStream: java.io.InputStream,
    encoding: String,
    maxChapterChars: Int = MAX_CHAPTER_RENDER_CHARS
): Pair<List<Chapter>, Int> {
    val safeEncoding = if (encoding.isNotEmpty()) encoding else "UTF-8"
    val chapters = ArrayList<Chapter>(512)
    val breakOffsets = ArrayList<Int>(16)
    var segmentStart = 0

    val bufferedIn = if (inputStream is java.io.BufferedInputStream) inputStream else java.io.BufferedInputStream(inputStream, 65536)
    // 跳过 UTF-8 BOM（若存在）
    if (safeEncoding.equals("UTF-8", ignoreCase = true)) {
        bufferedIn.mark(4)
        val bom = ByteArray(3)
        val nRead = bufferedIn.read(bom)
        if (!(nRead >= 3 && bom[0] == 0xEF.toByte() && bom[1] == 0xBB.toByte() && bom[2] == 0xBF.toByte())) {
            bufferedIn.reset()
        }
    }

    val reader = java.io.BufferedReader(java.io.InputStreamReader(bufferedIn, java.nio.charset.Charset.forName(safeEncoding)), 65536)
    val charBuf = CharArray(32768)
    val lineSb = java.lang.StringBuilder(128)
    var currentCharOffset = 0
    var lineStartCharOffset = 0
    var lineCharCount = 0
    var readChars: Int

    try {
        while (reader.read(charBuf).also { readChars = it } != -1) {
            for (i in 0 until readChars) {
                val c = charBuf[i]
                if (c == '\n' || c == '\r') {
                    if (lineCharCount > 0) {
                        if (checkAndAddChapter(lineSb, lineStartCharOffset, chapters)) {
                            segmentStart = lineStartCharOffset
                        }
                        lineSb.setLength(0)
                        lineCharCount = 0
                    }
                    currentCharOffset++
                    lineStartCharOffset = currentCharOffset
                    // 封顶切点吸附到自然段首字符，避免把一句话劈成两节
                    if (maxChapterChars > 0 && currentCharOffset - segmentStart >= maxChapterChars) {
                        breakOffsets.add(currentCharOffset)
                        segmentStart = currentCharOffset
                    }
                } else {
                    if (lineCharCount == 0) {
                        lineStartCharOffset = currentCharOffset
                    }
                    if (lineCharCount < 80) {
                        lineSb.append(c)
                    }
                    lineCharCount++
                    currentCharOffset++
                    // 单行自身每满一个封顶步长强切一次（只切真正的超长行，
                    // 短行文本仍由换行分支吸附到段首，避免切进句中）
                    if (maxChapterChars > 0 && lineCharCount % maxChapterChars == 0) {
                        breakOffsets.add(segmentStart + maxChapterChars)
                        segmentStart += maxChapterChars
                    }
                }
            }
        }
        if (lineCharCount > 0) {
            checkAndAddChapter(lineSb, lineStartCharOffset, chapters)
        }
    } finally {
        try { reader.close() } catch (_: Exception) {}
    }

    val totalChars = currentCharOffset
    if (chapters.isEmpty()) {
        return createVirtualChaptersFromLength(totalChars) to totalChars
    }
    return labelChapterParts(chapters, breakOffsets, totalChars) to totalChars
}

private fun checkAndAddChapter(
    lineSb: java.lang.StringBuilder,
    lineStartCharOffset: Int,
    chapters: ArrayList<Chapter>
): Boolean {
    val len = lineSb.length
    if (len in 2..60) {
        var start = 0
        while (start < len && lineSb[start].isWhitespace()) {
            start++
        }
        if (start < len) {
            val firstChar = lineSb[start]
            if (isChapterCandidateChar(firstChar)) {
                val line = lineSb.substring(start).trimEnd()
                if (line.length in 2..60 && CHAPTER_REGEX.matches(line)) {
                    chapters.add(Chapter(index = chapters.size, title = line, charOffset = lineStartCharOffset))
                    return true
                }
            }
        }
    }
    return false
}

/** 无真实章节归属的首段哨兵键 */
private const val HEAD_OWNER = Int.MIN_VALUE

/**
 * 并入封顶切点并按 "原标题 · n/m" 标注续节。
 *
 * 切点与真实章节起点重合时自动去重，避免产生零长度章节。
 */
private fun labelChapterParts(
    realChapters: List<Chapter>,
    breakOffsets: List<Int>,
    totalChars: Int
): List<Chapter> {
    val starts = (realChapters.map { it.charOffset } + breakOffsets.filter { it < totalChars })
        .toSortedSet()
        .toList()
    if (starts.size == realChapters.size) return realChapters

    val realOffsets = realChapters.map { it.charOffset }.sorted()
    val titleByOffset = realChapters.associateBy({ it.charOffset }, { it.title })
    val groups = LinkedHashMap<Int, MutableList<Int>>(realChapters.size + 1)
    for (start in starts) {
        val owner = realOffsets.lastOrNull { it <= start } ?: HEAD_OWNER
        groups.getOrPut(owner) { mutableListOf() }.add(start)
    }

    val result = ArrayList<Chapter>(starts.size)
    var index = 0
    for ((owner, members) in groups) {
        val ownerTitle = if (owner == HEAD_OWNER) "开篇" else titleByOffset.getValue(owner)
        for ((part, start) in members.withIndex()) {
            val title = if (members.size == 1) ownerTitle else "$ownerTitle · ${part + 1}/${members.size}"
            result.add(Chapter(index = index++, title = title, charOffset = start))
        }
    }
    return result
}

private fun createVirtualChaptersFromLength(totalChars: Int): List<Chapter> {
    if (totalChars <= 0) return emptyList()
    val chapters = mutableListOf<Chapter>()
    var offset = 0
    var partIdx = 1
    val chunkSize = 3000
    while (offset < totalChars) {
        chapters.add(Chapter(index = chapters.size, title = "第 $partIdx 节", charOffset = offset))
        partIdx++
        offset += chunkSize
    }
    return chapters
}

/**
 * 根据字符偏移量找到当前所在的章节索引
 * 二分查找 — O(log n)
 */
fun findCurrentChapterIndex(chapters: List<Chapter>, charOffset: Int): Int {
    if (chapters.isEmpty()) return -1
    var lo = 0
    var hi = chapters.lastIndex
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (chapters[mid].charOffset <= charOffset) lo = mid
        else hi = mid - 1
    }
    return lo
}
