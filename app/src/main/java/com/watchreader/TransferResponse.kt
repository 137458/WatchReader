package com.watchreader

import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.net.URLEncoder

/**
 * 端点动作的可返回体（纯 JVM 数据，不含 socket）。
 *
 * 两种形态覆盖现有全部响应：内存文本（页面/JSON/错误）与整文件下载。
 * 状态码与内容类型由动作决定，拼接与写回由 [HttpResponseWriter] 负责。
 */
internal sealed interface TransferResponse {

    val status: Int

    /** 内存文本响应；[contentType] 会由写回器追加 "; charset=utf-8"（沿用既有响应头形态） */
    data class Text(
        override val status: Int,
        val contentType: String,
        val body: String
    ) : TransferResponse

    /** 文件下载响应；自带 Content-Disposition 与文件内容类型（不追加 charset） */
    data class Download(
        override val status: Int,
        val file: File,
        val contentType: String,
        val fileName: String
    ) : TransferResponse
}

/**
 * HTTP/1.1 响应写回器（纯 JVM）：状态行 + 头 + 体，写完即 flush。
 *
 * 语义约定：
 * - 文本响应固定 `Content-Type: <contentType>; charset=utf-8`（调用方传完整类型时会重复出现 charset，
 *   这是既有网页/列表契约的一部分，未在本次重构中改动）；
 * - 下载响应按文件实际长度写 Content-Length，文件名百分号编码（空格用 %20 而非 +）；
 * - 任何写失败都向上抛，由连接处理方统一记日志并关 socket。
 */
internal object HttpResponseWriter {

    fun write(output: OutputStream, response: TransferResponse) {
        when (response) {
            is TransferResponse.Text -> writeText(output, response)
            is TransferResponse.Download -> writeDownload(output, response)
        }
    }

    internal fun statusText(statusCode: Int): String = when (statusCode) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        else -> "Internal Server Error"
    }

    /** 下载文件名的百分号编码；编码失败退回原名，保证响应仍可发出 */
    internal fun encodeDownloadName(fileName: String): String = try {
        URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
    } catch (_: Exception) {
        fileName
    }

    private fun writeText(output: OutputStream, response: TransferResponse.Text) {
        val bytes = response.body.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 ${response.status} ${statusText(response.status)}\r\n" +
            "Content-Type: ${response.contentType}; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun writeDownload(output: OutputStream, response: TransferResponse.Download) {
        val fileLength = response.file.length()
        val encodedName = encodeDownloadName(response.fileName)
        val header = "HTTP/1.1 ${response.status} ${statusText(response.status)}\r\n" +
            "Content-Type: ${response.contentType}\r\n" +
            "Content-Length: $fileLength\r\n" +
            "Content-Disposition: attachment; filename*=UTF-8''$encodedName\r\n" +
            "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        FileInputStream(response.file).use { fis ->
            fis.copyTo(output, 32768)
        }
        output.flush()
    }
}
