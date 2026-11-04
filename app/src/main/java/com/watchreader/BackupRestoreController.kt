package com.watchreader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * 书架备份与恢复的三段状态机：导出提示、恢复预览（待确认原文）、取消或确认覆盖。
 *
 * 待确认原文、书目计数与提示条的写入全部收在这里；ViewModel 只留同名薄委托，
 * 屏幕侧调用点不变。落盘与配置读取经构造注入，故本类零 Android 依赖，
 * 可用一条 MutableStateFlow 直接驱动三段迁移。
 */
internal class BackupRestoreController(
    private val uiState: MutableStateFlow<ReaderUiState>,
    private val exportJson: suspend () -> String,
    private val restoreFromJson: suspend (String) -> Int,
    private val loadConfig: suspend () -> AppInitialConfig
) {
    // 待确认的恢复备份原文：SAF 选完文件先解析预览，用户在确认浮层点头后才覆盖
    @Volatile
    private var pendingRestoreJson: String? = null

    /** 生成备份 JSON（调用方负责写入 SAF 目标文件） */
    suspend fun export(): String = withContext(Dispatchers.IO) { exportJson() }

    /**
     * 解析备份供确认浮层预览：合法则记录原文并返回书目数，非法返回 null（文件不是有效备份）
     */
    fun preview(json: String): Int? {
        val payload = when (val decoded = BackupCodec.decodeResult(json)) {
            is BackupCodec.DecodeResult.Success -> decoded.payload
            is BackupCodec.DecodeResult.Failure -> {
                // 失败原因逐码透出：结构损坏与「类型/版本不符」是两种完全不同的误操作
                uiState.update { it.copy(errorMessage = "恢复失败：${decoded.message}") }
                return null
            }
        }
        pendingRestoreJson = json
        uiState.update { it.copy(pendingRestoreCount = payload.shelf.size) }
        return payload.shelf.size
    }

    /** 取消恢复：丢弃待确认备份 */
    fun cancel() {
        pendingRestoreJson = null
        uiState.update { it.copy(pendingRestoreCount = null) }
    }

    /** 确认恢复：走覆盖路径 */
    suspend fun confirm() {
        val json = pendingRestoreJson
        pendingRestoreJson = null
        uiState.update { it.copy(pendingRestoreCount = null) }
        if (json != null) {
            restore(json)
        }
    }

    /**
     * 从备份 JSON 恢复：成功后重载全部配置与书架到当前状态
     * @return 是否恢复成功
     */
    suspend fun restore(json: String): Boolean {
        val count = withContext(Dispatchers.IO) { restoreFromJson(json) }
        if (count < 0) return false
        val config = withContext(Dispatchers.IO) { loadConfig() }
        uiState.update {
            it.copy(
                fontSize = config.fontSize,
                autoScrollSpeed = config.autoScrollSpeed,
                appBrightness = config.appBrightness,
                bookshelf = config.bookshelf,
                themeMode = config.themeMode,
                tapPageArea = config.tapPageArea,
                cleanTypography = config.cleanTypography,
                fontType = config.fontType,
                readDurationSec = config.readDurationSec,
                readDays = config.readDays,
                readGoalMinutes = config.readGoalMinutes,
                readGoalCelebrated = config.readGoalCelebrated,
                lineSpacing = config.lineSpacing,
                letterSpacing = config.letterSpacing,
                infoMessage = "已恢复 $count 本书与全部设置"
            )
        }
        return true
    }

    /** 备份导出结果提示（导出本身不改变状态，无需重载） */
    fun notifyExported(success: Boolean) {
        uiState.update {
            if (success) {
                it.copy(infoMessage = "备份已导出")
            } else {
                it.copy(errorMessage = "备份导出失败：无法写入所选文件")
            }
        }
    }

    fun notifyRestoreFailed() {
        uiState.update { it.copy(errorMessage = "恢复失败：不是有效的备份文件") }
    }

    /** 信息条自动消失 */
    fun clearInfoMessage() {
        uiState.update { it.copy(infoMessage = null) }
    }
}
