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

            val scrollView = object : ScrollView(context) {
                override fun fling(velocityY: Int) {
                    super.fling((velocityY * 1.35f).toInt())
                }
            }.apply {
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

            // 支持表冠在书架界面的灵敏滚动与振感
            scrollView.setOnGenericMotionListener { v, event ->
                if (CrownScrollHelper.isCrownScrollEvent(event)) {
                    val delta = CrownScrollHelper.extractCrownDelta(event)
                    if (kotlin.math.abs(delta) > 0.001f) {
                        val stepPixels = (delta * 60 * density).toInt()
                        scrollView.smoothScrollBy(0, stepPixels)
                        RotaryHapticManager.performScrollTick(context, v)
                        return@setOnGenericMotionListener true
                    }
                }
                false
            }

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            populateBookshelf(context, container, bookshelf, fontSize, isDarkMode, errorMessage, colorScheme, onOpenFile, onOpenBook, onDeleteBook, onFontSizeChange, onToggleDarkMode)
            scrollView.addView(container)

            scrollView.post {
                scrollView.requestFocus()
            }

            scrollView
        },
        update = { scrollView ->
            scrollView.setBackgroundColor(colorScheme.background.toArgb())
            val container = scrollView.getChildAt(0) as? LinearLayout ?: return@AndroidView
            populateBookshelf(scrollView.context, container, bookshelf, fontSize, isDarkMode, errorMessage, colorScheme, onOpenFile, onOpenBook, onDeleteBook, onFontSizeChange, onToggleDarkMode)
        }
    )
}

/**
 * 构建书架视图内容
 */
private fun populateBookshelf(
    context: Context,
    container: LinearLayout,
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
    val density = context.resources.displayMetrics.density
    container.removeAllViews()

    val primaryColor = colors.primary.toArgb()
    val onBgColor = colors.onBackground.toArgb()
    val secondaryColor = colors.secondary.toArgb()
    val surfaceColor = colors.surface.toArgb()
    val surfaceVariantColor = colors.surfaceVariant.toArgb()
    val onSurfaceVariantColor = colors.onSurfaceVariant.toArgb()
    val onSurfaceColor = colors.onSurface.toArgb()
    val outlineColor = colors.outline.toArgb()
    val errorColor = colors.error.toArgb()

    // 1. 顶部标题与功能按钮栏
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
        setTextColor(primaryColor)
        typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    headerLayout.addView(titleTv)

    // 深色模式切换按钮
    val themeBtn = TextView(context).apply {
        text = if (isDarkMode) "🌙 深色" else "☀️ 浅色"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(onSurfaceVariantColor)
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply {
            setColor(surfaceVariantColor)
            cornerRadius = 13 * density
        }
        setPadding((8 * density).toInt(), (5 * density).toInt(), (8 * density).toInt(), (5 * density).toInt())
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, (6 * density).toInt(), 0)
        }
        setOnClickListener { onToggleDarkMode() }
    }
    headerLayout.addView(themeBtn)

    // 导入按钮
    val importBtn = TextView(context).apply {
        text = "+ 导入"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        setTextColor(secondaryColor)
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply {
            setColor(surfaceVariantColor)
            cornerRadius = 13 * density
        }
        setPadding((10 * density).toInt(), (5 * density).toInt(), (10 * density).toInt(), (5 * density).toInt())
        setOnClickListener { onOpenFile() }
    }
    headerLayout.addView(importBtn)
    container.addView(headerLayout)

    // 2. 错误信息展示
    if (errorMessage != null) {
        val errTv = TextView(context).apply {
            text = errorMessage
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(errorColor)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        container.addView(errTv)
    }

    // 3. 书籍列表
    if (bookshelf.isNotEmpty()) {
        for (book in bookshelf) {
            val card = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, 0, (8 * density).toInt())
                }
                background = GradientDrawable().apply {
                    setColor(surfaceColor)
                    cornerRadius = 14 * density
                }
                setPadding((12 * density).toInt(), (10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt())
                isClickable = true
                setOnClickListener { onOpenBook(book) }
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
                text = book.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(onBgColor)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            infoLayout.addView(bookTitle)

            val progressText = if (book.progressPercent > 0) "已读 ${book.progressPercent}%" else "未读"
            val chapterSub = if (book.lastChapterTitle.isNotEmpty()) " · ${book.lastChapterTitle}" else ""
            val bookSub = TextView(context).apply {
                text = "$progressText$chapterSub"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(onSurfaceVariantColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, (2 * density).toInt(), 0, 0)
            }
            infoLayout.addView(bookSub)
            cardContent.addView(infoLayout)

            // 删除按钮
            val delBtn = TextView(context).apply {
                text = "✕"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(outlineColor)
                setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
                setOnClickListener { onDeleteBook(book) }
            }
            cardContent.addView(delBtn)

            card.addView(cardContent)
            container.addView(card)
        }
    } else {
        // 空书架提示
        val emptyLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, (16 * density).toInt(), 0, (16 * density).toInt())
            }
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val emptyTv = TextView(context).apply {
            text = "书架空空如也"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(onSurfaceVariantColor)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (10 * density).toInt())
        }
        emptyLayout.addView(emptyTv)

        val pickBtn = TextView(context).apply {
            text = "选择本地 TXT 小说"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(primaryColor)
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(surfaceVariantColor)
                cornerRadius = 16 * density
            }
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            setOnClickListener { onOpenFile() }
        }
        emptyLayout.addView(pickBtn)
        container.addView(emptyLayout)
    }

    // 4. 字号调节底栏
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
        setTextColor(onSurfaceVariantColor)
    }
    fontLayout.addView(fontLabel)

    val fontMinus = TextView(context).apply {
        text = "  A-  "
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(onSurfaceVariantColor)
        setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
        setOnClickListener { onFontSizeChange(fontSize - 1) }
    }
    fontLayout.addView(fontMinus)

    val fontSizeVal = TextView(context).apply {
        text = "$fontSize"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(onSurfaceColor)
        setPadding((4 * density).toInt(), 0, (4 * density).toInt(), 0)
    }
    fontLayout.addView(fontSizeVal)

    val fontPlus = TextView(context).apply {
        text = "  A+  "
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(secondaryColor)
        setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
        setOnClickListener { onFontSizeChange(fontSize + 1) }
    }
    fontLayout.addView(fontPlus)

    container.addView(fontLayout)
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


