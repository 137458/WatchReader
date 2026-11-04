package com.watchreader

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 返回手势的路由表：屏幕状态 → 该做什么，以及关书这条统一出口。
 *
 * 收口前 handleBack 是一段 44 行 when 表达式，六条分支各自直接调用 ViewModel 的其它方法并
 * 顺手发一个 IO 协程；它的真假值（拦截 or 交给系统回桌面）是唯一对外的输出，却和六种
 * 副作用搅在同一段代码里，只能整体读。这里把「哪条分支做什么」拆成注入的动作，
 * 判定顺序原样保留：菜单/章节列表/闪读 → 回阅读页；搜索 → 停扫再回阅读页；传书 → 关服务；
 * 阅读页 → 关书；装载中 → 弃装载回书架；书架 → 不拦截。
 *
 * 参数里的动作全部是 [ReaderViewModel] 已有的公开能力，本类不新增语义、也不读 Context：
 * 删掉它，六条分支与关书三步（落盘进度 → 停搜/关会话 → 复位书架）会重新摊回 ViewModel
 * 并把 DataStore 直接写进路由判定里（deletion test）。
 */
internal class ReaderBackNavigation(
    private val uiState: MutableStateFlow<ReaderUiState>,
    private val scope: CoroutineScope,
    private val store: ReaderProgressStore,
    private val flushReadingPosition: () -> Unit,
    private val resetSearch: () -> Unit,
    private val closeSession: () -> Unit,
    private val returnToReader: () -> Unit,
    private val cancelSearch: () -> Unit,
    private val closeWifiTransfer: () -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {

    /** 处理系统返回手势；返回 false 表示本次不拦截，交给系统返回桌面 */
    fun handleBack(): Boolean {
        return when (uiState.value.screen) {
            is Screen.Menu, is Screen.ChapterList, is Screen.Rsvp -> {
                returnToReader()
                true
            }
            is Screen.Search -> {
                // 离开搜索页即停扫：O(n) 后仍不应在阅读/书架页后台空转
                cancelSearch()
                returnToReader()
                true
            }
            is Screen.WifiTransfer -> {
                closeWifiTransfer()
                true
            }
            is Screen.Reader -> {
                closeBookInternal()
                true
            }
            is Screen.Loading -> {
                abandonLoading()
                true
            }
            is Screen.Home -> {
                saveHomeScreen()
                false
            }
        }
    }

    /** 关闭当前书籍返回主页 */
    fun closeBook() = closeBookInternal()

    /** 关书统一路径：落盘进度 + 取消预热/搜索 + 清空章节缓存 + 复位到书架 */
    private fun closeBookInternal() {
        flushReadingPosition()
        resetSearch()
        closeSession()
        scope.launch(io) {
            store.saveLastScreenHome()
            val shelf = store.loadShelf()
            uiState.update {
                it.copy(
                    currentUri = null,
                    fileName = "",
                    chapters = emptyList(),
                    currentChapterIndex = 0,
                    currentChapterContent = null,
                    bookshelf = shelf,
                    screen = Screen.Home
                )
            }
        }
    }

    /** 装载中按返回：弃掉正在装载的书，直接回书架（不写上次页面为 reader） */
    private fun abandonLoading() {
        closeSession()
        scope.launch(io) {
            store.saveLastScreenHome()
            val shelf = store.loadShelf()
            uiState.update {
                it.copy(
                    currentUri = null,
                    isLoading = false,
                    bookshelf = shelf,
                    screen = Screen.Home
                )
            }
        }
    }

    /** 书架页按返回：只记下「上次停在书架」，不拦截（交系统回到桌面） */
    private fun saveHomeScreen() {
        scope.launch(io) { store.saveLastScreenHome() }
    }
}
