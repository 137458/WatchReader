package com.watchreader

import kotlinx.coroutines.flow.StateFlow

/**
 * [LanTransferServer] 的生产适配器：把手写 ServerSocket 服务接进 [TransferController] 的接口。
 *
 * 只在 start 成功那一刻取一次连接信息——[WifiTransferServer.getLocalIpAddress] 会枚举网卡，
 * 若放到绑定的属性 getter 里懒计算，页面每次重组都会重新枚举。
 */
internal class WifiTransferServerAdapter(
    private val server: WifiTransferServer
) : LanTransferServer {

    override val progress: StateFlow<TransferProgress> get() = server.transferProgress

    override val uploadedCount: StateFlow<Int> get() = server.uploadedCount

    /** 六个候选端口全失败时返回 null（控制器据此保持未运行态），成功则返回本次运行的绑定 */
    override fun start(): LanTransferBinding? {
        if (!server.start()) return null
        val ip = server.getLocalIpAddress()
        val boundPort = server.activePort
        val token = server.accessToken
        return object : LanTransferBinding {
            override val ipAddress: String? = ip
            override val port: Int = boundPort
            override val token: String? = token
            override fun stop() = server.stop()
        }
    }
}
