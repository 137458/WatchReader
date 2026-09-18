package com.watchreader

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 菜单视图节点持有器（永久节点复用池，零 View 分配与销毁）
 */
private class MenuViewHolder(
    val scrollView: ScrollView,
    val container: LinearLayout,
    val menuTitleTv: TextView,
    val prevBtn: FrameLayout,
    val prevTv: TextView,
    val nextBtn: FrameLayout,
    val nextTv: TextView,
    val autoScrollCard: LinearLayout,
    val autoScrollToggleBtn: FrameLayout,
    val autoScrollToggleTv: TextView,
    val speedMinusBtn: TextView,
    val speedValTv: TextView,
    val speedPlusBtn: TextView,
    val brightnessCard: LinearLayout,
    val brightnessTitleTv: TextView,
    val brightnessMinusBtn: TextView,
    val brightnessPlusBtn: TextView,
    val brightnessSystemBtn: TextView,
    val brightnessL1Btn: TextView,
    val brightnessL2Btn: TextView,
    val brightnessL3Btn: TextView,
    val brightnessDarkBtn: TextView,
    val fontCard: LinearLayout,
    val fontMinusBtn: TextView,
    val fontValTv: TextView,
    val fontPlusBtn: TextView,
    val themeModeCard: FrameLayout,
    val themeModeTv: TextView,
    val tapPageAreaCard: FrameLayout,
    val tapPageAreaTv: TextView,
    val cleanTypographyCard: FrameLayout,
    val cleanTypographyTv: TextView,
    val fontTypeCard: FrameLayout,
    val fontTypeTv: TextView,
    val bookmarkCard: FrameLayout,
    val bookmarkTv: TextView,
    val rsvpCard: FrameLayout,
    val rsvpTv: TextView,
    val chapterListCard: FrameLayout,
    val chapterListTv: TextView,
    val bookshelfCard: FrameLayout,
    val bookshelfTv: TextView,
    val readDurationCard: FrameLayout,
    val readDurationTv: TextView,
    val backReaderCard: FrameLayout,
    val backReaderTv: TextView
)

/**
 * 菜单页 — 专为 466x466 圆屏深度优化的优雅卡片排版 + 原生极速 ScrollView 架构
 */
@Composable
fun MenuScreen(
    chapterTitle: String = "",
    fontSize: Int,
    isDarkMode: Boolean,
    autoScrollSpeed: Float,
    isAutoScrolling: Boolean,
    appBrightness: Float,
    hasPrevChapter: Boolean,
    hasNextChapter: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onToggleDarkMode: () -> Unit,
    onToggleAutoScroll: () -> Unit,
    onAutoScrollSpeedChange: (Float) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onAddBookmark: () -> Unit,
    onOpenRsvp: () -> Unit,
    onChapterListClick: () -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    themeMode: Int = 0,
    onThemeModeChange: (Int) -> Unit = {},
    tapPageArea: Int = 0,
    onTapPageAreaChange: (Int) -> Unit = {},
    cleanTypography: Boolean = true,
    onCleanTypographyChange: (Boolean) -> Unit = {},
    fontType: Int = 0,
    onFontTypeChange: (Int) -> Unit = {},
    readDurationSec: Long = 0L
) {
    BackHandler(onBack = onBack)

    val colorScheme = MaterialTheme.colorScheme
    val bgColor = colorScheme.background.toArgb()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        // 原生极速 ScrollView 渲染核心（对齐 ReaderScreen）
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val density = ctx.resources.displayMetrics.density
                val padH = (22 * density).toInt()     // 466x466 圆屏黄金安全区
                val padTop = (44 * density).toInt()   // 避让顶部弧形标题
                val padBottom = (52 * density).toInt()// 避让底部弧边

                val scrollView = ScrollView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    isFocusable = true
                    isFocusableInTouchMode = true
                    isVerticalScrollBarEnabled = false
                    overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                    setBackgroundColor(bgColor)
                    setPadding(padH, padTop, padH, padBottom)
                    clipToPadding = false
                }

                // 原生物理表冠旋转监听（对齐 ReaderScreen：原生平滑滚屏 + 细腻微振）
                scrollView.setOnGenericMotionListener { v, event ->
                    if (CrownScrollHelper.isCrownScrollEvent(event)) {
                        val delta = CrownScrollHelper.extractCrownDelta(event)
                        CrownScrollHelper.dispatchScroll(delta, scrollView, ctx, v)
                        return@setOnGenericMotionListener true
                    }
                    false
                }

                val container = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }

                val holder = createMenuViews(ctx, container, scrollView, density)
                scrollView.addView(container)
                scrollView.tag = holder

                bindMenuData(
                    holder = holder,
                    density = density,
                    colors = colorScheme,
                    fontSize = fontSize,
                    isDarkMode = isDarkMode,
                    autoScrollSpeed = autoScrollSpeed,
                    isAutoScrolling = isAutoScrolling,
                    appBrightness = appBrightness,
                    hasPrevChapter = hasPrevChapter,
                    hasNextChapter = hasNextChapter,
                    onPrevChapter = onPrevChapter,
                    onNextChapter = onNextChapter,
                    onFontSizeChange = onFontSizeChange,
                    onToggleDarkMode = onToggleDarkMode,
                    onToggleAutoScroll = onToggleAutoScroll,
                    onAutoScrollSpeedChange = onAutoScrollSpeedChange,
                    onBrightnessChange = onBrightnessChange,
                    onAddBookmark = onAddBookmark,
                    onOpenRsvp = onOpenRsvp,
                    onChapterListClick = onChapterListClick,
                    onBack = onBack,
                    onHome = onHome,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    tapPageArea = tapPageArea,
                    onTapPageAreaChange = onTapPageAreaChange,
                    cleanTypography = cleanTypography,
                    onCleanTypographyChange = onCleanTypographyChange,
                    fontType = fontType,
                    onFontTypeChange = onFontTypeChange,
                    readDurationSec = readDurationSec
                )

                scrollView
            },
            update = { scrollView ->
                val holder = scrollView.tag as? MenuViewHolder ?: return@AndroidView
                val density = scrollView.context.resources.displayMetrics.density
                scrollView.setBackgroundColor(bgColor)

                bindMenuData(
                    holder = holder,
                    density = density,
                    colors = colorScheme,
                    fontSize = fontSize,
                    isDarkMode = isDarkMode,
                    autoScrollSpeed = autoScrollSpeed,
                    isAutoScrolling = isAutoScrolling,
                    appBrightness = appBrightness,
                    hasPrevChapter = hasPrevChapter,
                    hasNextChapter = hasNextChapter,
                    onPrevChapter = onPrevChapter,
                    onNextChapter = onNextChapter,
                    onFontSizeChange = onFontSizeChange,
                    onToggleDarkMode = onToggleDarkMode,
                    onToggleAutoScroll = onToggleAutoScroll,
                    onAutoScrollSpeedChange = onAutoScrollSpeedChange,
                    onBrightnessChange = onBrightnessChange,
                    onAddBookmark = onAddBookmark,
                    onOpenRsvp = onOpenRsvp,
                    onChapterListClick = onChapterListClick,
                    onBack = onBack,
                    onHome = onHome,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    tapPageArea = tapPageArea,
                    onTapPageAreaChange = onTapPageAreaChange,
                    cleanTypography = cleanTypography,
                    onCleanTypographyChange = onCleanTypographyChange,
                    fontType = fontType,
                    onFontTypeChange = onFontTypeChange,
                    readDurationSec = readDurationSec
                )
            }
        )

        // 顶部平滑渐变羽化遮罩
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(
                    Brush.verticalGradient(
                        0f to colorScheme.background,
                        0.75f to colorScheme.background.copy(alpha = 0.85f),
                        1f to Color.Transparent
                    )
                )
                .align(Alignment.TopCenter)
        )

        // 底部平滑渐变羽化遮罩
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.8f to colorScheme.background.copy(alpha = 0.85f),
                        1f to colorScheme.background
                    )
                )
                .align(Alignment.BottomCenter)
        )

        // 顶部沿表盘外边缘弧形排布的标题
        CurvedChapterHeader(
            title = if (chapterTitle.isNotEmpty()) chapterTitle else "设置与导航",
            modifier = Modifier.align(Alignment.TopCenter)
        )

        // 9 点与 3 点方向贴边弧形排布的竖排电量与时间
        CurvedSideStatusBar(
            modifier = Modifier.fillMaxSize(),
            textColor = colorScheme.onSurfaceVariant.copy(alpha = 0.85f)
        )
    }
}

/**
 * 构建美化版静态 View 树并保存到 ViewHolder
 */
private fun createMenuViews(
    context: Context,
    container: LinearLayout,
    scrollView: ScrollView,
    density: Float
): MenuViewHolder {
    // 0. 顶部标题栏
    val menuTitleTv = TextView(context).apply {
        text = "— 设置与导航 —"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(0, (2 * density).toInt(), 0, (10 * density).toInt())
    }
    container.addView(menuTitleTv)

    // 1. 快捷翻章卡片栏（上一章 / 下一章，双药丸胶囊对称排列）
    val chapterRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, (8 * density).toInt())
        }
        gravity = Gravity.CENTER
    }

    val prevTv = TextView(context).apply {
        text = "‹ 上一章"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    val prevBtn = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, (4 * density).toInt(), 0)
        }
        setPadding((8 * density).toInt(), (9 * density).toInt(), (8 * density).toInt(), (9 * density).toInt())
        isClickable = true
        addView(prevTv)
    }
    chapterRow.addView(prevBtn)

    val nextTv = TextView(context).apply {
        text = "下一章 ›"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    val nextBtn = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins((4 * density).toInt(), 0, 0, 0)
        }
        setPadding((8 * density).toInt(), (9 * density).toInt(), (8 * density).toInt(), (9 * density).toInt())
        isClickable = true
        addView(nextTv)
    }
    chapterRow.addView(nextBtn)
    container.addView(chapterRow)

    // 2. 自动滚屏大卡片
    val autoScrollCard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, (8 * density).toInt())
        }
        setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
    }

    val autoScrollToggleTv = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    val autoScrollToggleBtn = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setPadding(0, (7 * density).toInt(), 0, (7 * density).toInt())
        isClickable = true
        addView(autoScrollToggleTv)
    }
    autoScrollCard.addView(autoScrollToggleBtn)

    val speedRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, (6 * density).toInt(), 0, 0)
        }
        gravity = Gravity.CENTER_VERTICAL
    }

    val speedMinusBtn = TextView(context).apply {
        text = " 减速- "
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
        isClickable = true
    }
    speedRow.addView(speedMinusBtn)

    val speedValTv = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    speedRow.addView(speedValTv)

    val speedPlusBtn = TextView(context).apply {
        text = " 加速+ "
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
        isClickable = true
    }
    speedRow.addView(speedPlusBtn)
    autoScrollCard.addView(speedRow)
    container.addView(autoScrollCard)

    // 3. 亮度调节大卡片
    val brightnessCard = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, (8 * density).toInt())
        }
        setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
    }

    val brightnessTopRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        gravity = Gravity.CENTER_VERTICAL
    }

    val brightnessTitleTv = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    brightnessTopRow.addView(brightnessTitleTv)

    val brightnessMinusBtn = TextView(context).apply {
        text = " 暗- "
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
        isClickable = true
    }
    brightnessTopRow.addView(brightnessMinusBtn)

    val brightnessPlusBtn = TextView(context).apply {
        text = " 亮+ "
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
        isClickable = true
    }
    brightnessTopRow.addView(brightnessPlusBtn)
    brightnessCard.addView(brightnessTopRow)

    val brightnessPresetsCol = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, (6 * density).toInt(), 0, 0)
        }
    }

    val presetRow1 = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        gravity = Gravity.CENTER
    }

    val presetRow2 = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, (4 * density).toInt(), 0, 0)
        }
        gravity = Gravity.CENTER
    }

    fun makePresetBtn(txt: String): TextView {
        return TextView(context).apply {
            text = txt
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            gravity = Gravity.CENTER
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins((2 * density).toInt(), 0, (2 * density).toInt(), 0)
            }
            setPadding((4 * density).toInt(), (7 * density).toInt(), (4 * density).toInt(), (7 * density).toInt())
            isClickable = true
        }
    }

    val brightnessSystemBtn = makePresetBtn("系统亮度")
    val brightnessDarkBtn = makePresetBtn("🌙 极暗护眼")
    val brightnessL1Btn = makePresetBtn("1档 (暗)")
    val brightnessL2Btn = makePresetBtn("2档 (中)")
    val brightnessL3Btn = makePresetBtn("3档 (亮)")

    presetRow1.addView(brightnessSystemBtn)
    presetRow1.addView(brightnessDarkBtn)
    presetRow2.addView(brightnessL1Btn)
    presetRow2.addView(brightnessL2Btn)
    presetRow2.addView(brightnessL3Btn)

    brightnessPresetsCol.addView(presetRow1)
    brightnessPresetsCol.addView(presetRow2)
    brightnessCard.addView(brightnessPresetsCol)
    container.addView(brightnessCard)

    // 4. 字号调节独立卡片
    val fontCard = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, (8 * density).toInt())
        }
        gravity = Gravity.CENTER_VERTICAL
        setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
    }

    val fontMinusBtn = TextView(context).apply {
        text = "  A -  "
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
        isClickable = true
    }
    fontCard.addView(fontMinusBtn)

    val fontValTv = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
    }
    fontCard.addView(fontValTv)

    val fontPlusBtn = TextView(context).apply {
        text = "  A +  "
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
        isClickable = true
    }
    fontCard.addView(fontPlusBtn)
    container.addView(fontCard)

    // 5. 视觉主题切换卡片 (羊皮纸 / 极光黑 / 纯黑红光夜视)
    val themeModeTv = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    val themeModeCard = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, (8 * density).toInt())
        }
        setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
        isClickable = true
        addView(themeModeTv)
    }
    container.addView(themeModeCard)

    // 6. 点按翻页热区设置 (上下 / 左右 / 关闭)
    val tapPageAreaTv = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    val tapPageAreaCard = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, (8 * density).toInt())
        }
        setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
        isClickable = true
        addView(tapPageAreaTv)
    }
    container.addView(tapPageAreaCard)

    // 7. 网文排版智能净化
    val cleanTypographyTv = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    val cleanTypographyCard = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, (8 * density).toInt())
        }
        setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
        isClickable = true
        addView(cleanTypographyTv)
    }
    container.addView(cleanTypographyCard)

    // 8. 字体风格切换 (系统黑体 / 系统衬线体)
    val fontTypeTv = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    val fontTypeCard = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, (8 * density).toInt())
        }
        setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
        isClickable = true
        addView(fontTypeTv)
    }
    container.addView(fontTypeCard)

    // 9. 书签与闪读快捷卡片（双药丸胶囊）
    val toolRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, (8 * density).toInt())
        }
        gravity = Gravity.CENTER
    }

    val bookmarkTv = TextView(context).apply {
        text = "🔖 存为书签"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    val bookmarkCard = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, (4 * density).toInt(), 0)
        }
        setPadding((6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt())
        isClickable = true
        addView(bookmarkTv)
    }
    toolRow.addView(bookmarkCard)

    val rsvpTv = TextView(context).apply {
        text = "⚡ 闪读速读"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    val rsvpCard = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins((4 * density).toInt(), 0, 0, 0)
        }
        setPadding((6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt())
        isClickable = true
        addView(rsvpTv)
    }
    toolRow.addView(rsvpCard)
    container.addView(toolRow)

    // 10. 导航双卡片（章节目录 / 返回书架）
    val navRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, (8 * density).toInt())
        }
        gravity = Gravity.CENTER
    }

    val chapterListTv = TextView(context).apply {
        text = "📖 章节目录"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    val chapterListCard = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins(0, 0, (4 * density).toInt(), 0)
        }
        setPadding((6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt())
        isClickable = true
        addView(chapterListTv)
    }
    navRow.addView(chapterListCard)

    val bookshelfTv = TextView(context).apply {
        text = "📚 返回书架"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    val bookshelfCard = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            setMargins((4 * density).toInt(), 0, 0, 0)
        }
        setPadding((6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt())
        isClickable = true
        addView(bookshelfTv)
    }
    navRow.addView(bookshelfCard)
    container.addView(navRow)

    // 11. 累计阅读统计轻量卡片
    val readDurationTv = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    val readDurationCard = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, (8 * density).toInt())
        }
        setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
        addView(readDurationTv)
    }
    container.addView(readDurationCard)

    // 12. 返回阅读高亮大卡片
    val backReaderTv = TextView(context).apply {
        text = "‹ 返回继续阅读"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    val backReaderCard = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, 0, 0, (18 * density).toInt())
        }
        setPadding((10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt(), (10 * density).toInt())
        isClickable = true
        addView(backReaderTv)
    }
    container.addView(backReaderCard)

    return MenuViewHolder(
        scrollView = scrollView,
        container = container,
        menuTitleTv = menuTitleTv,
        prevBtn = prevBtn,
        prevTv = prevTv,
        nextBtn = nextBtn,
        nextTv = nextTv,
        autoScrollCard = autoScrollCard,
        autoScrollToggleBtn = autoScrollToggleBtn,
        autoScrollToggleTv = autoScrollToggleTv,
        speedMinusBtn = speedMinusBtn,
        speedValTv = speedValTv,
        speedPlusBtn = speedPlusBtn,
        brightnessCard = brightnessCard,
        brightnessTitleTv = brightnessTitleTv,
        brightnessMinusBtn = brightnessMinusBtn,
        brightnessPlusBtn = brightnessPlusBtn,
        brightnessSystemBtn = brightnessSystemBtn,
        brightnessL1Btn = brightnessL1Btn,
        brightnessL2Btn = brightnessL2Btn,
        brightnessL3Btn = brightnessL3Btn,
        brightnessDarkBtn = brightnessDarkBtn,
        fontCard = fontCard,
        fontMinusBtn = fontMinusBtn,
        fontValTv = fontValTv,
        fontPlusBtn = fontPlusBtn,
        themeModeCard = themeModeCard,
        themeModeTv = themeModeTv,
        tapPageAreaCard = tapPageAreaCard,
        tapPageAreaTv = tapPageAreaTv,
        cleanTypographyCard = cleanTypographyCard,
        cleanTypographyTv = cleanTypographyTv,
        fontTypeCard = fontTypeCard,
        fontTypeTv = fontTypeTv,
        bookmarkCard = bookmarkCard,
        bookmarkTv = bookmarkTv,
        rsvpCard = rsvpCard,
        rsvpTv = rsvpTv,
        chapterListCard = chapterListCard,
        chapterListTv = chapterListTv,
        bookshelfCard = bookshelfCard,
        bookshelfTv = bookshelfTv,
        readDurationCard = readDurationCard,
        readDurationTv = readDurationTv,
        backReaderCard = backReaderCard,
        backReaderTv = backReaderTv
    )
}

/**
 * 属性就地绑定（0 View 重构，滚动条 0 跳变）
 */
@Suppress("UNUSED_PARAMETER")
private fun bindMenuData(
    holder: MenuViewHolder,
    density: Float,
    colors: ColorScheme,
    fontSize: Int,
    isDarkMode: Boolean,
    autoScrollSpeed: Float,
    isAutoScrolling: Boolean,
    appBrightness: Float,
    hasPrevChapter: Boolean,
    hasNextChapter: Boolean,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onToggleDarkMode: () -> Unit,
    onToggleAutoScroll: () -> Unit,
    onAutoScrollSpeedChange: (Float) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onAddBookmark: () -> Unit,
    onOpenRsvp: () -> Unit,
    onChapterListClick: () -> Unit,
    onBack: () -> Unit,
    onHome: () -> Unit,
    themeMode: Int,
    onThemeModeChange: (Int) -> Unit,
    tapPageArea: Int,
    onTapPageAreaChange: (Int) -> Unit,
    cleanTypography: Boolean,
    onCleanTypographyChange: (Boolean) -> Unit,
    fontType: Int,
    onFontTypeChange: (Int) -> Unit,
    readDurationSec: Long
) {
    val primaryColor = colors.primary.toArgb()
    val onBgColor = colors.onBackground.toArgb()
    val secondaryColor = colors.secondary.toArgb()
    val surfaceColor = colors.surface.toArgb()
    val surfaceVariantColor = colors.surfaceVariant.toArgb()
    val onSurfaceVariantColor = colors.onSurfaceVariant.toArgb()
    val onSurfaceColor = colors.onSurface.toArgb()
    val outlineColor = colors.outline.toArgb()

    // 0. 顶部提示
    holder.menuTitleTv.setTextColor(primaryColor)

    // 1. 上一章 / 下一章
    holder.prevBtn.visibility = if (hasPrevChapter) View.VISIBLE else View.INVISIBLE
    holder.prevBtn.background = GradientDrawable().apply {
        setColor(surfaceVariantColor)
        cornerRadius = 16 * density
    }
    holder.prevTv.setTextColor(onSurfaceVariantColor)
    holder.prevBtn.setOnClickListener { onPrevChapter() }

    holder.nextBtn.visibility = if (hasNextChapter) View.VISIBLE else View.INVISIBLE
    holder.nextBtn.background = GradientDrawable().apply {
        setColor(surfaceVariantColor)
        cornerRadius = 16 * density
    }
    holder.nextTv.setTextColor(primaryColor)
    holder.nextBtn.setOnClickListener { onNextChapter() }

    // 2. 自动滚屏大卡片
    holder.autoScrollCard.background = GradientDrawable().apply {
        setColor(surfaceColor)
        cornerRadius = 16 * density
    }
    holder.autoScrollToggleBtn.background = GradientDrawable().apply {
        setColor(if (isAutoScrolling) primaryColor else surfaceVariantColor)
        cornerRadius = 12 * density
    }
    holder.autoScrollToggleTv.text = if (isAutoScrolling) "⏸ 暂停自动滚屏" else "▶ 开启自动滚屏"
    holder.autoScrollToggleTv.setTextColor(if (isAutoScrolling) colors.background.toArgb() else primaryColor)
    holder.autoScrollToggleBtn.setOnClickListener { onToggleAutoScroll() }

    holder.speedValTv.text = "${autoScrollSpeed.toInt()} px/s"
    holder.speedValTv.setTextColor(primaryColor)

    holder.speedMinusBtn.setTextColor(onSurfaceVariantColor)
    holder.speedMinusBtn.background = GradientDrawable().apply {
        setColor(surfaceVariantColor)
        cornerRadius = 8 * density
    }
    holder.speedMinusBtn.setOnClickListener { onAutoScrollSpeedChange((autoScrollSpeed - 10f).coerceAtLeast(15f)) }

    holder.speedPlusBtn.setTextColor(secondaryColor)
    holder.speedPlusBtn.background = GradientDrawable().apply {
        setColor(surfaceVariantColor)
        cornerRadius = 8 * density
    }
    holder.speedPlusBtn.setOnClickListener { onAutoScrollSpeedChange((autoScrollSpeed + 10f).coerceAtMost(200f)) }

    // 3. 亮度调节大卡片
    holder.brightnessCard.background = GradientDrawable().apply {
        setColor(surfaceColor)
        cornerRadius = 16 * density
    }
    holder.brightnessTitleTv.text = "☀️ 亮度: ${BrightnessManager.formatBrightnessText(appBrightness)}"
    holder.brightnessTitleTv.setTextColor(primaryColor)

    holder.brightnessMinusBtn.setTextColor(onSurfaceVariantColor)
    holder.brightnessMinusBtn.background = GradientDrawable().apply {
        setColor(surfaceVariantColor)
        cornerRadius = 8 * density
    }
    holder.brightnessMinusBtn.setOnClickListener {
        val cur = if (appBrightness < 0f) 0.35f else appBrightness
        onBrightnessChange((cur - 0.05f).coerceIn(0.01f, 1.0f))
    }

    holder.brightnessPlusBtn.setTextColor(secondaryColor)
    holder.brightnessPlusBtn.background = GradientDrawable().apply {
        setColor(surfaceVariantColor)
        cornerRadius = 8 * density
    }
    holder.brightnessPlusBtn.setOnClickListener {
        val cur = if (appBrightness < 0f) 0.35f else appBrightness
        onBrightnessChange((cur + 0.05f).coerceIn(0.01f, 1.0f))
    }

    fun applyPresetStyle(tv: TextView, isSelected: Boolean, targetBrightness: Float) {
        tv.setTextColor(if (isSelected) primaryColor else onSurfaceVariantColor)
        tv.typeface = if (isSelected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        tv.background = GradientDrawable().apply {
            setColor(if (isSelected) surfaceVariantColor else 0x00000000)
            cornerRadius = 8 * density
        }
        tv.setOnClickListener { onBrightnessChange(targetBrightness) }
    }

    applyPresetStyle(holder.brightnessSystemBtn, appBrightness < 0f, -1.0f)
    applyPresetStyle(holder.brightnessL1Btn, appBrightness in 0.30f..0.49f, 0.35f)
    applyPresetStyle(holder.brightnessL2Btn, appBrightness in 0.50f..0.84f, 0.65f)
    applyPresetStyle(holder.brightnessL3Btn, appBrightness >= 0.85f, 1.0f)
    applyPresetStyle(holder.brightnessDarkBtn, appBrightness in 0.0f..0.29f, 0.10f)

    // 4. 字号调节卡片
    holder.fontCard.background = GradientDrawable().apply {
        setColor(surfaceColor)
        cornerRadius = 16 * density
    }
    holder.fontValTv.text = "字号 $fontSize"
    holder.fontValTv.setTextColor(onSurfaceColor)

    holder.fontMinusBtn.setTextColor(onSurfaceVariantColor)
    holder.fontMinusBtn.background = GradientDrawable().apply {
        setColor(surfaceVariantColor)
        cornerRadius = 8 * density
    }
    holder.fontMinusBtn.setOnClickListener { onFontSizeChange(fontSize - 1) }

    holder.fontPlusBtn.setTextColor(secondaryColor)
    holder.fontPlusBtn.background = GradientDrawable().apply {
        setColor(surfaceVariantColor)
        cornerRadius = 8 * density
    }
    holder.fontPlusBtn.setOnClickListener { onFontSizeChange(fontSize + 1) }

    // 5. 视觉主题卡片
    holder.themeModeCard.background = GradientDrawable().apply {
        setColor(surfaceColor)
        cornerRadius = 16 * density
    }
    holder.themeModeTv.text = when (themeMode) {
        0 -> "🎨 主题: 暖色羊皮纸"
        1 -> "🎨 主题: 极光黑 (AMOLED)"
        2 -> "🎨 主题: 纯黑红光夜视"
        else -> "🎨 主题设置"
    }
    holder.themeModeTv.setTextColor(secondaryColor)
    holder.themeModeCard.setOnClickListener { onThemeModeChange((themeMode + 1) % 3) }

    // 6. 点按翻页热区卡片
    holder.tapPageAreaCard.background = GradientDrawable().apply {
        setColor(surfaceColor)
        cornerRadius = 16 * density
    }
    holder.tapPageAreaTv.text = when (tapPageArea) {
        0 -> "👆 翻页: 上下点按翻页"
        1 -> "👆 翻页: 左右点按翻页"
        else -> "👆 翻页: 关闭点按翻页"
    }
    holder.tapPageAreaTv.setTextColor(primaryColor)
    holder.tapPageAreaCard.setOnClickListener { onTapPageAreaChange((tapPageArea + 1) % 3) }

    // 7. 网文排版智能净化
    holder.cleanTypographyCard.background = GradientDrawable().apply {
        setColor(surfaceColor)
        cornerRadius = 16 * density
    }
    holder.cleanTypographyTv.text = if (cleanTypography) "🧹 排版净化: 已开启" else "🧹 排版净化: 已关闭"
    holder.cleanTypographyTv.setTextColor(if (cleanTypography) primaryColor else onSurfaceVariantColor)
    holder.cleanTypographyCard.setOnClickListener { onCleanTypographyChange(!cleanTypography) }

    // 8. 字体风格卡片
    holder.fontTypeCard.background = GradientDrawable().apply {
        setColor(surfaceColor)
        cornerRadius = 16 * density
    }
    holder.fontTypeTv.text = if (fontType == 1) "🔤 字体: 系统衬线体 (宋体)" else "🔤 字体: 系统黑体 (无衬线)"
    holder.fontTypeTv.setTextColor(secondaryColor)
    holder.fontTypeCard.setOnClickListener { onFontTypeChange(if (fontType == 1) 0 else 1) }

    // 9. 工具双卡片（存为书签 / 闪读速读）
    holder.bookmarkCard.background = GradientDrawable().apply {
        setColor(surfaceColor)
        cornerRadius = 16 * density
    }
    holder.bookmarkTv.setTextColor(primaryColor)
    holder.bookmarkCard.setOnClickListener { onAddBookmark() }

    holder.rsvpCard.background = GradientDrawable().apply {
        setColor(surfaceColor)
        cornerRadius = 16 * density
    }
    holder.rsvpTv.setTextColor(secondaryColor)
    holder.rsvpCard.setOnClickListener { onOpenRsvp() }

    // 10. 导航双卡片
    holder.chapterListCard.background = GradientDrawable().apply {
        setColor(surfaceColor)
        cornerRadius = 16 * density
    }
    holder.chapterListTv.setTextColor(onBgColor)
    holder.chapterListCard.setOnClickListener { onChapterListClick() }

    holder.bookshelfCard.background = GradientDrawable().apply {
        setColor(surfaceColor)
        cornerRadius = 16 * density
    }
    holder.bookshelfTv.setTextColor(outlineColor)
    holder.bookshelfCard.setOnClickListener { onHome() }

    // 11. 累计阅读统计轻量卡片
    holder.readDurationCard.background = GradientDrawable().apply {
        setColor(surfaceColor)
        cornerRadius = 16 * density
    }
    val hours = readDurationSec / 3600
    val mins = (readDurationSec % 3600) / 60
    holder.readDurationTv.text = "⏱️ 累计阅读: ${hours}小时 ${mins}分钟"
    holder.readDurationTv.setTextColor(onSurfaceVariantColor)

    // 12. 返回阅读高亮大卡片
    holder.backReaderCard.background = GradientDrawable().apply {
        setColor(surfaceVariantColor)
        cornerRadius = 16 * density
    }
    holder.backReaderTv.setTextColor(primaryColor)
    holder.backReaderCard.setOnClickListener { onBack() }
}
