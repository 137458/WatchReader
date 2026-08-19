package com.watchreader

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 页面路由状态
 */
sealed class Screen {
    object Home : Screen()
    object Loading : Screen()
    data class Reader(val charOffset: Int = 0, val chapterIndex: Int = 0) : Screen()
    object ChapterList : Screen()
    object Menu : Screen()
    object Rsvp : Screen()
    object WifiTransfer : Screen()
}

/**
 * MainActivity — 遵循 Android / ColorOS Watch 官方规范的入口 Activity
 */
class MainActivity : ComponentActivity() {

    private val viewModel: ReaderViewModel by viewModels()

    // SAF 文件选择器
    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            viewModel.loadFile(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化 ViewModel 状态（从 DataStore 异步读取书架、字号、深色模式、亮度、滚速）
        viewModel.init()

        // 异步预热 OPPO 官方 Linearmotor 线性马达引擎
        lifecycleScope.launch(Dispatchers.Default) {
            RotaryHapticManager.initOplusLinearmotor(applicationContext)
        }

        // 全屏沉浸式
        @Suppress("DEPRECATION")
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        setContent {
            val uiState by viewModel.uiState.collectAsState()
            val colorScheme = if (uiState.isDarkMode) WatchDarkColorScheme else WatchColorScheme

            // 动态同步 Window 底层 DecorView 背景色与硬件独立屏幕亮度
            SideEffect {
                BrightnessManager.applyToWindow(this@MainActivity, uiState.appBrightness)
                window.decorView.setBackgroundColor(
                    if (uiState.isDarkMode) AndroidColor.BLACK else AndroidColor.parseColor("#FFF7F4EB")
                )
            }

            MaterialTheme(
                colorScheme = colorScheme,
                typography = WatchTypography
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        AppContent(uiState)
                    }

                    // 极暗纯黑 Alpha 硬件加速遮罩（仅当亮度低于 12% 时激活，不拦截手势）
                    val overlayAlpha = BrightnessManager.calculateDarkOverlayAlpha(uiState.appBrightness)
                    if (overlayAlpha > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = overlayAlpha))
                        )
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.flushReadingPosition()
    }

    /**
     * 顶层分发事件：优先让原生 View 树（ScrollView / ListView）原生处理表冠转动与物理减速
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (super.dispatchGenericMotionEvent(event)) {
            return true
        }
        val focus = currentFocus
        if (focus != null && focus.dispatchGenericMotionEvent(event)) {
            return true
        }
        return false
    }

    @Composable
    private fun AppContent(uiState: ReaderUiState) {
        when (val current = uiState.screen) {
            is Screen.Home -> BookshelfScreen(
                bookshelf = uiState.bookshelf,
                searchQuery = uiState.searchQuery,
                fontSize = uiState.fontSize,
                isDarkMode = uiState.isDarkMode,
                onOpenFile = {
                    openFileLauncher.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream", "*/*"))
                },
                onOpenBook = { book -> viewModel.openFromShelf(book) },
                onDeleteBook = { book -> viewModel.deleteFromShelf(book) },
                onTogglePin = { book -> viewModel.toggleBookPin(book.uriString) },
                onSearchChange = { viewModel.setSearchQuery(it) },
                onOpenWifiTransfer = { viewModel.openWifiTransfer() },
                onFontSizeChange = { viewModel.updateFontSize(it) },
                onToggleDarkMode = { viewModel.toggleDarkMode() },
                errorMessage = uiState.errorMessage
            )

            is Screen.Loading -> LoadingScreen()

            is Screen.Reader -> ReaderScreen(
                chapterContent = uiState.currentChapterContent,
                initialCharOffset = current.charOffset,
                onCharOffsetChange = { offset ->
                    viewModel.updateCharOffset(offset)
                },
                onNextChapter = { viewModel.goToNextChapter() },
                onPrevChapter = { viewModel.goToPrevChapter() },
                onLongPress = { viewModel.navigateTo(Screen.Menu) },
                onBack = { viewModel.handleBack() },
                fontSize = uiState.fontSize,
                autoScrollSpeed = uiState.autoScrollSpeed,
                isAutoScrolling = uiState.isAutoScrolling,
                onAutoScrollToggle = { viewModel.setAutoScrolling(!uiState.isAutoScrolling) },
                onAutoScrollSpeedChange = { viewModel.updateAutoScrollSpeed(it) },
                appBrightness = uiState.appBrightness,
                onBrightnessChange = { viewModel.updateAppBrightness(it) }
            )

            is Screen.Menu -> MenuScreen(
                chapterTitle = uiState.currentChapterContent?.title ?: "",
                fontSize = uiState.fontSize,
                isDarkMode = uiState.isDarkMode,
                autoScrollSpeed = uiState.autoScrollSpeed,
                isAutoScrolling = uiState.isAutoScrolling,
                appBrightness = uiState.appBrightness,
                hasPrevChapter = uiState.currentChapterContent?.hasPrevChapter == true,
                hasNextChapter = uiState.currentChapterContent?.hasNextChapter == true,
                onPrevChapter = {
                    viewModel.goToPrevChapter()
                    viewModel.navigateTo(Screen.Reader(viewModel.getCurrentReadingOffset(), uiState.currentChapterIndex))
                },
                onNextChapter = {
                    viewModel.goToNextChapter()
                    viewModel.navigateTo(Screen.Reader(viewModel.getCurrentReadingOffset(), uiState.currentChapterIndex))
                },
                onFontSizeChange = { viewModel.updateFontSize(it) },
                onToggleDarkMode = { viewModel.toggleDarkMode() },
                onToggleAutoScroll = {
                    val nextScrollState = !uiState.isAutoScrolling
                    viewModel.setAutoScrolling(nextScrollState)
                    if (nextScrollState) {
                        viewModel.navigateTo(Screen.Reader(viewModel.getCurrentReadingOffset(), uiState.currentChapterIndex))
                    }
                },
                onAutoScrollSpeedChange = { viewModel.updateAutoScrollSpeed(it) },
                onBrightnessChange = { viewModel.updateAppBrightness(it) },
                onAddBookmark = {
                    viewModel.addBookmark()
                    viewModel.navigateTo(Screen.Reader(viewModel.getCurrentReadingOffset(), uiState.currentChapterIndex))
                },
                onOpenRsvp = { viewModel.openRsvp() },
                onChapterListClick = { viewModel.navigateTo(Screen.ChapterList) },
                onBack = { viewModel.navigateTo(Screen.Reader(viewModel.getCurrentReadingOffset(), uiState.currentChapterIndex)) },
                onHome = { viewModel.closeBook() }
            )

            is Screen.ChapterList -> ChapterListScreen(
                chapters = uiState.chapters,
                currentChapterIndex = uiState.currentChapterIndex,
                bookmarks = uiState.bookmarks,
                onChapterClick = { index ->
                    viewModel.goToChapter(index)
                },
                onBookmarkClick = { bookmark ->
                    viewModel.jumpToBookmark(bookmark)
                },
                onDeleteBookmark = { bookmark ->
                    viewModel.removeBookmark(bookmark.id)
                },
                onBack = { viewModel.navigateTo(Screen.Reader(viewModel.getCurrentReadingOffset(), uiState.currentChapterIndex)) }
            )

            is Screen.Rsvp -> RsvpScreen(
                chapterContent = uiState.currentChapterContent,
                initialCharOffset = viewModel.getCurrentReadingOffset(),
                onCharOffsetChange = { offset ->
                    viewModel.updateCharOffset(offset)
                },
                onNextChapter = { viewModel.goToNextChapter() },
                onBack = { viewModel.handleBack() }
            )

            is Screen.WifiTransfer -> WifiTransferScreen(
                ipAddress = uiState.wifiIpAddress,
                port = uiState.wifiPort,
                uploadedCount = uiState.wifiUploadedCount,
                isServerRunning = uiState.isWifiServerRunning,
                isTransferring = uiState.isTransferring,
                transferProgress = uiState.transferProgress,
                transferFileName = uiState.transferFileName,
                onToggleServer = {
                    if (uiState.isWifiServerRunning) viewModel.closeWifiTransfer() else viewModel.openWifiTransfer()
                },
                onBack = { viewModel.closeWifiTransfer() }
            )
        }
    }
}

// ═══════════════════════════════════════
//  书架主页 Composable（原生 ScrollView 架构 + 纯黑深色/亮色自适应）
// ═══════════════════════════════════════

/**
 * 书架主页 — 圆屏黄金安全区排版
 */
@Composable
fun BookshelfScreen(
    bookshelf: List<BookItem>,
    searchQuery: String = "",
    fontSize: Int,
    isDarkMode: Boolean,
    onOpenFile: () -> Unit,
    onOpenBook: (BookItem) -> Unit,
    onDeleteBook: (BookItem) -> Unit,
    onTogglePin: (BookItem) -> Unit = {},
    onSearchChange: (String) -> Unit = {},
    onOpenWifiTransfer: () -> Unit = {},
    onFontSizeChange: (Int) -> Unit,
    onToggleDarkMode: () -> Unit,
    errorMessage: String? = null
) {
    val colorScheme = MaterialTheme.colorScheme

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            val density = context.resources.displayMetrics.density
            val padH = (22 * density).toInt()
            val padTop = (44 * density).toInt()
            val padBottom = (64 * density).toInt()

            val scrollView = ScrollView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isFocusable = true
                isFocusableInTouchMode = true
                isVerticalScrollBarEnabled = false
                setBackgroundColor(colorScheme.background.toArgb())
                setPadding(padH, padTop, padH, padBottom)
                clipToPadding = false
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            }

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val holder = createBookshelfViewHolder(context, container)
            scrollView.tag = holder
            scrollView.addView(container)

            updateBookshelfView(
                holder = holder,
                bookshelf = bookshelf,
                searchQuery = searchQuery,
                fontSize = fontSize,
                isDarkMode = isDarkMode,
                errorMessage = errorMessage,
                colors = colorScheme,
                onOpenFile = onOpenFile,
                onOpenBook = onOpenBook,
                onDeleteBook = onDeleteBook,
                onTogglePin = onTogglePin,
                onSearchChange = onSearchChange,
                onOpenWifiTransfer = onOpenWifiTransfer,
                onFontSizeChange = onFontSizeChange,
                onToggleDarkMode = onToggleDarkMode
            )
            scrollView
        },
        update = { scrollView ->
            scrollView.setBackgroundColor(colorScheme.background.toArgb())
            val holder = scrollView.tag as? BookshelfViewHolder ?: return@AndroidView
            updateBookshelfView(
                holder = holder,
                bookshelf = bookshelf,
                searchQuery = searchQuery,
                fontSize = fontSize,
                isDarkMode = isDarkMode,
                errorMessage = errorMessage,
                colors = colorScheme,
                onOpenFile = onOpenFile,
                onOpenBook = onOpenBook,
                onDeleteBook = onDeleteBook,
                onTogglePin = onTogglePin,
                onSearchChange = onSearchChange,
                onOpenWifiTransfer = onOpenWifiTransfer,
                onFontSizeChange = onFontSizeChange,
                onToggleDarkMode = onToggleDarkMode
            )
        }
    )
}

private class BookshelfViewHolder(
    val container: LinearLayout,
    val headerLayout: LinearLayout,
    val titleTv: TextView,
    val wifiBtn: TextView,
    val themeBtn: TextView,
    val importBtn: TextView,
    val searchInput: EditText,
    val errTv: TextView,
    val cardsContainer: LinearLayout,
    val emptyLayout: LinearLayout,
    val emptyTv: TextView,
    val pickBtn: TextView,
    val fontLayout: LinearLayout,
    val fontLabel: TextView,
    val fontMinus: TextView,
    val fontSizeVal: TextView,
    val fontPlus: TextView,
    val cardHolders: MutableList<BookCardHolder> = mutableListOf()
)

private class BookCardHolder(
    val card: FrameLayout,
    val pinIndicator: TextView,
    val bookTitle: TextView,
    val formatBadge: TextView,
    val bookSub: TextView,
    val pinBtn: TextView,
    val delBtn: TextView
)

/**
 * 首次初始化构建骨架 View
 */
private fun createBookshelfViewHolder(context: Context, container: LinearLayout): BookshelfViewHolder {
    val density = context.resources.displayMetrics.density

    // 1. 顶部栏
    val headerLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, (8 * density).toInt())
        }
        gravity = Gravity.CENTER_VERTICAL
    }

    val titleTv = TextView(context).apply {
        text = "书架"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
        typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    headerLayout.addView(titleTv)

    val wifiBtn = TextView(context).apply {
        text = "📶传书"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding((7 * density).toInt(), (4.5f * density).toInt(), (7 * density).toInt(), (4.5f * density).toInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, (4 * density).toInt(), 0)
        }
    }
    headerLayout.addView(wifiBtn)

    val themeBtn = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding((7 * density).toInt(), (4.5f * density).toInt(), (7 * density).toInt(), (4.5f * density).toInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, (4 * density).toInt(), 0)
        }
    }
    headerLayout.addView(themeBtn)

    val importBtn = TextView(context).apply {
        text = "+ 导入"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding((8 * density).toInt(), (4.5f * density).toInt(), (8 * density).toInt(), (4.5f * density).toInt())
    }
    headerLayout.addView(importBtn)
    container.addView(headerLayout)

    // 2. 搜索框
    val searchInput = EditText(context).apply {
        hint = "🔍 搜索小说书名…"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        maxLines = 1
        setSingleLine(true)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, (8 * density).toInt())
        }
        setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
    }
    container.addView(searchInput)

    // 3. 错误提示
    val errTv = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, (6 * density).toInt())
        visibility = View.GONE
    }
    container.addView(errTv)

    // 4. 卡片容器
    val cardsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
    container.addView(cardsContainer)

    // 5. 空书架布局
    val emptyLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, (14 * density).toInt(), 0, (14 * density).toInt())
        }
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
    }

    val emptyTv = TextView(context).apply {
        text = "书架暂无书籍"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, (10 * density).toInt())
    }
    emptyLayout.addView(emptyTv)

    val pickBtn = TextView(context).apply {
        text = "选择本地小说 (TXT / EPUB)"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
    }
    emptyLayout.addView(pickBtn)
    container.addView(emptyLayout)

    // 6. 字号调节底栏
    val fontLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, (12 * density).toInt(), 0, (20 * density).toInt())
        }
        gravity = Gravity.CENTER_VERTICAL
    }

    val fontLabel = TextView(context).apply {
        text = "阅读字号"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    fontLayout.addView(fontLabel)

    val fontMinus = TextView(context).apply {
        text = "－"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
    }
    fontLayout.addView(fontMinus)

    val fontSizeVal = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
    }
    fontLayout.addView(fontSizeVal)

    val fontPlus = TextView(context).apply {
        text = "＋"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
    }
    fontLayout.addView(fontPlus)
    container.addView(fontLayout)

    return BookshelfViewHolder(
        container = container,
        headerLayout = headerLayout,
        titleTv = titleTv,
        wifiBtn = wifiBtn,
        themeBtn = themeBtn,
        importBtn = importBtn,
        searchInput = searchInput,
        errTv = errTv,
        cardsContainer = cardsContainer,
        emptyLayout = emptyLayout,
        emptyTv = emptyTv,
        pickBtn = pickBtn,
        fontLayout = fontLayout,
        fontLabel = fontLabel,
        fontMinus = fontMinus,
        fontSizeVal = fontSizeVal,
        fontPlus = fontPlus
    )
}

/**
 * 刷新书架主页数据与动态样式
 */
private fun updateBookshelfView(
    holder: BookshelfViewHolder,
    bookshelf: List<BookItem>,
    searchQuery: String,
    fontSize: Int,
    isDarkMode: Boolean,
    errorMessage: String?,
    colors: ColorScheme,
    onOpenFile: () -> Unit,
    onOpenBook: (BookItem) -> Unit,
    onDeleteBook: (BookItem) -> Unit,
    onTogglePin: (BookItem) -> Unit,
    onSearchChange: (String) -> Unit,
    onOpenWifiTransfer: () -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onToggleDarkMode: () -> Unit
) {
    val context = holder.container.context
    val density = context.resources.displayMetrics.density

    val primaryColor = colors.primary.toArgb()
    val onBgColor = colors.onBackground.toArgb()
    val secondaryColor = colors.secondary.toArgb()
    val surfaceColor = colors.surface.toArgb()
    val surfaceVariantColor = colors.surfaceVariant.toArgb()
    val onSurfaceVariantColor = colors.onSurfaceVariant.toArgb()
    val onSurfaceColor = colors.onSurface.toArgb()
    val outlineColor = colors.outline.toArgb()
    val errorColor = colors.error.toArgb()

    // 1. 顶部栏更新
    holder.titleTv.setTextColor(primaryColor)

    holder.wifiBtn.apply {
        setTextColor(primaryColor)
        background = GradientDrawable().apply {
            setColor(surfaceVariantColor)
            cornerRadius = 12 * density
        }
        setOnClickListener { onOpenWifiTransfer() }
    }

    holder.themeBtn.apply {
        text = if (isDarkMode) "🌙 深色" else "☀️ 浅色"
        setTextColor(onSurfaceVariantColor)
        background = GradientDrawable().apply {
            setColor(surfaceVariantColor)
            cornerRadius = 12 * density
        }
        setOnClickListener { onToggleDarkMode() }
    }

    holder.importBtn.apply {
        setTextColor(secondaryColor)
        background = GradientDrawable().apply {
            setColor(surfaceVariantColor)
            cornerRadius = 12 * density
        }
        setOnClickListener { onOpenFile() }
    }

    // 2. 搜索框样式与监听
    holder.searchInput.apply {
        setTextColor(onSurfaceColor)
        setHintTextColor(onSurfaceVariantColor.let { Color(it).copy(alpha = 0.6f).toArgb() })
        background = GradientDrawable().apply {
            setColor(surfaceVariantColor)
            cornerRadius = 10 * density
        }
        if (text.toString() != searchQuery) {
            setText(searchQuery)
        }
        // 单例 TextWatcher 防抖
        val oldWatcher = tag as? TextWatcher
        if (oldWatcher != null) removeTextChangedListener(oldWatcher)
        val newWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                onSearchChange(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        addTextChangedListener(newWatcher)
        tag = newWatcher
    }

    // 3. 错误信息展示
    if (errorMessage != null) {
        holder.errTv.text = errorMessage
        holder.errTv.setTextColor(errorColor)
        holder.errTv.visibility = View.VISIBLE
    } else {
        holder.errTv.visibility = View.GONE
    }

    // 根据搜索过滤书架列表
    val filteredBooks = if (searchQuery.isNotEmpty()) {
        bookshelf.filter { it.title.contains(searchQuery, ignoreCase = true) }
    } else {
        bookshelf
    }

    // 4. 书架卡片复用与绑定
    if (filteredBooks.isNotEmpty()) {
        holder.emptyLayout.visibility = View.GONE
        holder.cardsContainer.visibility = View.VISIBLE

        for (i in filteredBooks.indices) {
            val book = filteredBooks[i]
            val cardHolder: BookCardHolder
            if (i < holder.cardHolders.size) {
                cardHolder = holder.cardHolders[i]
                cardHolder.card.visibility = View.VISIBLE
            } else {
                cardHolder = createBookCardHolder(context, density)
                holder.cardHolders.add(cardHolder)
                holder.cardsContainer.addView(cardHolder.card)
            }

            // 就地更新卡片数据与外观
            cardHolder.card.apply {
                background = GradientDrawable().apply {
                    setColor(surfaceColor)
                    cornerRadius = 14 * density
                    if (book.isPinned) {
                        setStroke((1.2f * density).toInt(), primaryColor)
                    }
                }
                setOnClickListener { onOpenBook(book) }
            }

            cardHolder.pinIndicator.apply {
                visibility = if (book.isPinned) View.VISIBLE else View.GONE
                setTextColor(primaryColor)
            }

            val isEpub = book.uriString.endsWith(".epub", ignoreCase = true) || book.title.endsWith(".epub", ignoreCase = true)
            val displayTitle = EpubParser.cleanBookTitle(book.title)

            cardHolder.bookTitle.apply {
                text = displayTitle
                setTextColor(onBgColor)
            }

            cardHolder.formatBadge.apply {
                text = if (isEpub) "EPUB" else "TXT"
                setTextColor(if (isEpub) primaryColor else secondaryColor)
                background = GradientDrawable().apply {
                    setColor(surfaceVariantColor)
                    cornerRadius = 4 * density
                }
            }

            val progressText = if (book.progressPercent > 0) "已读 ${book.progressPercent}%" else "未读"
            val chapterSub = if (book.lastChapterTitle.isNotEmpty()) " · ${book.lastChapterTitle}" else ""
            cardHolder.bookSub.apply {
                text = "$progressText$chapterSub"
                setTextColor(onSurfaceVariantColor)
            }

            cardHolder.pinBtn.apply {
                text = if (book.isPinned) "取消置顶" else "置顶"
                setTextColor(if (book.isPinned) primaryColor else outlineColor)
                setOnClickListener { onTogglePin(book) }
            }

            cardHolder.delBtn.apply {
                setTextColor(outlineColor)
                setOnClickListener { onDeleteBook(book) }
            }
        }

        // 隐藏多余卡片
        for (i in filteredBooks.size until holder.cardHolders.size) {
            holder.cardHolders[i].card.visibility = View.GONE
        }
    } else {
        holder.cardsContainer.visibility = View.GONE
        holder.emptyLayout.visibility = View.VISIBLE
        holder.emptyTv.apply {
            text = if (searchQuery.isNotEmpty()) "未找到匹配的小说" else "书架暂无书籍"
            setTextColor(onSurfaceVariantColor)
        }
        holder.pickBtn.apply {
            setTextColor(primaryColor)
            background = GradientDrawable().apply {
                setColor(surfaceVariantColor)
                cornerRadius = 16 * density
            }
            setOnClickListener { onOpenFile() }
        }
    }

    // 5. 字号底栏更新
    holder.fontLabel.setTextColor(onSurfaceVariantColor)
    holder.fontMinus.apply {
        setTextColor(onSurfaceVariantColor)
        setOnClickListener { onFontSizeChange(fontSize - 1) }
    }
    holder.fontSizeVal.apply {
        text = "$fontSize"
        setTextColor(onSurfaceColor)
    }
    holder.fontPlus.apply {
        setTextColor(secondaryColor)
        setOnClickListener { onFontSizeChange(fontSize + 1) }
    }
}

private fun createBookCardHolder(context: Context, density: Float): BookCardHolder {
    val card = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, (8 * density).toInt())
        }
        setPadding((10 * density).toInt(), (9 * density).toInt(), (6 * density).toInt(), (9 * density).toInt())
        isClickable = true
    }

    val cardContent = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        gravity = Gravity.CENTER_VERTICAL
    }

    val infoLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }

    val titleRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        gravity = Gravity.CENTER_VERTICAL
    }

    val pinIndicator = TextView(context).apply {
        text = "📌"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        setPadding(0, 0, (3 * density).toInt(), 0)
        visibility = View.GONE
    }
    titleRow.addView(pinIndicator)

    val bookTitle = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    titleRow.addView(bookTitle)

    val formatBadge = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 8.5f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding((4 * density).toInt(), (1.5f * density).toInt(), (4 * density).toInt(), (1.5f * density).toInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins((4 * density).toInt(), 0, (4 * density).toInt(), 0)
        }
    }
    titleRow.addView(formatBadge)
    infoLayout.addView(titleRow)

    val bookSub = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(0, (2 * density).toInt(), 0, 0)
    }
    infoLayout.addView(bookSub)
    cardContent.addView(infoLayout)

    val pinBtn = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 9f)
        setPadding((4 * density).toInt(), (5 * density).toInt(), (4 * density).toInt(), (5 * density).toInt())
    }
    cardContent.addView(pinBtn)

    val delBtn = TextView(context).apply {
        text = "✕"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding((6 * density).toInt(), (5 * density).toInt(), (6 * density).toInt(), (5 * density).toInt())
    }
    cardContent.addView(delBtn)

    card.addView(cardContent)
    return BookCardHolder(card, pinIndicator, bookTitle, formatBadge, bookSub, pinBtn, delBtn)
}

/**
 * 加载中界面
 */
@Composable
fun LoadingScreen() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "加载中…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
