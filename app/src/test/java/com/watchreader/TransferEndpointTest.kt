package com.watchreader

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * 传书端点路由与副作用契约：每个动作都返回确定的响应，副作用只经 [TransferEndpoint.Listener] 外泄。
 * 端点不持有 socket/android 依赖，因此越限删残档、403/404 文案、清单排序都能在纯 JVM 上直接断言。
 */
class TransferEndpointTest {

    private val booksDir: File = File(
        System.getProperty("java.io.tmpdir"),
        "watchreader-transfer-" + System.nanoTime()
    ).apply { mkdirs() }

    private class RecordingListener : TransferEndpoint.Listener {
        val rejected = mutableListOf<String>()
        val saved = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        var aborted = 0
        var milestones = 0

        override fun onUploadRejected(fileName: String, declaredBytes: Long) { rejected += fileName }
        override fun onUploadSaved(file: File, fileName: String) { saved += fileName }
        override fun onBookDeleted(fileName: String) { deleted += fileName }
        override fun onUploadAborted() { aborted++ }
        override fun onUploadMilestone() { milestones++ }
    }

    private val listener = RecordingListener()

    private fun endpoint(maxUploadBytes: Long = TransferEndpoint.MAX_UPLOAD_BYTES) = TransferEndpoint(
        booksDir = booksDir,
        isSupportedFileName = BookTextConverter::isSupportedFileName,
        uploadedCount = { 7 },
        listener = listener,
        maxUploadBytes = maxUploadBytes
    )

    private fun request(
        method: String,
        target: String,
        headers: Map<String, String> = emptyMap()
    ) = HttpRequest(method, target, HttpHeaders(headers))

    private fun body(text: String) = ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))

    private fun bookFiles(): List<String> = booksDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()

    @After
    fun tearDown() {
        booksDir.deleteRecursively()
    }

    @Test
    fun `未知路径返回 404 纯文本`() {
        val response = endpoint().dispatch(request("GET", "/nope"), body("")) as TransferResponse.Text
        assertEquals(404, response.status)
        assertEquals("text/plain", response.contentType)
        assertEquals("Not Found", response.body)
    }

    @Test
    fun `首页返回内联网页且状态接口回传注入的上传计数`() {
        val page = endpoint().dispatch(request("GET", "/?token=t"), body("")) as TransferResponse.Text
        assertEquals(200, page.status)
        assertEquals("text/html", page.contentType)
        assertTrue(page.body.startsWith("<!DOCTYPE html>"))

        val status = endpoint().dispatch(request("GET", "/status"), body("")) as TransferResponse.Text
        assertEquals(200, status.status)
        assertEquals("""{"status":"ok","uploaded":7}""", status.body)
    }

    @Test
    fun `书籍清单只列白名单文件并按修改时间倒序`() {
        File(booksDir, "a.txt").apply { writeText("aa"); setLastModified(1_000_000L) }
        File(booksDir, "b.epub").apply { writeText("bbb"); setLastModified(2_000_000L) }
        File(booksDir, "c.pdf").writeText("x")

        val response = endpoint().dispatch(request("GET", "/api/books?token=t"), body("")) as TransferResponse.Text
        assertEquals(200, response.status)
        assertEquals("application/json; charset=utf-8", response.contentType)

        val array = JSONArray(response.body)
        assertEquals(2, array.length())
        assertEquals("b.epub", array.getJSONObject(0).getString("name"))
        assertEquals(3L, array.getJSONObject(0).getLong("size"))
        assertEquals("a.txt", array.getJSONObject(1).getString("name"))
    }

    @Test
    fun `删除书籍删文件并回调删除事件`() {
        val file = File(booksDir, "旧书.txt").apply { writeText("x") }
        val response = endpoint().dispatch(
            request("DELETE", "/api/books/delete?name=%E6%97%A7%E4%B9%A6.txt&token=t"),
            body("")
        ) as TransferResponse.Text

        assertEquals(200, response.status)
        assertEquals("旧书.txt", JSONObject(response.body).getString("deleted"))
        assertFalse(file.exists())
        assertEquals(listOf("旧书.txt"), listener.deleted)
    }

    @Test
    fun `删除缺失书籍返回 404 且不回调`() {
        val response = endpoint().dispatch(request("POST", "/api/books/delete?name=ghost.txt"), body("")) as TransferResponse.Text
        assertEquals(404, response.status)
        assertTrue(listener.deleted.isEmpty())
    }

    @Test
    fun `下载已知书籍返回整文件响应且路径穿越被压成基名`() {
        val file = File(booksDir, "inner.epub").apply { writeBytes(ByteArray(5)) }
        val response = endpoint().dispatch(request("GET", "/api/download?name=..%2F..%2Finner.epub"), body(""))

        assertTrue(response is TransferResponse.Download)
        val download = response as TransferResponse.Download
        assertEquals(200, download.status)
        assertEquals(file, download.file)
        assertEquals("inner.epub", download.fileName)
        assertEquals("application/epub+zip", download.contentType)
    }

    @Test
    fun `上传缺少文件名与不支持扩展名各自返回 400`() {
        val missing = endpoint().dispatch(request("POST", "/upload"), body("abc")) as TransferResponse.Text
        assertEquals(400, missing.status)
        assertTrue(missing.body.contains("缺少 filename"))

        val unsupported = endpoint().dispatch(
            request("POST", "/upload?filename=a.pdf"),
            body("abc")
        ) as TransferResponse.Text
        assertEquals(400, unsupported.status)
        assertTrue(unsupported.body.contains(".txt"))
        assertTrue(bookFiles().isEmpty())
    }

    @Test
    fun `声明长度超限直接 413 且一个字节都不落盘`() {
        val response = endpoint(maxUploadBytes = 10).dispatch(
            request("POST", "/upload?filename=a.txt", mapOf("content-length" to "11")),
            body("0123456789")
        ) as TransferResponse.Text

        assertEquals(413, response.status)
        assertTrue(bookFiles().isEmpty())
        assertEquals(listOf("a.txt"), listener.rejected)
        assertEquals(1, listener.aborted)
    }

    @Test
    fun `未声明长度上传越线即断并删除残档`() {
        val response = endpoint(maxUploadBytes = 4).dispatch(
            request("POST", "/upload?filename=a.txt"),
            body("0123456789")
        ) as TransferResponse.Text

        assertEquals(413, response.status)
        assertTrue(bookFiles().isEmpty())
        assertEquals(1, listener.aborted)
    }

    @Test
    fun `上传体短于声明长度删除残档并返回 400`() {
        val response = endpoint().dispatch(
            request("POST", "/upload?filename=a.txt", mapOf("content-length" to "10")),
            body("0123")
        ) as TransferResponse.Text

        assertEquals(400, response.status)
        assertTrue(bookFiles().isEmpty())
        assertEquals(1, listener.aborted)
    }

    @Test
    fun `上传成功保留内容并回调入库`() {
        val response = endpoint().dispatch(
            request("POST", "/upload?filename=%E4%B8%89%E4%BD%93.txt"),
            body("你好")
        ) as TransferResponse.Text

        assertEquals(200, response.status)
        assertEquals("三体.txt", JSONObject(response.body).getString("fileName"))
        assertEquals("你好", File(booksDir, "三体.txt").readText())
        assertEquals(listOf("三体.txt"), listener.saved)

        val list = endpoint().dispatch(request("GET", "/api/books"), body("")) as TransferResponse.Text
        val names = JSONArray(list.body).let { arr -> (0 until arr.length()).map { arr.getJSONObject(it).getString("name") } }
        assertEquals(listOf("三体.txt"), names)
    }

    @Test
    fun `X-Filename 头优先于 query 文件名并做 URL 解码`() {
        val response = endpoint().dispatch(
            request(
                "POST",
                "/upload?filename=ignored.txt",
                mapOf("x-filename" to "%E6%96%B0%E5%90%8D.txt", "content-length" to "2")
            ),
            body("ab")
        ) as TransferResponse.Text

        assertEquals(200, response.status)
        assertEquals(listOf("新名.txt"), listener.saved)
        assertEquals("ab", File(booksDir, "新名.txt").readText())
        assertEquals(listOf("新名.txt"), bookFiles())
    }
}
