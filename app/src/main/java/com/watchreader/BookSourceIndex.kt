package com.watchreader

/**
 * BookSource 的纯索引几何：章节起点 → 右端偏移 → 单章切片边界 → 缺失总长时的估算。
 *
 * 本 object 零 import（无 Context / Uri / Log，也不碰文件系统），因此可以纯 JVM 单测；
 * [BookSource] 的 companion 只留一行委托，公开签名与行为不变。
 */
internal object BookSourceIndex {

    /**
     * 各章右端字符偏移（半开区间用）：章节 i 占据 [chapters[i].charOffset, ends[i])。
     *
     * 末章右端取全书总长；索引倒序或越界（章节起点大于下一章起点、大于总长）时
     * 夹紧为单调不减，否则切片装配器会把倒序边界当成「本章已结束」，静默产出空章。
     */
    fun chapterEndOffsets(chapters: List<Chapter>, totalChars: Int): IntArray {
        if (chapters.isEmpty()) return IntArray(0)
        val safeTotal = totalChars.coerceAtLeast(0)
        val ends = IntArray(chapters.size)
        var previous = 0
        for (i in chapters.indices) {
            val start = chapters[i].charOffset.coerceAtLeast(0)
            val rawEnd = if (i + 1 < chapters.size) chapters[i + 1].charOffset else safeTotal
            previous = maxOf(previous, start, rawEnd)
            ends[i] = previous
        }
        return ends
    }

    /**
     * 单章切片边界：起点与终点都夹进 [0, totalChars]，终点不小于起点。
     * 索引越界返回 null（调用方按「无此章」处理，不是读取失败）。
     */
    fun chapterBounds(chapters: List<Chapter>, index: Int, totalChars: Int): CharBounds? {
        if (index !in chapters.indices) return null
        val safeTotal = totalChars.coerceAtLeast(0)
        val start = chapters[index].charOffset.coerceIn(0, safeTotal)
        val rawEnd = if (index + 1 < chapters.size) chapters[index + 1].charOffset else safeTotal
        return CharBounds(start, rawEnd.coerceIn(start, safeTotal))
    }

    /**
     * 磁盘缓存只留下了章节表、没留下总字符数（旧版缓存）时的估算：
     * 可定位字节数按编码折算成字符数，再用末章起点 + 3000 兜住「估算值反而比
     * 已扫到的章节起点还小」的情况，避免总长截断末章。
     */
    internal fun estimateTotalChars(
        chapters: List<Chapter>,
        fileSizeHint: Long,
        encoding: String
    ): Int {
        val bytesPerChar = if (encoding.startsWith("UTF-16")) 2L else 1L
        val estimated = (fileSizeHint.coerceAtLeast(0L) / bytesPerChar)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val lastChapter = chapters.lastOrNull() ?: return estimated
        return maxOf(estimated, lastChapter.charOffset + 3000)
    }
}

/** 半开字符区间 [start, end)；长度可能为 0（空章），不会为负 */
data class CharBounds(val start: Int, val end: Int) {
    val length: Int get() = maxOf(end - start, 0)
}

/** 章节索引与全书总长（二者来自同一次扫描，必须成对传递） */
internal data class BookIndex(val chapters: List<Chapter>, val totalChars: Int)

/**
 * 空书 / 章节索引越界时的占位内容：结构合法但正文为空，与读取失败的 null 区分。
 * （调用方对「越界」和「读失败」的处理不同：前者留在当前页，后者报错。）
 */
internal fun emptyChapterContent(): ChapterContent = ChapterContent(
    chapterIndex = 0,
    title = "",
    formattedBody = "",
    startCharOffset = 0,
    endCharOffset = 0,
    hasPrevChapter = false,
    prevChapterTitle = "",
    hasNextChapter = false,
    nextChapterTitle = ""
)
