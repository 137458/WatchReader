package com.watchreader

/**
 * 排版智能净化引擎
 *
 * 核心排版能力：
 * 1. 连续换行归一化：多余连续空行压缩为规范双换行（段落间保留 1 个标准空行）。
 * 2. 剥离行尾与冗余空白：去除行尾各种空格、制表符及全角空白。
 * 3. 规范段首缩进：自然段段首自动补全标准全角双空格（\u3000\u3000），已有缩进规范化，不重复补全。
 * 4. 极端边界自适应：空文本、纯空白、单行文本、已规整文本等场景健壮处理。
 */
object TypographyCleaner {

    private const val FULL_WIDTH_INDENT = "\u3000\u3000"

    /**
     * 智能排版净化
     *
     * @param text 输入原始小说或章节正文
     * @return 净化与规范缩进后的排版文本
     */
    fun clean(text: String): String {
        if (text.isEmpty()) return ""

        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val rawLines = normalized.split('\n')
        val paragraphs = ArrayList<String>(rawLines.size)

        for (rawLine in rawLines) {
            val trimmed = rawLine.trim { it <= ' ' || it == '\u3000' }
            if (trimmed.isNotEmpty()) {
                paragraphs.add(FULL_WIDTH_INDENT + trimmed)
            }
        }

        return paragraphs.joinToString("\n\n")
    }
}
