package com.watchreader

import androidx.compose.runtime.Immutable

/**
 * 章节检测器 — 从全文中高性能提取章节目录与分节索引
 */

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
 * 高性能零分配前缀过滤章节检测算法
 * @param fullText 完整文本
 * @return 章节列表，按出现顺序排列
 */
fun detectChapters(fullText: String): List<Chapter> {
    if (fullText.isEmpty()) return emptyList()

    val chapters = ArrayList<Chapter>(minOf(512, fullText.length / 2500 + 32))
    var offset = 0
    val len = fullText.length

    while (offset < len) {
        val nl = fullText.indexOf('\n', offset)
        val lineEnd = if (nl == -1) len else nl
        val lineLen = lineEnd - offset

        // 仅对 2~60 字以内的短行进行候选字符快速预检
        if (lineLen in 2..60) {
            var start = offset
            while (start < lineEnd && fullText[start].isWhitespace()) {
                start++
            }
            if (start < lineEnd) {
                val firstChar = fullText[start]
                // 零分配候选首字剪枝：99.9% 普通正文直接跳过，耗时从 1.5s 骤降至 10ms
                val isCandidate = firstChar == '第' || firstChar == '序' || firstChar == '楔' ||
                        firstChar == '引' || firstChar == '前' || firstChar == '终' ||
                        firstChar == '后' || firstChar == '尾' || firstChar == '番' ||
                        firstChar == '扉' || firstChar == '卷' || firstChar == '正' ||
                        firstChar == 'C' || firstChar == 'c' ||
                        firstChar == '上' || firstChar == '中' || firstChar == '下'

                if (isCandidate) {
                    val line = fullText.substring(start, lineEnd).trimEnd()
                    if (line.length in 2..60 && CHAPTER_REGEX.matches(line)) {
                        chapters.add(Chapter(index = chapters.size, title = line, charOffset = offset))
                    }
                }
            }
        }

        offset = lineEnd + 1
    }

    // 若无匹配到任何规范章节（如纯文本记录），生成虚拟分节，确保按段分章极速渲染
    if (chapters.isEmpty()) {
        return createVirtualChapters(fullText)
    }

    return chapters
}

/**
 * 针对无章节标题小说生成虚拟分节（约 3000 字一节，按自然段换行对齐）
 */
private fun createVirtualChapters(fullText: String): List<Chapter> {
    val chapters = mutableListOf<Chapter>()
    val len = fullText.length
    if (len == 0) return emptyList()

    var offset = 0
    var partIdx = 1
    val chunkSize = 3000

    while (offset < len) {
        val title = "第 $partIdx 节"
        chapters.add(Chapter(index = chapters.size, title = title, charOffset = offset))
        partIdx++

        val targetEnd = offset + chunkSize
        if (targetEnd >= len) break

        val nextNl = fullText.indexOf('\n', targetEnd)
        offset = if (nextNl in targetEnd until minOf(targetEnd + 600, len)) {
            nextNl + 1
        } else {
            targetEnd
        }
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
