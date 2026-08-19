package com.watchreader

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
                fontSize = uiState.fontSize,
                isDarkMode = uiState.isDarkMode,
                onOpenFile = { openFileLauncher.launch(arrayOf("text/plain")) },
                onOpenBook = { book -> viewModel.openFromShelf(book) },
                onDeleteBook = { book -> viewModel.deleteFromShelf(book) },
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
                        // 开启自动滚屏后，立即自动返回阅读界面并开始平滑滚屏！
                        viewModel.navigateTo(Screen.Reader(viewModel.getCurrentReadingOffset(), uiState.currentChapterIndex))
                    }
                },
                onAutoScrollSpeedChange = { viewModel.updateAutoScrollSpeed(it) },
                onBrightnessChange = { viewModel.updateAppBrightness(it) },
                onChapterListClick = { viewModel.navigateTo(Screen.ChapterList) },
                onBack = { viewModel.navigateTo(Screen.Reader(viewModel.getCurrentReadingOffset(), uiState.currentChapterIndex)) },
                onHome = { viewModel.closeBook() }
            )

            is Screen.ChapterList -> ChapterListScreen(
                chapters = uiState.chapters,
                currentChapterIndex = uiState.currentChapterIndex,
                onChapterClick = { index ->
                    viewModel.goToChapter(index)
                },
                onBack = { viewModel.navigateTo(Screen.Reader(viewModel.getCurrentReadingOffset(), uiState.currentChapterIndex)) }
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
    fontSize: Int,
    isDarkMode: Boolean,
    onOpenFile: () -> Unit,
    onOpenBook: (BookItem) -> Unit,
    onDeleteBook: (BookItem) -> Unit,
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
            val padTop = (38 * density).toInt()
            val padBottom = (42 * density).toInt()

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
                fontSize = fontSize,
                isDarkMode = isDarkMode,
                errorMessage = errorMessage,
                colors = colorScheme,
                onOpenFile = onOpenFile,
                onOpenBook = onOpenBook,
                onDeleteBook = onDeleteBook,
                onFontSizeChange = onFontSizeChange,
                onToggleDarkMode = onToggleDarkMode
            )

            scrollView.post {
                scrollView.requestFocus()
            }

            scrollView
        },
        update = { scrollView ->
            scrollView.setBackgroundColor(colorScheme.background.toArgb())
            val holder = scrollView.tag as? BookshelfViewHolder ?: return@AndroidView
            updateBookshelfView(
                holder = holder,
                bookshelf = bookshelf,
                fontSize = fontSize,
                isDarkMode = isDarkMode,
                errorMessage = errorMessage,
                colors = colorScheme,
                onOpenFile = onOpenFile,
                onOpenBook = onOpenBook,
                onDeleteBook = onDeleteBook,
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
    val themeBtn: TextView,
    val importBtn: TextView,
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
    val bookTitle: TextView,
    val bookSub: TextView,
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
            setMargins((8 * density).toInt(), 0, (8 * density).toInt(), (12 * density).toInt())
        }
        gravity = Gravity.CENTER_VERTICAL
    }

    val titleTv = TextView(context).apply {
        text = "我的书架"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    headerLayout.addView(titleTv)

    val themeBtn = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding((8 * density).toInt(), (5 * density).toInt(), (8 * density).toInt(), (5 * density).toInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, (6 * density).toInt(), 0)
        }
    }
    headerLayout.addView(themeBtn)

    val importBtn = TextView(context).apply {
        text = "+ 导入"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding((10 * density).toInt(), (5 * density).toInt(), (10 * density).toInt(), (5 * density).toInt())
    }
    headerLayout.addView(importBtn)
    container.addView(headerLayout)

    // 2. 错误提示
    val errTv = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, (6 * density).toInt())
        visibility = View.GONE
    }
    container.addView(errTv)

    // 3. 卡片容器
    val cardsContainer = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }
    container.addView(cardsContainer)

    // 4. 空书架布局
    val emptyLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, (16 * density).toInt(), 0, (16 * density).toInt())
        }
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
    }

    val emptyTv = TextView(context).apply {
        text = "书架空空如也"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, (10 * density).toInt())
    }
    emptyLayout.addView(emptyTv)

    val pickBtn = TextView(context).apply {
        text = "选择本地 TXT 小说"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
    }
    emptyLayout.addView(pickBtn)
    container.addView(emptyLayout)

    // 5. 字号调节底栏
    val fontLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, (8 * density).toInt(), 0, 0)
        }
        gravity = Gravity.CENTER
    }

    val fontLabel = TextView(context).apply {
        text = "字号:"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    }
    fontLayout.addView(fontLabel)

    val fontMinus = TextView(context).apply {
        text = "  A-  "
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
    }
    fontLayout.addView(fontMinus)

    val fontSizeVal = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
    }
    fontLayout.addView(fontSizeVal)

    val fontPlus = TextView(context).apply {
        text = "  A+  "
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
    }
    fontLayout.addView(fontPlus)
    container.addView(fontLayout)

    return BookshelfViewHolder(
        container = container,
        headerLayout = headerLayout,
        titleTv = titleTv,
        themeBtn = themeBtn,
        importBtn = importBtn,
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
 * 0 销毁、0 重新分配地就地刷新书架数据与颜色
 */
private fun updateBookshelfView(
    holder: BookshelfViewHolder,
    bookshelf: List<BookItem>,
    fontSize: Int,
    isDarkMode: Boolean,
    errorMessage: String?,
    colors: ColorScheme,
    onOpenFile: () -> Unit,
    onOpenBook: (BookItem) -> Unit,
    onDeleteBook: (BookItem) -> Unit,
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
    holder.themeBtn.apply {
        text = if (isDarkMode) "🌙 深色" else "☀️ 浅色"
        setTextColor(onSurfaceVariantColor)
        background = GradientDrawable().apply {
            setColor(surfaceVariantColor)
            cornerRadius = 13 * density
        }
        setOnClickListener { onToggleDarkMode() }
    }

    holder.importBtn.apply {
        setTextColor(secondaryColor)
        background = GradientDrawable().apply {
            setColor(surfaceVariantColor)
            cornerRadius = 13 * density
        }
        setOnClickListener { onOpenFile() }
    }

    // 2. 错误信息展示
    if (errorMessage != null) {
        holder.errTv.text = errorMessage
        holder.errTv.setTextColor(errorColor)
        holder.errTv.visibility = View.VISIBLE
    } else {
        holder.errTv.visibility = View.GONE
    }

    // 3. 书架卡片复用与绑定
    if (bookshelf.isNotEmpty()) {
        holder.emptyLayout.visibility = View.GONE
        holder.cardsContainer.visibility = View.VISIBLE

        for (i in bookshelf.indices) {
            val book = bookshelf[i]
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
                }
                setOnClickListener { onOpenBook(book) }
            }

            cardHolder.bookTitle.apply {
                text = book.title
                setTextColor(onBgColor)
            }

            val progressText = if (book.progressPercent > 0) "已读 ${book.progressPercent}%" else "未读"
            val chapterSub = if (book.lastChapterTitle.isNotEmpty()) " · ${book.lastChapterTitle}" else ""
            cardHolder.bookSub.apply {
                text = "$progressText$chapterSub"
                setTextColor(onSurfaceVariantColor)
            }

            cardHolder.delBtn.apply {
                setTextColor(outlineColor)
                setOnClickListener { onDeleteBook(book) }
            }
        }

        // 隐藏多余的卡片
        for (i in bookshelf.size until holder.cardHolders.size) {
            holder.cardHolders[i].card.visibility = View.GONE
        }
    } else {
        holder.cardsContainer.visibility = View.GONE
        holder.emptyLayout.visibility = View.VISIBLE
        holder.emptyTv.setTextColor(onSurfaceVariantColor)
        holder.pickBtn.apply {
            setTextColor(primaryColor)
            background = GradientDrawable().apply {
                setColor(surfaceVariantColor)
                cornerRadius = 16 * density
            }
            setOnClickListener { onOpenFile() }
        }
    }

    // 4. 字号底栏更新
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
        setPadding((12 * density).toInt(), (10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt())
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

    val bookTitle = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.DEFAULT_BOLD
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    infoLayout.addView(bookTitle)

    val bookSub = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(0, (2 * density).toInt(), 0, 0)
    }
    infoLayout.addView(bookSub)
    cardContent.addView(infoLayout)

    val delBtn = TextView(context).apply {
        text = "✕"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
    }
    cardContent.addView(delBtn)

    card.addView(cardContent)
    return BookCardHolder(card, bookTitle, bookSub, delBtn)
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


