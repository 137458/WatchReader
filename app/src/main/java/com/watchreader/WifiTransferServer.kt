package com.watchreader

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.*
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * 局域网 Wi-Fi 网页无线传书轻量级 HTTP 服务
 *
 * 特性：
 * 1. 0 第三方依赖：纯原生 ServerSocket 构建，包体积 0 增加。
 * 2. 双协议支持：同时支持高速 Direct Stream 与标准 Multipart Form-Data 上传。
 * 3. 跨平台现代 Web UI：手机与电脑端浏览器自适应，多文件拖拽上传与实时进度反馈。
 * 4. 自动入库：上传完成自动校验、保存至私有存储并即时录入书架。
 */
class WifiTransferServer(
    private val context: Context,
    private val port: Int = 8888,
    private val onBookUploaded: ((BookItem) -> Unit)? = null
) {
    private val TAG = "WifiTransferServer"
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _uploadedCount = MutableStateFlow(0)
    val uploadedCount: StateFlow<Int> = _uploadedCount.asStateFlow()

    private val booksDir: File by lazy {
        val dir = File(context.filesDir, "books")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    /**
     * 获取手表当前局域网 IP 地址
     */
    fun getLocalIpAddress(): String? {
        // 1. 优先通过 WifiManager 获取当前连接的 Wi-Fi IP
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val ipInt = wifiManager?.connectionInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                val ipStr = String.format(
                    java.util.Locale.US,
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
                if (ipStr != "0.0.0.0") return ipStr
            }
        } catch (_: Exception) {}

        // 2. 遍历网络接口枚举活跃 IPv4
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress
                        if (!host.isNullOrEmpty() && host != "127.0.0.1") {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    /**
     * 启动 HTTP 传书服务
     */
    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true
        return try {
            serverSocket = ServerSocket(port)
            serverSocket?.reuseAddress = true
            isRunning = true
            scope.launch {
                listenForClients()
            }
            Log.i(TAG, "Wi-Fi transfer server started on port $port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start Wi-Fi transfer server on port $port", e)
            isRunning = false
            false
        }
    }

    /**
     * 停止 HTTP 传书服务
     */
    @Synchronized
    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        Log.i(TAG, "Wi-Fi transfer server stopped")
    }

    fun isServerRunning(): Boolean = isRunning

    private fun listenForClients() {
        while (isRunning) {
            try {
                val socket = serverSocket?.accept() ?: break
                scope.launch {
                    handleClient(socket)
                }
            } catch (_: Exception) {
                if (!isRunning) break
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 30000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val requestHeader = readHeader(input)
            if (requestHeader.isEmpty()) {
                socket.close()
                return
            }

            val lines = requestHeader.lines()
            val requestLine = lines.firstOrNull() ?: ""
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                socket.close()
                return
            }

            val method = parts[0].uppercase()
            val rawPath = parts[1]

            when {
                method == "GET" && (rawPath == "/" || rawPath.startsWith("/?")) -> {
                    serveWebPage(output)
                }
                method == "GET" && rawPath == "/favicon.ico" -> {
                    sendResponse(output, 204, "image/x-icon", "")
                }
                method == "GET" && rawPath == "/status" -> {
                    sendResponse(output, 200, "application/json", """{"status":"ok","uploaded":${_uploadedCount.value}}""")
                }
                method == "POST" && rawPath.startsWith("/upload") -> {
                    handleFileUpload(rawPath, lines, input, output)
                }
                else -> {
                    sendResponse(output, 404, "text/plain", "Not Found")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling client request", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun readHeader(input: InputStream): String {
        val baos = ByteArrayOutputStream()
        var b: Int
        var consecutiveNewlines = 0

        while (input.read().also { b = it } != -1) {
            baos.write(b)
            if (b == '\n'.code) {
                consecutiveNewlines++
                if (consecutiveNewlines >= 2) break
            } else if (b != '\r'.code) {
                consecutiveNewlines = 0
            }
            if (baos.size() > 65536) break
        }
        return baos.toString("UTF-8")
    }

    private fun handleFileUpload(rawPath: String, headers: List<String>, input: InputStream, output: OutputStream) {
        var contentType = ""
        var contentLength = -1L
        var queryFileName = ""

        // 解析 Query 参数中的 filename (例如 /upload?filename=斗破苍穹.txt)
        val qIdx = rawPath.indexOf('?')
        if (qIdx >= 0 && qIdx + 1 < rawPath.length) {
            val queryStr = rawPath.substring(qIdx + 1)
            val params = queryStr.split("&")
            for (p in params) {
                val kv = p.split("=")
                if (kv.size == 2 && kv[0].equals("filename", ignoreCase = true)) {
                    try {
                        queryFileName = URLDecoder.decode(kv[1], "UTF-8")
                    } catch (_: Exception) {}
                }
            }
        }

        for (h in headers) {
            val lower = h.lowercase()
            if (lower.startsWith("content-type:")) {
                contentType = h.substring(13).trim()
            } else if (lower.startsWith("content-length:")) {
                contentLength = h.substring(15).trim().toLongOrNull() ?: -1L
            } else if (lower.startsWith("x-filename:")) {
                try {
                    queryFileName = URLDecoder.decode(h.substring(11).trim(), "UTF-8")
                } catch (_: Exception) {}
            }
        }

        if (queryFileName.isNotEmpty()) {
            // 模式 1：Direct Binary Stream 直接流式写入（最稳定极速）
            saveDirectStreamUpload(queryFileName, contentLength, input, output)
            return
        }

        if (contentType.contains("multipart/form-data")) {
            // 模式 2：Multipart Form-Data 表单解析
            val boundaryMatch = Regex("""boundary=(?:["']?)([^"';\s]+)""").find(contentType)
            val boundary = boundaryMatch?.groupValues?.get(1)
            if (boundary != null) {
                parseMultipartUpload(boundary, input, contentLength, output)
                return
            }
        }

        sendResponse(output, 400, "application/json", """{"status":"error","message":"Invalid Content-Type or missing filename"}""")
    }

    private fun saveDirectStreamUpload(
        fileName: String,
        contentLength: Long,
        input: InputStream,
        output: OutputStream
    ) {
        try {
            val cleanName = File(fileName).name
            if (!cleanName.endsWith(".txt", ignoreCase = true) && !cleanName.endsWith(".epub", ignoreCase = true)) {
                sendResponse(output, 400, "application/json", """{"status":"error","message":"仅支持 .txt 和 .epub 格式"}""")
                return
            }

            val savedFile = File(booksDir, cleanName)
            FileOutputStream(savedFile).use { fos ->
                val buf = ByteArray(32768)
                var read: Int
                var totalRead = 0L
                while (input.read(buf).also { read = it } != -1) {
                    fos.write(buf, 0, read)
                    totalRead += read
                    if (contentLength > 0 && totalRead >= contentLength) {
                        break
                    }
                }
            }

            onFileSuccessfullySaved(savedFile, cleanName, output)
        } catch (e: Exception) {
            Log.e(TAG, "Error in saveDirectStreamUpload", e)
            sendResponse(output, 500, "application/json", """{"status":"error","message":"${e.localizedMessage}"}""")
        }
    }

    private fun parseMultipartUpload(
        boundary: String,
        input: InputStream,
        contentLength: Long,
        output: OutputStream
    ) {
        try {
            val delimiter = "--$boundary".toByteArray(Charsets.UTF_8)
            var savedFile: File? = null
            var originalFileName = ""

            val partHeader = readHeader(input)
            val fnMatch = Regex("""filename=(?:["']?)([^"';\r\n]+)""").find(partHeader)
            if (fnMatch != null) {
                var rawName = fnMatch.groupValues[1]
                try {
                    rawName = URLDecoder.decode(rawName, "UTF-8")
                } catch (_: Exception) {}
                originalFileName = File(rawName).name
                if (originalFileName.endsWith(".txt", ignoreCase = true) || originalFileName.endsWith(".epub", ignoreCase = true)) {
                    savedFile = File(booksDir, originalFileName)
                }
            }

            if (savedFile != null) {
                FileOutputStream(savedFile).use { fos ->
                    val buf = ByteArray(32768)
                    var read: Int
                    var totalRead = 0L
                    while (input.read(buf).also { read = it } != -1) {
                        fos.write(buf, 0, read)
                        totalRead += read
                        if (contentLength > 0 && totalRead >= contentLength - delimiter.size - 32) {
                            break
                        }
                    }
                }

                onFileSuccessfullySaved(savedFile, originalFileName, output)
            } else {
                sendResponse(output, 400, "application/json", """{"status":"error","message":"仅支持 .txt 和 .epub 格式文件"}""")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in parseMultipartUpload", e)
            sendResponse(output, 500, "application/json", """{"status":"error","message":"${e.localizedMessage}"}""")
        }
    }

    private fun onFileSuccessfullySaved(file: File, fileName: String, output: OutputStream) {
        val uri = Uri.fromFile(file)
        val bookTitle = EpubParser.cleanBookTitle(fileName)
        val bookItem = BookItem(
            uriString = uri.toString(),
            title = bookTitle,
            charOffset = 0,
            totalChars = file.length().toInt(),
            lastChapterTitle = "新导入",
            lastReadTime = System.currentTimeMillis()
        )

        scope.launch(Dispatchers.IO) {
            DataStoreManager.updateBookInShelf(context, uri, 0, file.length().toInt(), "新导入")
        }
        _uploadedCount.value += 1
        onBookUploaded?.invoke(bookItem)

        val responseJson = """{"status":"ok","fileName":"$fileName"}"""
        sendResponse(output, 200, "application/json", responseJson)
    }

    private fun sendResponse(output: OutputStream, statusCode: Int, contentType: String, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val statusText = when (statusCode) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Internal Server Error"
        }
        val header = "HTTP/1.1 $statusCode $statusText\r\n" +
                "Content-Type: $contentType; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Connection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun serveWebPage(output: OutputStream) {
        val html = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <title>WatchReader 腕上无线传书</title>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; }
                    body { background: #0f1117; color: #e1e4ea; display: flex; flex-direction: column; align-items: center; min-height: 100vh; padding: 24px 16px; }
                    .card { background: #1a1d26; border-radius: 16px; width: 100%; max-width: 520px; padding: 28px; box-shadow: 0 10px 30px rgba(0,0,0,0.5); border: 1px solid #2d3139; }
                    .title { font-size: 20px; font-weight: 700; color: #58a6ff; text-align: center; margin-bottom: 8px; }
                    .desc { font-size: 13px; color: #8b949e; text-align: center; margin-bottom: 24px; }
                    .drop-zone { border: 2px dashed #388bfd; border-radius: 12px; padding: 36px 20px; text-align: center; background: rgba(56,139,253,0.04); cursor: pointer; transition: all 0.2s ease; margin-bottom: 20px; }
                    .drop-zone:hover, .drop-zone.dragover { background: rgba(56,139,253,0.12); border-color: #58a6ff; }
                    .drop-text { font-size: 15px; font-weight: 600; color: #c9d1d9; margin-bottom: 6px; }
                    .drop-sub { font-size: 12px; color: #8b949e; }
                    .btn { background: #238636; color: #ffffff; border: none; padding: 12px 20px; border-radius: 8px; font-size: 15px; font-weight: 600; cursor: pointer; width: 100%; transition: background 0.2s; }
                    .btn:hover { background: #2ea043; }
                    .btn:disabled { background: #234c2e; color: #8b949e; cursor: not-allowed; }
                    #fileInput { display: none; }
                    .file-list { margin-top: 20px; list-style: none; }
                    .file-item { display: flex; justify-content: space-between; align-items: center; background: #21262d; padding: 10px 14px; border-radius: 8px; font-size: 13px; margin-bottom: 8px; }
                    .file-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 75%; }
                    .file-status { font-weight: 600; font-size: 12px; }
                    .success { color: #3fb950; }
                    .error { color: #f85149; }
                    .progress-bar { height: 4px; background: #388bfd; width: 0%; border-radius: 2px; transition: width 0.2s; margin-top: 12px; }
                </style>
            </head>
            <body>
                <div class="card">
                    <div class="title">WatchReader 腕上无线传书</div>
                    <div class="desc">支持将本地 TXT 与 EPUB 小说直接无线推送到手表书架</div>
                    <div class="drop-zone" id="dropZone">
                        <div class="drop-text">点击或拖拽小说文件到此处</div>
                        <div class="drop-sub">支持 .txt、.epub 格式</div>
                    </div>
                    <input type="file" id="fileInput" multiple accept=".txt,.epub">
                    <button class="btn" id="uploadBtn" disabled>开始传输到手表</button>
                    <div class="progress-bar" id="progressBar"></div>
                    <ul class="file-list" id="fileList"></ul>
                </div>

                <script>
                    const dropZone = document.getElementById('dropZone');
                    const fileInput = document.getElementById('fileInput');
                    const uploadBtn = document.getElementById('uploadBtn');
                    const fileList = document.getElementById('fileList');
                    const progressBar = document.getElementById('progressBar');
                    let selectedFiles = [];

                    dropZone.addEventListener('click', () => fileInput.click());
                    dropZone.addEventListener('dragover', (e) => { e.preventDefault(); dropZone.classList.add('dragover'); });
                    dropZone.addEventListener('dragleave', () => dropZone.classList.remove('dragover'));
                    dropZone.addEventListener('drop', (e) => {
                        e.preventDefault();
                        dropZone.classList.remove('dragover');
                        handleFiles(e.dataTransfer.files);
                    });
                    fileInput.addEventListener('change', (e) => handleFiles(e.target.files));

                    function handleFiles(files) {
                        for (let f of files) {
                            if (f.name.endsWith('.txt') || f.name.endsWith('.epub') || f.name.endsWith('.TXT') || f.name.endsWith('.EPUB')) {
                                selectedFiles.push(f);
                                const li = document.createElement('li');
                                li.className = 'file-item';
                                li.innerHTML = `<span class="file-name">${'$'}{f.name}</span><span class="file-status" style="color:#8b949e">待传输</span>`;
                                fileList.appendChild(li);
                            }
                        }
                        if (selectedFiles.length > 0) uploadBtn.disabled = false;
                    }

                    uploadBtn.addEventListener('click', async () => {
                        uploadBtn.disabled = true;
                        const items = fileList.querySelectorAll('.file-item');
                        for (let i = 0; i < selectedFiles.length; i++) {
                            const file = selectedFiles[i];
                            const statusEl = items[i].querySelector('.file-status');
                            statusEl.innerText = '传输中...';
                            statusEl.style.color = '#58a6ff';

                            try {
                                const uploadUrl = '/upload?filename=' + encodeURIComponent(file.name);
                                const res = await fetch(uploadUrl, {
                                    method: 'POST',
                                    headers: {
                                        'Content-Type': 'application/octet-stream',
                                        'X-Filename': encodeURIComponent(file.name)
                                    },
                                    body: file
                                });
                                const data = await res.json();
                                if (data.status === 'ok') {
                                    statusEl.innerText = '已送达手表';
                                    statusEl.className = 'file-status success';
                                } else {
                                    statusEl.innerText = data.message || '失败';
                                    statusEl.className = 'file-status error';
                                }
                            } catch (e) {
                                statusEl.innerText = '连接超时';
                                statusEl.className = 'file-status error';
                            }
                            progressBar.style.width = ((i + 1) / selectedFiles.length * 100) + '%';
                        }
                        selectedFiles = [];
                    });
                </script>
            </body>
            </html>
        """.trimIndent()
        sendResponse(output, 200, "text/html", html)
    }
}
