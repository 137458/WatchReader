package com.watchreader

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读域三个收口点（偏好成对写入 / 启动配置映射 / 返回路由）的行为测试。
 *
 * 三者都不碰 Android 与磁盘：ReaderPreferenceWrites 的落盘是一个 suspend lambda，
 * ReaderBackNavigation 的持久化走 ReaderProgressStore 接口，withLoadedConfig 是纯函数。
 * 因此这里直接用记录型 fake 与 Unconfined 调度器驱动，不需要 CoroutineScope 的虚拟时间。
 */
class ReaderViewModelDomainsTest {

    // ---------- 记录型 fake ----------

    private class RecordingStore : ReaderProgressStore {
        val events = mutableListOf<String>()
        var shelf: List<BookItem> = emptyList()

        override suspend fun loadShelf(): List<BookItem> {
            events += "loadShelf"
            return shelf
        }

        override suspend fun updateInShelf(
            uri: Uri,
            charOffset: Int,
            totalChars: Int,
            chapterTitle: String
        ): List<BookItem> {
            events += "updateInShelf"
            return shelf
        }

        override suspend fun loadBookmarks(uriString: String): List<Bookmark> {
            events += "loadBookmarks"
            return emptyList()
        }

        override suspend fun savePosition(uri: Uri, charOffset: Int, totalChars: Int, chapterTitle: String) {
            events += "savePosition"
        }

        override suspend fun clearPosition() {
            events += "clearPosition"
        }

        override suspend fun saveLastScreenHome() {
            events += "saveLastScreenHome"
        }
    }

    private fun scope() = CoroutineScope(Dispatchers.Unconfined)

    private fun initialConfig(): AppInitialConfig = AppInitialConfig(
        fontSize = 20,
        autoScrollSpeed = 66f,
        appBrightness = 0.7f,
        bookshelf = listOf(
            BookItem(
                uriString = "content://books/shelf.txt",
                title = "shelf.txt",
                charOffset = 128,
                totalChars = 40960,
                lastChapterTitle = "第三章",
                lastReadTime = 1_760_000_000_000L
            )
        ),
        lastScreen = "reader",
        lastUri = null,
        lastCharOffset = 512,
        themeMode = 1,
        tapPageArea = 2,
        cleanTypography = false,
        fontType = 1,
        readDurationSec = 3600L,
        readDays = mapOf("2026-11-05" to 600L),
        readGoalMinutes = 30,
        readGoalCelebrated = "2026-11-05",
        lineSpacing = 2,
        letterSpacing = 2
    )

    // ---------- A. 偏好成对写入 ----------

    @Test
    fun persistUpdatesStateBeforeReturning() {
        val state = MutableStateFlow(ReaderUiState())
        val writes = ReaderPreferenceWrites(state, scope(), Dispatchers.Unconfined)

        writes.persist({ it.copy(fontSize = 22) }) { }

        assertEquals(22, state.value.fontSize)
    }

    @Test
    fun persistRunsSaveExactlyOnce() {
        val state = MutableStateFlow(ReaderUiState())
        val writes = ReaderPreferenceWrites(state, scope(), Dispatchers.Unconfined)
        var saves = 0

        writes.persist({ it.copy(fontSize = 18) }) { saves += 1 }

        assertEquals(1, saves)
    }

    @Test
    fun persistSaveObservesAlreadyUpdatedState() {
        val state = MutableStateFlow(ReaderUiState())
        val writes = ReaderPreferenceWrites(state, scope(), Dispatchers.Unconfined)
        var seenDuringSave = -1

        writes.persist({ it.copy(fontSize = 17) }) { seenDuringSave = state.value.fontSize }

        assertEquals(17, seenDuringSave)
    }

    @Test
    fun localUpdatesStateWithoutSaving() {
        val state = MutableStateFlow(ReaderUiState())
        val writes = ReaderPreferenceWrites(state, scope(), Dispatchers.Unconfined)
        var saves = 0

        writes.local { it.copy(isAutoScrolling = true) }

        assertTrue(state.value.isAutoScrolling)
        assertEquals(0, saves)
    }

    // ---------- B. 启动配置 → 屏幕状态 ----------

    @Test
    fun withLoadedConfigCopiesEveryPreferenceField() {
        val loaded = ReaderUiState().withLoadedConfig(
            config = initialConfig(),
            screen = Screen.Home,
            isLoading = false,
            currentUri = null
        )

        assertEquals(20, loaded.fontSize)
        assertEquals(66f, loaded.autoScrollSpeed, 0.001f)
        assertEquals(0.7f, loaded.appBrightness, 0.001f)
        assertEquals(1, loaded.themeMode)
        assertEquals(2, loaded.tapPageArea)
        assertFalse(loaded.cleanTypography)
        assertEquals(1, loaded.fontType)
        assertEquals(3600L, loaded.readDurationSec)
        assertEquals(600L, loaded.readDays["2026-11-05"])
        assertEquals(30, loaded.readGoalMinutes)
        assertEquals("2026-11-05", loaded.readGoalCelebrated)
        assertEquals(2, loaded.lineSpacing)
        assertEquals(2, loaded.letterSpacing)
        assertEquals(1, loaded.bookshelf.size)
        assertEquals("content://books/shelf.txt", loaded.bookshelf[0].uriString)
        assertEquals(128, loaded.bookshelf[0].charOffset)
    }

    @Test
    fun withLoadedConfigAppliesLandingWithoutTouchingTransientFields() {
        val loaded = ReaderUiState(
            currentChapterIndex = 4,
            errorMessage = "上一次的报错",
            infoMessage = "上一次的提示",
            rsvpSpeed = 500f
        ).withLoadedConfig(
            config = initialConfig(),
            screen = Screen.Loading,
            isLoading = true,
            currentUri = null
        )

        assertEquals(Screen.Loading, loaded.screen)
        assertTrue(loaded.isLoading)
        assertNull(loaded.currentUri)
        assertEquals(4, loaded.currentChapterIndex)
        assertEquals("上一次的报错", loaded.errorMessage)
        assertEquals("上一次的提示", loaded.infoMessage)
        assertEquals(500f, loaded.rsvpSpeed, 0.001f)
    }

    @Test
    fun withLoadedConfigForwardsReaderLandingAndUriSlot() {
        // 单测桩下 android.net.Uri.parse 恒返回 null（unitTests.isReturnDefaultValues），
        // 故这里只能锁「落点与 uri 槽位被原样转发」，非空 uri 的搬迁路径留给实机验证。
        val loaded = ReaderUiState().withLoadedConfig(
            config = initialConfig(),
            screen = Screen.Reader(512, 3),
            isLoading = false,
            currentUri = null
        )

        assertEquals(Screen.Reader(512, 3), loaded.screen)
        assertFalse(loaded.isLoading)
        assertNull(loaded.currentUri)
    }

    // ---------- C. 章节前后翻的索引边界 ----------

    @Test
    fun nextChapterIndexAdvancesWithinRange() {
        assertEquals(1, ChapterNavigator.nextChapterIndex(0, 3))
        assertEquals(2, ChapterNavigator.nextChapterIndex(1, 3))
    }

    @Test
    fun nextChapterIndexIsNullOnLastChapter() {
        assertNull(ChapterNavigator.nextChapterIndex(2, 3))
        assertNull(ChapterNavigator.nextChapterIndex(0, 1))
    }

    @Test
    fun nextChapterIndexIsNullWhenIndexBeyondRange() {
        assertNull(ChapterNavigator.nextChapterIndex(5, 3))
    }

    @Test
    fun previousChapterIndexStepsBack() {
        assertEquals(0, ChapterNavigator.previousChapterIndex(1))
        assertEquals(2, ChapterNavigator.previousChapterIndex(3))
    }

    @Test
    fun previousChapterIndexIsNullOnFirstChapter() {
        assertNull(ChapterNavigator.previousChapterIndex(0))
    }

    // ---------- D. 返回路由 ----------

    private class BackHarness(screen: Screen) {
        val state = MutableStateFlow(ReaderUiState(screen = screen))
        val store = RecordingStore()
        val calls = mutableListOf<String>()
        val navigation = ReaderBackNavigation(
            uiState = state,
            scope = CoroutineScope(Dispatchers.Unconfined),
            store = store,
            flushReadingPosition = { calls += "flush" },
            resetSearch = { calls += "resetSearch" },
            closeSession = { calls += "closeSession" },
            returnToReader = { calls += "returnToReader" },
            cancelSearch = { calls += "cancelSearch" },
            closeWifiTransfer = { calls += "closeWifiTransfer" },
            io = Dispatchers.Unconfined
        )
    }

    @Test
    fun backOnMenuReturnsToReaderAndConsumes() {
        val harness = BackHarness(Screen.Menu)

        assertTrue(harness.navigation.handleBack())
        assertEquals(listOf("returnToReader"), harness.calls)
        assertEquals(emptyList<String>(), harness.store.events)
    }

    @Test
    fun backOnChapterListReturnsToReader() {
        val harness = BackHarness(Screen.ChapterList(bookmarkTab = true))

        assertTrue(harness.navigation.handleBack())
        assertEquals(listOf("returnToReader"), harness.calls)
    }

    @Test
    fun backOnRsvpReturnsToReader() {
        val harness = BackHarness(Screen.Rsvp)

        assertTrue(harness.navigation.handleBack())
        assertEquals(listOf("returnToReader"), harness.calls)
    }

    @Test
    fun backOnSearchCancelsSearchBeforeReturning() {
        val harness = BackHarness(Screen.Search)

        assertTrue(harness.navigation.handleBack())
        assertEquals(listOf("cancelSearch", "returnToReader"), harness.calls)
    }

    @Test
    fun backOnWifiTransferClosesTransfer() {
        val harness = BackHarness(Screen.WifiTransfer)

        assertTrue(harness.navigation.handleBack())
        assertEquals(listOf("closeWifiTransfer"), harness.calls)
    }

    @Test
    fun backOnReaderClosesBookAndResetsToShelf() {
        val harness = BackHarness(Screen.Reader(charOffset = 900, chapterIndex = 2))
        harness.state.value = harness.state.value.copy(
            currentUri = Uri.parse("content://books/a.txt"),
            fileName = "a.txt",
            isLoading = true
        )

        assertTrue(harness.navigation.handleBack())

        // 关书顺序：先落盘进度、再停搜、最后关会话
        assertEquals(listOf("flush", "resetSearch", "closeSession"), harness.calls)
        assertNull(harness.state.value.currentUri)
        assertEquals("", harness.state.value.fileName)
        assertEquals(emptyList<Chapter>(), harness.state.value.chapters)
        assertEquals(0, harness.state.value.currentChapterIndex)
        assertNull(harness.state.value.currentChapterContent)
        assertEquals(Screen.Home, harness.state.value.screen)
        assertEquals(listOf("saveLastScreenHome", "loadShelf"), harness.store.events)
    }

    @Test
    fun backOnLoadingAbandonsLoadWithoutFlushingProgress() {
        val harness = BackHarness(Screen.Loading)
        harness.state.value = harness.state.value.copy(
            currentUri = Uri.parse("content://books/b.txt"),
            isLoading = true
        )

        assertTrue(harness.navigation.handleBack())

        assertEquals(listOf("closeSession"), harness.calls)
        assertNull(harness.state.value.currentUri)
        assertFalse(harness.state.value.isLoading)
        assertEquals(Screen.Home, harness.state.value.screen)
        assertEquals(listOf("saveLastScreenHome", "loadShelf"), harness.store.events)
    }

    @Test
    fun backOnHomeIsNotConsumedAndOnlyRecordsHomeScreen() {
        val harness = BackHarness(Screen.Home)

        assertFalse(harness.navigation.handleBack())

        assertEquals(emptyList<String>(), harness.calls)
        assertEquals(listOf("saveLastScreenHome"), harness.store.events)
        assertEquals(Screen.Home, harness.state.value.screen)
    }

    @Test
    fun closeBookFlushesProgressBeforeResettingShelfState() {
        val harness = BackHarness(Screen.Reader())
        harness.state.value = harness.state.value.copy(currentUri = Uri.parse("content://books/c.txt"))

        harness.navigation.closeBook()

        assertEquals(listOf("flush", "resetSearch", "closeSession"), harness.calls)
        assertNull(harness.state.value.currentUri)
        assertEquals(Screen.Home, harness.state.value.screen)
    }
}
