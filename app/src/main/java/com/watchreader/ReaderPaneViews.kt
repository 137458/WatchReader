package com.watchreader

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 固定 5 节点 View 持有器 + 自动滚屏引擎（永久复用，零 View 分配与销毁）
 *
 * 同时是 [ReaderPaneStylePort] 的 Android 适配器：下发哪些属性由 [ReaderPaneStyles] 决定，
 * 本类只负责把属性落到具体 View / Drawable 上。
 */
internal class ReaderViewHolder(
    val scrollView: ScrollView,
    val container: LinearLayout,
    val prevBtn: FrameLayout,
    val prevTv: TextView,
    val titleTv: TextView,
    val bodyTv: TextView,
    val nextBtn: FrameLayout,
    val nextTv: TextView,
    val endTv: TextView,
    val autoScrollEngine: AutoScrollEngine,
    var currentContent: ChapterContent? = null
) : ReaderPaneStylePort {

    // 卡片背景复用池：滚动期间 ReaderScreen 高频重组，update 阶段仅重着色不再新建 GradientDrawable
    var prevBackground: GradientDrawable? = null
    var nextBackground: GradientDrawable? = null

    // 已应用外观（唯一守卫状态）：chapters 为不稳定 List，阅读页无法跳过父级重组，update 每次都会执行；
    // TextView 的 setTextSize / setTextColor / typeface 各自都会经 checkForRelayout() 同步重建整章
    // StaticLayout，没有守卫会把"阅读时长 10s tick / 底部百分比整数跳变"这类低频重组转化为
    // 全 ScrollView 重排版（滚动掉帧源），也让一次字体切换重复排版三遍
    var appliedAppearance: ReaderPaneAppearance? = null

    override fun setBackgroundArgb(argb: Int) {
        scrollView.setBackgroundColor(argb)
    }

    override fun setChromeArgb(argb: Int) {
        prevBackground?.setColor(argb)
        nextBackground?.setColor(argb)
    }

    override fun setBodyColorArgb(argb: Int) {
        bodyTv.setTextColor(argb)
    }

    override fun setTitleColorArgb(argb: Int) {
        titleTv.setTextColor(argb)
        nextTv.setTextColor(argb)
    }

    override fun setMetaColorArgb(argb: Int) {
        prevTv.setTextColor(argb)
        endTv.setTextColor(argb)
    }

    override fun setTitleTextSizeSp(sp: Float) {
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    override fun setBodyTextSizeSp(sp: Float) {
        bodyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    }

    override fun setLineSpacingMultiplier(multiplier: Float) {
        bodyTv.setLineSpacing(0f, multiplier)
    }

    override fun setLetterSpacingEm(em: Float) {
        bodyTv.letterSpacing = em
    }

    override fun setBodyTypefaceToken(token: Any?) {
        // 端口以不透明句柄表达字面，避免纯计算模块依赖 android.graphics
        bodyTv.typeface = token as? Typeface ?: Typeface.SANS_SERIF
    }
}

/**
 * 构建固定 5 节点 View 树（ScrollView + 上一章卡片 + 标题 + 正文 + 下一章卡片 + 全书完）
 * 并装配自动滚屏引擎。
 *
 * 只做"一次性装配"：所有随状态变化的内容由 [bindChapterData] / [applyChapterStyles] 增量下发，
 * 因此这里不读也不写任何 Compose 状态。调用方负责把返回值挂到 [ScrollView.tag]，
 * 并把 [ReaderViewHolder.autoScrollEngine] 同步进自己的生命周期引用。
 */
internal fun createReaderViewHolder(
    ctx: Context,
    backgroundArgb: Int,
    chromeArgb: Int,
    bodyColorArgb: Int,
    titleColorArgb: Int,
    metaColorArgb: Int,
    fontSizeSp: Int,
    autoScrollSpeedPxPerSec: Float,
    onPrevChapter: () -> Unit,
    onNextChapter: () -> Unit
): ReaderViewHolder {
    val density = ctx.resources.displayMetrics.density
    val padH = (24 * density).toInt() // 466x466 圆屏安全区域边距
    val padTop = (52 * density).toInt()
    val padBottom = (56 * density).toInt()

    val scrollView = ScrollView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        isFocusable = true
        isFocusableInTouchMode = true
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        setBackgroundColor(backgroundArgb)
        setPadding(padH, padTop, padH, padBottom)
        clipToPadding = false
    }

    val container = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    // 1. 上一章按钮卡片
    var prevBackgroundRef: GradientDrawable? = null
    val prevTv = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
        setTextColor(metaColorArgb)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    val prevBtn = FrameLayout(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, (4 * density).toInt(), 0, (14 * density).toInt())
        }
        minimumHeight = (48 * density).toInt()
        background = GradientDrawable().apply {
            setColor(chromeArgb)
            cornerRadius = 12 * density
        }
        setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
        isClickable = true
        setOnClickListener { onPrevChapter() }
        addView(prevTv)
    }
    container.addView(prevBtn)
    prevBackgroundRef = prevBtn.background as GradientDrawable

    // 2. 章节标题
    val titleTv = TextView(ctx).apply {
        tag = "title"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, (fontSizeSp + 2).toFloat())
        setTextColor(titleColorArgb)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
    }
    container.addView(titleTv)

    // 3. 章节正文（单 TextLayout 硬件加速排版 + 中文两端平整对齐）
    val bodyTv = TextView(ctx).apply {
        tag = "body"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp.toFloat())
        setTextColor(bodyColorArgb)
        setLineSpacing(0f, LineSpacingMode.STANDARD.multiplier)
        setPadding(0, 0, 0, (12 * density).toInt())
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            justificationMode = android.graphics.text.LineBreaker.JUSTIFICATION_MODE_INTER_WORD
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            justificationMode = android.text.Layout.JUSTIFICATION_MODE_INTER_WORD
        }
    }
    container.addView(bodyTv)

    // 4. 下一章按钮卡片
    var nextBackgroundRef: GradientDrawable? = null
    val nextTv = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(titleColorArgb)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    val nextBtn = FrameLayout(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(0, (14 * density).toInt(), 0, (20 * density).toInt())
        }
        minimumHeight = (48 * density).toInt()
        background = GradientDrawable().apply {
            setColor(chromeArgb)
            cornerRadius = 12 * density
        }
        setPadding((12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
        isClickable = true
        setOnClickListener { onNextChapter() }
        addView(nextTv)
    }
    container.addView(nextBtn)
    nextBackgroundRef = nextBtn.background as GradientDrawable

    // 5. 全书完结指示
    val endTv = TextView(ctx).apply {
        tag = "end"
        text = "— 全书完 —"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setTextColor(metaColorArgb)
        gravity = Gravity.CENTER
        setPadding(0, (20 * density).toInt(), 0, (20 * density).toInt())
    }
    container.addView(endTv)

    scrollView.addView(container)

    // 6. 初始化 Choreographer 自动滚屏引擎
    val autoEngine = AutoScrollEngine(
        scrollView = scrollView,
        onNextChapterRequest = { onNextChapter() }
    )
    autoEngine.speedPxPerSec = autoScrollSpeedPxPerSec

    val holder = ReaderViewHolder(
        scrollView = scrollView,
        container = container,
        prevBtn = prevBtn,
        prevTv = prevTv,
        titleTv = titleTv,
        bodyTv = bodyTv,
        nextBtn = nextBtn,
        nextTv = nextTv,
        endTv = endTv,
        autoScrollEngine = autoEngine
    )
    holder.prevBackground = prevBackgroundRef
    holder.nextBackground = nextBackgroundRef
    // 工厂已直接写到 View 上的初值：声明为"已应用"，首次绑定只补下发工厂未设置的项
    // （行距档位、字距与正文字面），避免无变化时仍然重建整章 StaticLayout
    val factoryAppearance = ReaderPaneAppearance(
        backgroundArgb = backgroundArgb,
        chromeArgb = chromeArgb,
        bodyColorArgb = bodyColorArgb,
        titleColorArgb = titleColorArgb,
        metaColorArgb = metaColorArgb,
        fontSizeSp = fontSizeSp,
        lineSpacingMultiplier = LineSpacingMode.STANDARD.multiplier
    )
    holder.appliedAppearance = factoryAppearance
    scrollView.tag = holder
    return holder
}

/**
 * 外观差量下发（见 [ReaderPaneStyles]）：只在值真正变化时触碰对应属性，
 * 避免同一帧内重复整章排版
 */
internal fun applyChapterStyles(holder: ReaderViewHolder, target: ReaderPaneAppearance) {
    holder.appliedAppearance = ReaderPaneStyles.apply(holder, holder.appliedAppearance, target)
}

/**
 * 极速数据绑定（微秒级直接属性赋值，0 对象分配）
 */
internal fun bindChapterData(holder: ReaderViewHolder, content: ChapterContent?) {
    holder.currentContent = content
    if (content == null) {
        holder.container.tag = null
        holder.prevBtn.visibility = View.GONE
        holder.titleTv.text = ""
        holder.bodyTv.text = ""
        holder.nextBtn.visibility = View.GONE
        holder.endTv.visibility = View.GONE
        return
    }

    holder.container.tag = content.chapterIndex

    // 1. 上一章
    if (content.hasPrevChapter) {
        holder.prevBtn.visibility = View.VISIBLE
        holder.prevTv.text = "‹ 上一章: ${content.prevChapterTitle}"
    } else {
        holder.prevBtn.visibility = View.GONE
    }

    // 2. 章节标题（无上一章按钮时增加顶部安全避让间距）
    val density = holder.scrollView.context.resources.displayMetrics.density
    val topPad = (ReaderPaneLogic.chapterTitleTopPaddingDp(content.hasPrevChapter) * density).toInt()
    holder.titleTv.setPadding(0, topPad, 0, (10 * density).toInt())
    holder.titleTv.text = content.title

    // 3. 章节正文（单 TextLayout 一体排版；样式由 applyChapterStyles 差量下发）
    holder.bodyTv.text = content.formattedBody

    // 4. 下一章 / 全书完
    if (content.hasNextChapter) {
        holder.nextBtn.visibility = View.VISIBLE
        holder.nextTv.text = "下一章: ${content.nextChapterTitle} ›"
        holder.endTv.visibility = View.GONE
    } else {
        holder.nextBtn.visibility = View.GONE
        holder.endTv.visibility = View.VISIBLE
    }
}

/**
 * 滚动位置持久化：基于 bodyTv 真实行定位进行精准防抖持久化，消除线性映射偏差
 */
internal fun installReaderPaneScrollPersistence(
    scrollView: ScrollView,
    holder: ReaderViewHolder,
    readingPosition: ReadingPosition,
    onCharOffsetChange: (Int) -> Unit,
    onScrollActivity: () -> Unit
) {
    val handler = Handler(Looper.getMainLooper())
    var saveRunnable: Runnable? = null
    var lastReportedOffset = -1

    scrollView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
        onScrollActivity()
        val content = holder.currentContent
        if (content != null && content.formattedBody.isNotEmpty()) {
            val bodyTop = holder.bodyTv.top
            val layout = holder.bodyTv.layout
            val currentOffset = if (layout != null &&
                ReaderPaneLogic.isLineDomainReady(layout.lineCount, holder.bodyTv.height)
            ) {
                val clampedY = ReaderPaneLogic.clampBodyScrollY(scrollY, bodyTop, holder.bodyTv.height)
                val line = layout.getLineForVertical(clampedY)
                val charOffsetInBody =
                    ReaderPaneLogic.clampCharOffset(layout.getLineStart(line), content.formattedBody.length)
                // 正文坐标 → 原文坐标：段落映射精确换算，持久化位置必须落在原文域
                content.startCharOffset + ChapterOffsetMapper.bodyToRaw(
                    charOffsetInBody,
                    content.bodyParagraphStarts,
                    content.rawParagraphStarts,
                    content.formattedBody.length,
                    rawLength = content.endCharOffset - content.startCharOffset,
                    indentChars = content.paragraphIndentChars
                )
            } else {
                ReaderPaneLogic.rawOffsetByRatio(
                    scrollY,
                    bodyTop,
                    holder.bodyTv.height,
                    content.startCharOffset,
                    content.endCharOffset
                )
            }
            readingPosition.update(currentOffset)

            if (currentOffset != lastReportedOffset) {
                lastReportedOffset = currentOffset
                saveRunnable?.let { handler.removeCallbacks(it) }
                saveRunnable = Runnable { onCharOffsetChange(currentOffset) }
                handler.postDelayed(saveRunnable!!, 300L)
            }
        }
    }
}

/**
 * 恢复滚动位置：采用原生 Layout 行高映射定位，根除线性比例漂移
 */
internal fun restoreScrollPosition(
    scrollView: ScrollView,
    holder: ReaderViewHolder,
    content: ChapterContent?,
    initialCharOffset: Int
) {
    if (content == null) return
    val maxScrollY = ScrollMetrics.maxScrollY(scrollView)
    if (initialCharOffset <= content.startCharOffset) {
        val sixDpPx = (6 * scrollView.resources.displayMetrics.density).toInt()
        scrollView.scrollTo(
            0,
            ReaderScrollMath.clampScrollY(
                ReaderScrollMath.topAnchorScrollY(holder.titleTv.top, sixDpPx),
                maxScrollY
            )
        )
        return
    }

    val layout = holder.bodyTv.layout
    if (layout != null && ReaderPaneLogic.isLineDomainReady(layout.lineCount, holder.bodyTv.height)) {
        // 原文坐标 → 正文坐标：段落映射精确反解后再定位排版行
        val charOffsetInBody = ChapterOffsetMapper.rawToBody(
            initialCharOffset - content.startCharOffset,
            content.bodyParagraphStarts,
            content.rawParagraphStarts,
            content.formattedBody.length,
            indentChars = content.paragraphIndentChars
        )
        val line = layout.getLineForOffset(charOffsetInBody)
        scrollView.scrollTo(
            0,
            ReaderScrollMath.clampScrollY(
                ReaderScrollMath.lineScrollY(holder.bodyTv.top, layout.getLineTop(line)),
                maxScrollY
            )
        )
    } else {
        val chapterLen = maxOf(1, content.endCharOffset - content.startCharOffset)
        val relativeOffset = (initialCharOffset - content.startCharOffset).coerceIn(0, chapterLen)
        val ratio = relativeOffset.toFloat() / chapterLen
        scrollView.scrollTo(
            0,
            ReaderScrollMath.clampScrollY(
                ReaderScrollMath.ratioScrollY(holder.bodyTv.top, maxOf(1, holder.bodyTv.height), ratio),
                maxScrollY
            )
        )
    }
}

/**
 * 安全恢复滚动位置：若 bodyTv 尚未完成 measure/layout，自动监听布局就绪后精准还原，杜绝 0 高度归零
 */
internal fun safeRestoreScrollPosition(
    scrollView: ScrollView,
    holder: ReaderViewHolder,
    content: ChapterContent?,
    initialCharOffset: Int
) {
    // 章节切换/位置恢复与惯性滑动互斥，防止 fling 把恢复位置再拖走
    CrownScrollHelper.abortFling(scrollView)
    if (content == null) return
    if (ReaderScrollMath.isLayoutReady(holder.bodyTv.height, holder.bodyTv.layout != null)) {
        restoreScrollPosition(scrollView, holder, content, initialCharOffset)
    } else {
        holder.bodyTv.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View?, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
            ) {
                if (ReaderScrollMath.isLayoutReady(bottom - top, holder.bodyTv.layout != null)) {
                    holder.bodyTv.removeOnLayoutChangeListener(this)
                    restoreScrollPosition(scrollView, holder, holder.currentContent, initialCharOffset)
                }
            }
        })
    }
}

/**
 * AndroidView.update：把一帧的组合状态落到常驻 View 树上。
 *
 * 顺序即契约：可见性 → 引擎同步 → 换章（绑定数据 → 下发外观 → 恢复位置）→ 外观兜底。
 */
internal fun updateReaderPane(
    scrollView: ScrollView,
    covered: Boolean,
    isAutoScrolling: Boolean,
    autoScrollSpeed: Float,
    chapterContent: ChapterContent?,
    initialCharOffset: Int,
    targetAppearance: ReaderPaneAppearance,
    readingPosition: ReadingPosition
) {
    val holder = scrollView.tag as? ReaderViewHolder ?: return

    // 被覆盖页压顶：原生视图转 INVISIBLE 退出绘制但保持挂载 ——
    // ViewGroup 跳过不可见子节点，隐藏期零绘制开销；返回阅读时零重建，
    // 整章排版、滚动位置与表冠监听原样保留（本页常驻化的核心收益）
    val targetVisibility = if (covered) View.INVISIBLE else View.VISIBLE
    if (scrollView.visibility != targetVisibility) {
        scrollView.visibility = targetVisibility
        if (covered) CrownScrollHelper.abortFling(scrollView)
    }

    // 同步自动滚屏引擎状态（被覆盖时挂起，避免不可见状态下空转滚动）
    val engine = holder.autoScrollEngine
    engine.speedPxPerSec = autoScrollSpeed
    when (ReaderPaneLogic.engineSyncAction(covered, isAutoScrolling, engine.isRunning)) {
        ReaderPaneLogic.EngineAction.START -> engine.start()
        ReaderPaneLogic.EngineAction.STOP -> engine.stop()
        ReaderPaneLogic.EngineAction.NONE -> Unit
    }

    val lastChapterIndex = holder.container.tag as? Int
    val currentChapterIdx = chapterContent?.chapterIndex

    if (lastChapterIndex != currentChapterIdx || holder.currentContent != chapterContent) {
        bindChapterData(holder, chapterContent)
        // 样式必须先于位置恢复下发：换章与换排版同时发生时，滚动位置要按新排版度量
        applyChapterStyles(holder, targetAppearance)
        // 同一章内仅换排版（净化开关只替换正文、不跳页）时，实时偏移仍落在本章区间内，
        // 以它为准才不会把阅读位置弹回本章最初打开的地方
        val restoreOffset = ReaderPaneLogic.chapterChangeRestoreOffset(
            liveOffset = readingPosition.latestValue,
            sameChapter = lastChapterIndex == currentChapterIdx,
            hasContent = chapterContent != null,
            startCharOffset = chapterContent?.startCharOffset ?: 0,
            endCharOffset = chapterContent?.endCharOffset ?: 0,
            initialCharOffset = initialCharOffset
        )
        safeRestoreScrollPosition(scrollView, holder, chapterContent, restoreOffset)
        if (isAutoScrolling) {
            holder.autoScrollEngine.start()
        }
    }
    // 外观差量下发：颜色 / 字号 / 行距 / 字距 / 字面全走同一份已应用快照，无变化时零 View 调用
    // （卡片背景仅重着色不重建 GradientDrawable）。覆盖"同章内仅换排版"这条路径，
    // 换章分支已在恢复位置前下发过，此处为空操作
    applyChapterStyles(holder, targetAppearance)
}
