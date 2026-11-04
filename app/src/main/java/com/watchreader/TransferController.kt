package com.watchreader

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 传书页要展示的全部状态（纯数据，不含 Context / socket / 协程）。
 *
 * 与 ReaderUiState 里的 wifi* 字段一一对应，由适配层搬运；[port] 默认 8888 是
 * 首选端口，实际值在 [TransferController.open] 成功后取自绑定结果。
 */
@Immutable
data class TransferUiState(
    val isRunning: Boolean = false,
    val ipAddress: String? = null,
    val port: Int = 8888,
    val token: String? = null,
    val uploadedCount: Int = 0,
    val isTransferring: Boolean = false,
    val progress: Float = 0f,
    val fileName: String = ""
)

/**
 * 传书服务的最小可替换面：只暴露「启动」与两条状态流。
 *
 * 全部成员都是纯 JVM 可构造的类型（[TransferProgress]、[StateFlow]），因此测试用假实现
 * 即可驱动控制器，不需要 ServerSocket、Context 或 Robolectric。
 * 生产实现由 WifiTransferServer 适配（见 ADR 中的 port/adapter 关系）。
 */
internal interface LanTransferServer {

    /** 上传进度（服务侧单实例流，值会被镜像进 [TransferUiState]） */
    val progress: StateFlow<TransferProgress>

    /** 累计已入库书籍数（服务侧单实例流） */
    val uploadedCount: StateFlow<Int>

    /**
     * 启动监听并返回本次运行的绑定信息。
     *
     * 约定：绑定失败（全部候选端口 bind 失败）返回 null 而不是抛异常——控制器据此保持
     * 未运行态；抛异常同样被控制器吞成未运行态，作为兜底。
     */
    fun start(): LanTransferBinding?
}

/** 一次运行的连接信息与停止入口；端口是实际绑定端口，不是首选端口 */
internal interface LanTransferBinding {
    val ipAddress: String?
    val port: Int
    val token: String?
    fun stop()
}

/**
 * 局域网传书服务的生命周期与状态镜像（纯 JVM，无 Android 依赖）。
 *
 * 职责：
 * - [open]：首次调用时创建服务（[factory] 只调一次，与旧实现「一个 ViewModel 一个 server」一致），
 *   启动后把 ip/端口/令牌写入 [state]，并把进度与计数两条流镜像进去；
 * - [close]：取消镜像、停止服务、状态全量复位；未运行时调用无副作用。
 *
 * 线程契约：**open/close 是阻塞调用**（启动要 bind 最多 6 个候选端口并枚举网卡），调用方必须在
 * IO 线程执行（旧实现用 withContext(Dispatchers.IO) 包住 server.start()/getLocalIpAddress()）。
 * 镜像协程跑在注入的 [scope] 上，不额外切线程。
 *
 * 幂等与竞态：
 * - 已运行时 [open] 直接返回，不重启、不重复订阅；
 * - 未运行时 [close] 直接返回；
 * - [generation] 递增用于挡住 close 之后仍在途的镜像回调，避免「关页后又翻出上传中」。
 *
 * 失败语义：启动失败一律折叠为「未运行态 + state 复位」，异常不向调用方冒泡——适配层的调用点
 * 在 viewModelScope.launch 里，冒泡会让 App 崩溃；失败原因是否要展示给用户由适配层决定。
 */
internal class TransferController(
    private val scope: CoroutineScope,
    private val factory: () -> LanTransferServer
) {

    private val _state = MutableStateFlow(TransferUiState())
    val state: StateFlow<TransferUiState> = _state.asStateFlow()

    /** 服务实例跨 open/close 复用：旧实现同样只在首次 open 时构造，令牌因此保持不变 */
    private var server: LanTransferServer? = null
    private var binding: LanTransferBinding? = null
    private var mirrorJob: Job? = null
    private var generation = 0

    /** 启动服务并把连接信息与两条状态流发布到 [state]；已运行时幂等返回 */
    fun open() {
        if (binding != null) return

        val target = server ?: factory().also { server = it }
        val started = try {
            target.start()
        } catch (_: Exception) {
            null
        }
        if (started == null) {
            _state.value = TransferUiState()
            return
        }

        binding = started
        val currentGeneration = ++generation
        _state.value = TransferUiState(
            isRunning = true,
            ipAddress = started.ipAddress,
            port = started.port,
            token = started.token
        )

        // StateFlow 订阅即回放当前值，因此不需要额外做一次初始同步
        mirrorJob = scope.launch {
            launch {
                target.uploadedCount.collect { count ->
                    if (generation == currentGeneration) {
                        _state.update { it.copy(uploadedCount = count) }
                    }
                }
            }
            launch {
                target.progress.collect { progress ->
                    if (generation == currentGeneration) {
                        _state.update {
                            it.copy(
                                isTransferring = progress.isTransferring,
                                progress = progress.progress,
                                fileName = progress.fileName
                            )
                        }
                    }
                }
            }
        }
    }

    /** 停止服务并把状态全量复位；未运行时幂等返回 */
    fun close() {
        if (binding == null) return

        mirrorJob?.cancel()
        mirrorJob = null
        generation++
        val target = binding
        binding = null
        try {
            target?.stop()
        } catch (_: Exception) {
            // 停止失败也要复位：否则界面会永久停在「运行中」且再也关不掉
        }
        _state.value = TransferUiState()
    }
}
