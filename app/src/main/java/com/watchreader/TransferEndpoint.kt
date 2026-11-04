package com.watchreader

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLDecoder

/** 书籍清单单条记录（文件名 + 体积 + 修改时间） */
internal data class BookFileEntry(val name: String, val size: Long, val modified: Long)

/**
 * 传书端点：把「路径 + 请求体」翻译成 [TransferResponse]（纯 JVM，不碰 socket、不碰 Android）。
 *
 * 职责边界：
 * - 只做路由分发、参数提取、落盘与响应构造；鉴权、socket 读写、进度展示、书架写入都在调用方；
 * - 所有副作用通过 [Listener] 回调出去，测试可注入空实现，生产由 WifiTransferServer 承接；
 * - 文件白名单与书籍目录由调用方注入，端点自身不持有全局状态。
 *
 * 三态约定（每个动作都必须返回确定的 [TransferResponse]，不抛异常）：
 * - 缺参数 / 格式不支持 → 400；
 * - 目标不存在 → 404；
 * - 落盘或删除失败 → 500。上传越限 → 413，且半成品必须已删除。
 */
internal class TransferEndpoint(
    private val booksDir: File,
    private val isSupportedFileName: (String) -> Boolean,
    private val uploadedCount: () -> Int = { 0 },
    private val listener: Listener = Listener.None,
    private val maxUploadBytes: Long = MAX_UPLOAD_BYTES
) {

    /** 端点对外暴露的副作用出口；全部带空实现，调用方只覆盖关心的事件 */
    internal interface Listener {

        /** 声明长度已超限，尚未读取 body */
        fun onUploadRejected(fileName: String, declaredBytes: Long) {}

        /** 开始落盘：进度置为起始值并触发一次触感 */
        fun onUploadStarted(fileName: String, declaredBytes: Long) {}

        /** 每写满一次缓冲区的进度通知 */
        fun onUploadChunk(fileName: String, progress: Float, bytesRead: Long, declaredBytes: Long) {}

        /** 进度跨过 25% 整数点位，用于节流触感 */
        fun onUploadMilestone() {}

        /** 上传被中断/越限/长度不足：半成品已删，调用方应复位进度 */
        fun onUploadAborted() {}

        /** 上传成功且文件已在磁盘上；书架入库与 100% 展示由调用方负责 */
        fun onUploadSaved(file: File, fileName: String) {}

        /** 书籍文件已删除 */
        fun onBookDeleted(fileName: String) {}

        companion object {
            val None: Listener = object : Listener {}
        }
    }

    fun dispatch(request: HttpRequest, body: InputStream): TransferResponse {
        val target = request.target
        return when {
            request.method == "GET" && (target == "/" || target.startsWith("/?")) ->
                TransferResponse.Text(200, "text/html", TRANSFER_PAGE_HTML)

            request.method == "GET" && target == "/favicon.ico" ->
                TransferResponse.Text(204, "image/x-icon", "")

            request.method == "GET" && target == "/status" ->
                TransferResponse.Text(200, "application/json", """{"status":"ok","uploaded":${uploadedCount()}}""")

            request.method == "GET" && (target == "/api/books" || target.startsWith("/api/books?")) ->
                TransferResponse.Text(200, "application/json; charset=utf-8", bookListJson(listBooks()))

            (request.method == "POST" || request.method == "DELETE") && target.startsWith("/api/books/delete") ->
                deleteBook(request)

            request.method == "GET" && (target.startsWith("/api/download") || target.startsWith("/download")) ->
                downloadBook(request)

            request.method == "POST" && target.startsWith("/upload") ->
                upload(request, body)

            else -> TransferResponse.Text(404, "text/plain", "Not Found")
        }
    }

    private fun listBooks(): List<BookFileEntry> =
        booksDir.listFiles()
            ?.filter { isSupportedFileName(it.name) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { BookFileEntry(it.name, it.length(), it.lastModified()) }
            ?: emptyList()

    private fun downloadBook(request: HttpRequest): TransferResponse {
        val targetName = request.queryOf("name", "filename") ?: ""
        if (targetName.isEmpty()) {
            return TransferResponse.Text(400, "application/json", """{"status":"error","message":"缺少书籍名称"}""")
        }
        val cleanName = File(targetName).name
        val targetFile = File(booksDir, cleanName)
        if (!targetFile.exists()) {
            return TransferResponse.Text(404, "application/json", """{"status":"not_found","message":"书籍不存在"}""")
        }
        val contentType = when (BookFormat.fromExtension(targetFile.extension)) {
            BookFormat.EPUB -> "application/epub+zip"
            BookFormat.MOBI -> "application/x-mobipocket-ebook"
            BookFormat.FB2 -> "text/xml; charset=utf-8"
            BookFormat.HTML -> "text/html; charset=utf-8"
            else -> "text/plain; charset=utf-8"
        }
        return TransferResponse.Download(200, targetFile, contentType, cleanName)
    }

    private fun deleteBook(request: HttpRequest): TransferResponse {
        val targetName = request.queryOf("name", "filename") ?: ""
        if (targetName.isEmpty()) {
            return TransferResponse.Text(400, "application/json", """{"status":"error","message":"缺少书籍名称"}""")
        }
        val cleanName = File(targetName).name
        val targetFile = File(booksDir, cleanName)
        if (!targetFile.exists()) {
            return TransferResponse.Text(404, "application/json", """{"status":"not_found","message":"书籍不存在"}""")
        }
        if (!targetFile.delete()) {
            return TransferResponse.Text(500, "application/json", """{"status":"error","message":"删除失败"}""")
        }
        listener.onBookDeleted(cleanName)
        return TransferResponse.Text(
            200,
            "application/json",
            JSONObject().put("status", "ok").put("deleted", cleanName).toString()
        )
    }

    /** 上传动作的兜底包装：端点绝不把异常抛给连接层，未预期失败统一 500（文件保持原状） */
    private fun upload(request: HttpRequest, body: InputStream): TransferResponse = try {
        uploadInternal(request, body)
    } catch (e: Exception) {
        TransferResponse.Text(
            500,
            "application/json",
            JSONObject().put("status", "error").put("message", e.localizedMessage ?: "传输失败").toString()
        )
    }

    private fun uploadInternal(request: HttpRequest, body: InputStream): TransferResponse {
        val declared = request.headers.of("content-length")?.trim()?.toLongOrNull() ?: -1L
        var fileName = request.queryOf("filename", "name") ?: ""
        request.headers.of("x-filename")?.let { raw ->
            fileName = try {
                URLDecoder.decode(raw, "UTF-8")
            } catch (_: Exception) {
                fileName
            }
        }
        if (fileName.isEmpty()) {
            return TransferResponse.Text(
                400,
                "application/json",
                """{"status":"error","message":"仅支持内置网页直传（缺少 filename 参数）"}"""
            )
        }

        val cleanName = File(fileName).name
        if (!isSupportedFileName(cleanName)) {
            return TransferResponse.Text(
                400,
                "application/json",
                """{"status":"error","message":"仅支持 .txt / .epub / .mobi / .azw3 / .fb2 / .html 格式"}"""
            )
        }

        // 声明长度超限：body 一个字节都不读，直接 413
        if (uploadExceedsLimit(declared, maxUploadBytes)) {
            listener.onUploadRejected(cleanName, declared)
            listener.onUploadAborted()
            return tooLargeResponse()
        }

        listener.onUploadStarted(cleanName, declared)
        val savedFile = File(booksDir, cleanName)
        var totalRead = 0L
        var overflow = false
        try {
            FileOutputStream(savedFile).use { fos ->
                val buffer = ByteArray(32768)
                var lastMilestone = 0
                while (true) {
                    val read = body.read(buffer)
                    if (read == -1) break
                    fos.write(buffer, 0, read)
                    totalRead += read

                    // 未声明长度的上传在这里兜底：越线立刻停手，避免写满存储
                    if (uploadExceedsLimit(totalRead, maxUploadBytes)) {
                        overflow = true
                        break
                    }

                    val progress = if (declared > 0) {
                        (totalRead.toFloat() / declared).coerceIn(0.05f, 0.98f)
                    } else {
                        0.5f
                    }
                    listener.onUploadChunk(cleanName, progress, totalRead, declared)

                    val milestone = (progress * 4).toInt()
                    if (milestone > lastMilestone) {
                        lastMilestone = milestone
                        listener.onUploadMilestone()
                    }

                    if (declared > 0 && totalRead >= declared) break
                }
            }
        } catch (e: Exception) {
            // 异常路径必须清理半成品：否则残缺文件会被 /api/books 当正常书籍展示
            savedFile.delete()
            listener.onUploadAborted()
            return TransferResponse.Text(
                500,
                "application/json",
                """{"status":"error","message":"传输中断，已取消保存"}"""
            )
        }

        if (overflow) {
            savedFile.delete()
            listener.onUploadAborted()
            return tooLargeResponse()
        }

        if (declared > 0 && totalRead < declared) {
            savedFile.delete()
            listener.onUploadAborted()
            return TransferResponse.Text(
                400,
                "application/json",
                """{"status":"error","message":"文件传输中断，已取消保存"}"""
            )
        }

        listener.onUploadSaved(savedFile, cleanName)
        return TransferResponse.Text(
            200,
            "application/json",
            JSONObject().put("status", "ok").put("fileName", cleanName).toString()
        )
    }

    private fun tooLargeResponse(): TransferResponse {
        val body = JSONObject()
            .put("status", "error")
            .put("message", "文件超过 50MB 上传上限")
        return TransferResponse.Text(413, "application/json", body.toString())
    }

    companion object {

        /**
         * 单文件上传硬上限 50MB。
         *
         * 手表可用存储远小于手机，上传直接写进 books 目录（不落临时盘），一次误传超大文件
         * 就能把 /data 写满并让后续所有写盘操作失败；50MB 对纯文本/EPUB 小说已是充裕上界。
         */
        internal const val MAX_UPLOAD_BYTES: Long = 50L * 1024L * 1024L

        /** 上传体是否超限；contentLength < 0 表示未声明长度，不在此预判 */
        internal fun uploadExceedsLimit(contentLength: Long, limit: Long = MAX_UPLOAD_BYTES): Boolean =
            contentLength > limit

        /** 书籍清单 JSON：统一走 org.json，文件名含引号/反斜杠/换行时不再拼出伪 JSON */
        internal fun bookListJson(entries: List<BookFileEntry>): String {
            val array = JSONArray()
            for (entry in entries) {
                array.put(
                    JSONObject()
                        .put("name", entry.name)
                        .put("size", entry.size)
                        .put("modified", entry.modified)
                )
            }
            return array.toString()
        }
    }
}
