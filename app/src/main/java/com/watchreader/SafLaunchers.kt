package com.watchreader

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * SAF 结果处理：三个 ActivityResult 回调体的实现（打开文件 / 导出备份 / 导入备份）
 *
 * Activity 只保留 registerForActivityResult 的注册本身（必须在 Activity 期完成），
 * 回调体里的流生命周期、编码、失败提示全部收敛在这里：删除本类会把 48 行 IO 编排
 * 摊回 MainActivity，而 openOutputStream / openInputStream / use / flush 只在这一处出现。
 */
internal class SafResultHandlers(
    private val contentResolver: ContentResolver,
    private val scope: CoroutineScope,
    private val viewModel: ReaderViewModel
) {

    /** 打开 TXT / EPUB：先取持久读权限（失败可忽略），再交给 ViewModel 载入 */
    fun onOpenDocumentResult(uri: Uri?) {
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            viewModel.loadFile(it)
        }
    }

    /** 备份导出：SAF 建文件后写 UTF-8 JSON，成功 / 失败各回一条提示 */
    fun onExportBackupResult(uri: Uri?) {
        if (uri == null) return
        scope.launch(Dispatchers.IO) {
            try {
                val json = viewModel.exportBackup()
                contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                    out.flush()
                } ?: throw IllegalStateException("无法写入所选文件")
                viewModel.notifyBackupExported(true)
            } catch (_: Exception) {
                viewModel.notifyBackupExported(false)
            }
        }
    }

    /**
     * 备份导入：只读文本并交给 ViewModel 解析预览
     *
     * 先解析预览、确认浮层点头后才真正覆盖（误选旧备份不再立即回滚全书架）；
     * 预览失败的具体原因由 previewBackupRestore 写入 errorMessage，此处不覆盖。
     */
    fun onImportBackupResult(uri: Uri?) {
        if (uri == null) return
        scope.launch(Dispatchers.IO) {
            try {
                val json = contentResolver.openInputStream(uri)
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                viewModel.previewBackupRestore(json)
            } catch (_: Exception) {
                viewModel.notifyBackupRestoreFailed()
            }
        }
    }
}

/**
 * 三个 SAF 启动请求
 *
 * 页面只表达「我要打开文件 / 导出 / 导入」，具体启动哪个选择器、带什么 MIME、
 * 默认文件名怎么拼由 [SafRequests] 与 Activity 的注册期声明，页面不再接触启动器字段。
 */
internal class SafLaunchRequests(
    val openDocument: () -> Unit,
    val exportBackup: () -> Unit,
    val importBackup: () -> Unit
)

/** SAF 选择器 MIME 集合与备份默认文件名（字面量逐字保留原内联写法） */
internal object SafRequests {

    fun openDocumentMimeTypes(): Array<String> =
        arrayOf("text/plain", "application/epub+zip", "application/octet-stream", "*/*")

    fun importBackupMimeTypes(): Array<String> =
        arrayOf("application/json", "text/plain", "*/*")

    /** 默认备份文件名，形如 watchreader_backup_20261104_2340.json（本地时区分钟级时间戳） */
    fun defaultBackupFileName(now: Long = System.currentTimeMillis()): String {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmm", java.util.Locale.US)
            .format(java.util.Date(now))
        return "watchreader_backup_${stamp}.json"
    }
}
