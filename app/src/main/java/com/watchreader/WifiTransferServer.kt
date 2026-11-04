package com.watchreader

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 传书进度数据模型
 */
data class TransferProgress(
    val isTransferring: Boolean = false,
    val fileName: String = "",
    val progress: Float = 0f,
    val bytesRead: Long = 0L,
    val totalBytes: Long = 0L
)

/**
 * 局域网 Wi-Fi 网页无线传书轻量级 HTTP 服务
 *
 * 本类只做「传输管道」：端口绑定与回退、socket 生命周期、令牌鉴权、进度与触感、上传后入库。
 * 协议解析与动作分发分别落在三个纯 JVM 模块里，本类不解析请求行、不拼响应头：
 * - [HttpRequestParser]：请求头 → [HttpRequest]；
 * - [EntityBody]：按 Content-Length / chunked 把 socket 流还原成一个实体体；
 * - [TransferEndpoint]：路径 + 请求体 → [TransferResponse]，副作用经 [TransferEndpoint.Listener] 回调回本类；
 * - [HttpResponseWriter]：把 [TransferResponse] 写回 socket。
 *
 * 特性：
 * 1. 0 第三方依赖：纯原生 ServerSocket 构建，包体积 0 增加。
 * 2. 智能多端口回退：自动规避端口占用（8888 -> 8080 -> 8989 -> 9090 -> 动态分配），彻底解决 EADDRINUSE。
 * 3. 全链路进度追踪：实时向手表端与 Web 浏览器推送毫秒级传输字节与百分比进度。
 * 4. 跨平台现代 Web UI：支持 XHR Upload Progress，实时展示上传百分比与动画。
 * 5. 自动入库与触觉反馈：上传完成自动校验、保存至私有存储并即时录入书架，触发双重确认震感。
 */
class WifiTransferServer(
    private val context: Context,
    private val preferredPort: Int = 8888,
    private val onBookUploaded: ((BookItem) -> Unit)? = null,
    private val onBookDeleted: ((String) -> Unit)? = null
) {
    private val TAG = "WifiTransferServer"
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var scope = CoroutineScope(Dispatchers.IO)

    /** 本次服务会话的访问令牌：二维码 URL 携带，所有请求校验，杜绝同网段未授权访问 */
    private val sessionToken = generateToken()

    /** 对外暴露当前会话令牌（拼入二维码 URL） */
    val accessToken: String get() = sessionToken

    companion object {
        /**
         * 单文件上传硬上限 50MB（实现见 [TransferEndpoint.MAX_UPLOAD_BYTES]）。
         *
         * 手表可用存储远小于手机，且上传直接写进 books 目录（不落临时盘），一次误传超大文件
         * 就能把 /data 写满并让后续所有写盘操作失败；50MB 对纯文本/EPUB 小说已是充裕上界。
         * 声明了 Content-Length 的请求在开写前就拒绝，未声明长度的（chunked）由流式累计兜底。
         */
        internal const val MAX_UPLOAD_BYTES: Long = TransferEndpoint.MAX_UPLOAD_BYTES

        /** 会话令牌长度（字符） */
        private const val TOKEN_LENGTH = 12

        /** base62 字符表：URL query 安全，无需额外百分号编码 */
        private const val TOKEN_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"

        private val tokenRandom = SecureRandom()

        /** 生成 12 位 base62 会话令牌（SecureRandom.nextInt 内部拒绝采样，无模偏置） */
        internal fun generateToken(): String {
            val builder = StringBuilder(TOKEN_LENGTH)
            repeat(TOKEN_LENGTH) {
                builder.append(TOKEN_ALPHABET[tokenRandom.nextInt(TOKEN_ALPHABET.length)])
            }
            return builder.toString()
        }

        /** 上传体是否超限；contentLength < 0 表示未声明长度，不在此预判 */
        internal fun uploadExceedsLimit(contentLength: Long, limit: Long = MAX_UPLOAD_BYTES): Boolean =
            TransferEndpoint.uploadExceedsLimit(contentLength, limit)

        /** 书籍清单 JSON（实现见 [TransferEndpoint.bookListJson]） */
        internal fun bookListJson(entries: List<BookFileEntry>): String =
            TransferEndpoint.bookListJson(entries)

        /** 解析 target 里的查询串（实现见 [parseQuery]） */
        internal fun parseQueryParams(rawPath: String): Map<String, String> = parseQuery(rawPath)

        /** 恒时比较令牌：空值/不一致一律拒绝，空串也视作未提供 */
        internal fun tokenMatches(presented: String?, expected: String): Boolean {
            if (presented.isNullOrEmpty()) return false
            return MessageDigest.isEqual(
                presented.toByteArray(Charsets.UTF_8),
                expected.toByteArray(Charsets.UTF_8)
            )
        }
    }

    var activePort: Int = preferredPort
        private set

    private val _uploadedCount = MutableStateFlow(0)
    val uploadedCount: StateFlow<Int> = _uploadedCount.asStateFlow()

    private val _transferProgress = MutableStateFlow(TransferProgress())
    val transferProgress: StateFlow<TransferProgress> = _transferProgress.asStateFlow()

    private val booksDir: File by lazy {
        val dir = File(context.filesDir, "books")
        if (!dir.exists()) dir.mkdirs()
        dir
    }

    /** 端点把副作用回调到这里：进度、触感、入库都留在 Android 侧，端点本身保持纯 JVM */
    private val endpoint: TransferEndpoint by lazy {
        TransferEndpoint(
            booksDir = booksDir,
            isSupportedFileName = BookTextConverter::isSupportedFileName,
            uploadedCount = { _uploadedCount.value },
            listener = object : TransferEndpoint.Listener {

                override fun onUploadRejected(fileName: String, declaredBytes: Long) {
                    Log.w(TAG, "拒绝超限上传 $fileName：$declaredBytes > $MAX_UPLOAD_BYTES")
                }

                override fun onUploadStarted(fileName: String, declaredBytes: Long) {
                    _transferProgress.value = TransferProgress(
                        isTransferring = true,
                        fileName = fileName,
                        progress = 0.05f,
                        bytesRead = 0,
                        totalBytes = declaredBytes
                    )
                    RotaryHapticManager.performScrollTick(context, null)
                }

                override fun onUploadChunk(
                    fileName: String,
                    progress: Float,
                    bytesRead: Long,
                    declaredBytes: Long
                ) {
                    _transferProgress.value = TransferProgress(
                        isTransferring = true,
                        fileName = fileName,
                        progress = progress,
                        bytesRead = bytesRead,
                        totalBytes = declaredBytes
                    )
                }

                override fun onUploadMilestone() {
                    RotaryHapticManager.performScrollTick(context, null)
                }

                override fun onUploadAborted() {
                    _transferProgress.value = TransferProgress()
                }

                override fun onUploadSaved(file: File, fileName: String) {
                    onFileSuccessfullySaved(file, fileName)
                }

                override fun onBookDeleted(fileName: String) {
                    onBookDeleted?.invoke(fileName)
                }
            }
        )
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
     * 启动 HTTP 传书服务（支持端口自动回退，消除 EADDRINUSE 异常）
     */
    @Synchronized
    fun start(): Boolean {
        if (isRunning) return true
        val portCandidates = intArrayOf(preferredPort, 8080, 8989, 9090, 8889, 0)

        for (p in portCandidates) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(p))
                serverSocket = ss
                activePort = ss.localPort
                isRunning = true
                scope.launch {
                    listenForClients()
                }
                Log.i(TAG, "Wi-Fi transfer server successfully bound to port $activePort")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Port $p bind failed, trying next candidate...", e)
            }
        }
        isRunning = false
        return false
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
        // 取消全部在途协程（监听循环、上传写入、延时重置），重建 scope 供下次 start 使用；
        // 此前 stop 后在途上传仍会继续写完整个文件并回调无人收集的 StateFlow
        scope.cancel()
        scope = CoroutineScope(Dispatchers.IO)
        _transferProgress.value = TransferProgress()
        _uploadedCount.value = 0
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

            val request = HttpRequestParser.read(input)
            if (request == null) {
                socket.close()
                return
            }

            // 访问令牌校验：二维码 URL 与网页 JS 均携带 token，未授权请求一律拒绝，
            // 杜绝同网段任意设备/网页静默读架、删书、上传
            if (!tokenMatches(request.query["token"], sessionToken)) {
                HttpResponseWriter.write(
                    output,
                    TransferResponse.Text(
                        401,
                        "application/json",
                        """{"status":"error","message":"未授权：请通过手表二维码重新进入传书页"}"""
                    )
                )
                return
            }

            val body = EntityBody.open(request.headers, input)
            val response = endpoint.dispatch(request, body)
            // 端点内部吞掉异常并回 500，异常现场只剩状态码，这里补一条日志便于排查落盘失败
            if (response is TransferResponse.Text && response.status >= 500) {
                Log.w(TAG, "Endpoint returned ${response.status}: ${request.method} ${request.target}")
            }
            HttpResponseWriter.write(output, response)
        } catch (e: Exception) {
            Log.w(TAG, "Error handling client request", e)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    /** 上传落盘成功：入库、进度 100%、触感；响应体仍由端点返回 */
    private fun onFileSuccessfullySaved(file: File, fileName: String) {
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
            onBookUploaded?.invoke(bookItem)
        }
        // 并发上传完成时避免读-改-写竞态丢计数
        _uploadedCount.update { it + 1 }

        // 设置 100% 状态
        _transferProgress.value = TransferProgress(
            isTransferring = true,
            fileName = bookTitle,
            progress = 1.0f,
            bytesRead = file.length(),
            totalBytes = file.length()
        )

        RotaryHapticManager.performSuccessFeedback(context)

        // 1.2 秒后平滑恢复常驻就绪视图
        scope.launch {
            delay(1200)
            _transferProgress.value = TransferProgress(isTransferring = false)
        }
    }
}
