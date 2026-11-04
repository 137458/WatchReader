package com.watchreader

/**
 * 章节范围分卷模型
 */
data class ChapterRange(
    val startIndex: Int,
    val endIndex: Int,
    val label: String
)

/**
 * 智能根据章节总数生成适宜手表的范围分卷
 */
fun generateChapterRanges(totalCount: Int): List<ChapterRange> {
    if (totalCount <= 30) return emptyList()
    val step = when {
        totalCount <= 150 -> 25
        totalCount <= 600 -> 50
        else -> 100
    }
    val list = ArrayList<ChapterRange>(totalCount / step + 1)
    var start = 0
    while (start < totalCount) {
        val end = minOf(start + step, totalCount)
        list.add(ChapterRange(start, end - 1, "第 ${start + 1} ~ ${end} 章"))
        start = end
    }
    return list
}
