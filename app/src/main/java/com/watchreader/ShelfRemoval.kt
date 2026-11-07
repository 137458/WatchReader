package com.watchreader

/**
 * 书架删书命中当前打开书籍的判定。
 *
 * 两条删书入口的身份信息并不对等：书架页拿得到 SAF uri 与书名，Web 端只拿得到文件名；
 * 因此「按 uri 命中」是精确判定，「按文件名命中」只在 Web 端作为兜底口径。
 * 判定必须抽成纯函数：命中与否决定是否关闭阅读会话并取消待落盘进度，写错会把
 * 已删书经迟到的 savePosition 写回书架。
 */
internal object ShelfRemoval {

    /** 被删书的身份信息；[compareFileName] 为 false 时文件名不参与判定 */
    data class RemovalTarget(
        val uriString: String?,
        val fileName: String?,
        val compareFileName: Boolean
    )

    fun hits(target: RemovalTarget, currentUriString: String?, currentFileName: String): Boolean {
        if (currentUriString == null && currentFileName.isEmpty()) return false
        val sameDocument = target.uriString != null && target.uriString == currentUriString
        val sameFileName = target.compareFileName &&
            target.fileName != null && target.fileName.isNotEmpty() &&
            currentFileName.isNotEmpty() && target.fileName.equals(currentFileName, ignoreCase = true)
        return sameDocument || sameFileName
    }
}
