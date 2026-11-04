package com.watchreader

import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 偏好落盘的成对写入：状态先改、落盘后台跑，两者一一对应。
 *
 * 收口前每个偏好 setter 都在自己身上重复同一段三件套（归一参数 → update → launch(IO) 落盘），
 * 十几份拷贝里任何一份写错顺序（先落盘后改状态）都会让 UI 与磁盘短暂背离，而这种背离没有
 * 任何外部可观察的症状——只有重启后才暴露。把「成对」这件事本身变成唯一实现，setter 只剩
 * 「写哪个字段、落到哪个键」两件事实。
 *
 * 线程约定：[persist] 的状态写入在调用线程同步完成；[save] 永远在 [io] 上执行（生产即 IO），
 * 与收口前的 viewModelScope.launch(Dispatchers.IO) 逐字等价。
 */
internal class ReaderPreferenceWrites(
    private val uiState: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {

    /** 更新状态并在后台把它落盘：两次动作成对出现，不允许只做一半 */
    fun persist(mutate: (ReaderUiState) -> ReaderUiState, save: suspend () -> Unit) {
        uiState.update(mutate)
        scope.launch(io) { save() }
    }

    /** 只改内存态、不落盘（滚屏开关、搜索词这类不需要跨进程存活的临时态） */
    fun local(mutate: (ReaderUiState) -> ReaderUiState) {
        uiState.update(mutate)
    }
}

/**
 * 启动配置 → 屏幕状态：14 个偏好字段加落点（页面 / 装载中 / 上次书的 uri）一次写全。
 *
 * 收口前 init 里有两份 15 字段的 copy，reader 分支与 home 分支只差最后三行，任何一侧漏抄一个
 * 字段都会表现为「某个设置重启后回退默认值」，且两份不可能同时被测到。差异只剩参数里那三项。
 */
internal fun ReaderUiState.withLoadedConfig(
    config: AppInitialConfig,
    screen: Screen,
    isLoading: Boolean,
    currentUri: Uri?
): ReaderUiState = copy(
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
    screen = screen,
    isLoading = isLoading,
    currentUri = currentUri
)
