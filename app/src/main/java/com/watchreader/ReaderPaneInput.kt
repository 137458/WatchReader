package com.watchreader

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.ScrollView

/**
 * 阅读页的原生输入管线：点按翻页 / 长按呼菜单 / 弧形寻道 / 表冠滚动与调速。
 *
 * 为什么是一层类而不是工厂里的一堆内联监听器：监听器只在 factory 里装配一次，
 * 闭包会把首帧参数烧死在 lambda 里（自动滚屏点按暂停失效、点按热区切换不生效的根因）。
 * 这里把"事件期需要最新值"的读取全部改成 [chapterIndex] / [isAutoScrolling] 这类取值函数，
 * 装配一次、永久生效；装配动作收敛到 [install] 一个入口，顺序即契约。
 */
internal class ReaderPaneInputBinding(
    private val context: Context,
    private val scrollView: ScrollView,
    private val autoEngine: AutoScrollEngine,
    private val seekRecognizer: ArcSeekGestureRecognizer,
    private val seekState: ArcSeekUiState,
    private val chapterIndex: () -> Int,
    private val chapterCount: () -> Int,
    private val isAutoScrolling: () -> Boolean,
    private val tapPageArea: () -> Int,
    private val keepScreenOnTrigger: () -> Unit,
    private val onScrollActivity: () -> Unit,
    private val onAutoScrollToggle: () -> Unit,
    private val onAutoScrollSpeedChange: (Float) -> Unit,
    private val onLongPress: () -> Unit,
    private val onSeekChapter: (Int) -> Unit
) {

    private val density = context.resources.displayMetrics.density

    // 手指是否在屏：表冠与手指同时改 scrollY 会互相抢滚动量（表现为转动时跳动），
    // 手指按住期间表冠事件直接让位，抬起后立即恢复
    private var pointerDown = false

    /**
     * 一次表冠旋转增量（极性已在 [CrownScrollHelper.extractCrownDelta] 归一化）。
     *
     * 两条入口共用本方法：原生 ScrollView 的 OnGenericMotionListener（依赖原生焦点）、
     * 以及组合期登记的 [CrownScrollTarget]（绕开焦点，见 [rememberCrownScrollTarget]）。
     * 收口前只有前者，Compose 组件拿走焦点后表冠事件就落到空处——阅读页"转表冠不滚"的根因。
     *
     * @return true 表示本次增量已被消费
     */
    fun onCrownDelta(delta: Float, source: android.view.View): Boolean {
        if (delta == 0f) return false
        // 手指交互期间让位：不做任何滚动，也不触发振感
        if (pointerDown) return true

        keepScreenOnTrigger()
        onScrollActivity()

        if (autoEngine.isRunning) {
            if (ReaderPaneLogic.isCrownAdjustDelta(delta)) {
                autoEngine.adjustSpeed(ReaderPaneLogic.autoScrollSpeedDelta(delta))
                onAutoScrollSpeedChange(autoEngine.speedPxPerSec)
                // 调速档位微振：每物理档位（12 单位）一振，与全应用表冠振感节奏统一
                CrownScrollHelper.dispatchAdjustTick(delta, context, source)
            }
            return true
        }
        // 常规阅读：统一走表冠管线（系统滚动系数 × 0.5 连续下发 + 距离门限齿轮微振），
        // 替代原先落给原生 ScrollView 的裸滚动（无振感且步进随系统滚动系数漂移）
        if (ReaderPaneLogic.isCrownScrollDelta(delta)) {
            CrownScrollHelper.dispatchScroll(delta, scrollView, context, source)
        }
        return true
    }

    fun install() {
        // 手势交互：支持上下/左右点按翻页、长按呼出菜单、自动滚屏时单击暂停。
        // 点按走 onSingleTapUp 即触即判：本应用没有双击行为，onSingleTapConfirmed 会让
        // 最高频的点按翻页每次白等 ~300ms 双击确认窗（腕上"点了没反应"的主要体感来源）
        val handleTap: (Float, Float) -> Unit = { tapX, tapY ->
            val w = scrollView.width.toFloat()
            val h = scrollView.height.toFloat()
            if (w <= 0f || h <= 0f) {
                // 布局未就绪，忽略本次点按
            } else if (isAutoScrolling()) {
                onAutoScrollToggle()
            } else {
                when (TapPageHelper.resolveTapAction(tapX, tapY, w, h, tapPageArea())) {
                    TapAction.PAGE_UP -> {
                        scrollView.smoothScrollBy(0, -TapPageHelper.calculateScrollDistance(h, density))
                        RotaryHapticManager.performScrollTick(context, scrollView)
                    }
                    TapAction.PAGE_DOWN -> {
                        scrollView.smoothScrollBy(0, TapPageHelper.calculateScrollDistance(h, density))
                        RotaryHapticManager.performScrollTick(context, scrollView)
                    }
                    TapAction.SHOW_MENU -> {
                        if (tapPageArea() == TapPageArea.DISABLED.value) {
                            onAutoScrollToggle()
                        } else {
                            onLongPress()
                        }
                    }
                }
            }
        }
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                autoEngine.stop()
                onLongPress()
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                handleTap(e.x, e.y)
                return true
            }
        })

        scrollView.setOnTouchListener { view, event ->
            var seekHandled = false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pointerDown = true
                    // 手指接管前先停掉表冠惯性，否则转动余速会把手指刚定位的位置再推开
                    CrownScrollHelper.abortFling(scrollView)
                    val tracking = seekRecognizer.onDown(
                        event.x,
                        event.y,
                        view.width.toFloat(),
                        view.height.toFloat(),
                        chapterIndex()
                    )
                    if (tracking) {
                        seekState.targetIndex = chapterIndex()
                        seekState.touchAngle = 0f
                        seekState.isSeeking = false
                    }
                    seekHandled = tracking
                    keepScreenOnTrigger()
                    autoEngine.pauseTemporarily(1800L)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (seekRecognizer.isTracking) {
                        val consumed = seekRecognizer.onMove(
                            event.x,
                            event.y,
                            view.width.toFloat(),
                            view.height.toFloat(),
                            chapterCount()
                        )
                        if (consumed) {
                            seekState.isSeeking = seekRecognizer.isSeeking
                            seekState.touchAngle = seekRecognizer.currentAngle
                            seekState.targetIndex = seekRecognizer.targetChapterIndex
                            seekHandled = true
                        }
                    }
                    onScrollActivity()
                    // 手指持续滑动/慢读期间持续续租，杜绝长按拖拽时引擎强行复苏抢夺屏幕
                    autoEngine.pauseTemporarily(1800L)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    pointerDown = false
                    if (seekRecognizer.isTracking) {
                        // 未越过拖动门限的寻道带点按回落为翻页点按：
                        // 右缘寻道带与翻页热区存在重叠，纯吞掉会形成"点了没反应"死点
                        val wasTapCandidate = !seekRecognizer.isSeeking
                        val confirmed = seekRecognizer.onUp()
                        seekState.isSeeking = false
                        if (confirmed != null) {
                            onSeekChapter(confirmed)
                            seekHandled = true
                        } else if (wasTapCandidate && event.actionMasked == MotionEvent.ACTION_UP) {
                            handleTap(event.x, event.y)
                            seekHandled = true
                        }
                    }
                    keepScreenOnTrigger()
                    // 手指离开屏幕后启动延时恢复
                    autoEngine.pauseTemporarily(1800L)
                }
            }

            // 寻道带内起手的整段手势不参与点按/长按判定（避免拖动双触发）；
            // 带内未成形的纯点按已在 UP 分支回落为翻页点按，不再形成死点
            val tapSuppressed = seekHandled || seekRecognizer.isTracking || seekRecognizer.isSeeking
            if (!tapSuppressed) {
                gestureDetector.onTouchEvent(event)
            }

            tapSuppressed
        }

        // 表冠物理旋转监听（原生路径兜底）：正常由组合期登记的 CrownScrollTarget 先消费；
        // 该路径只在原生 ScrollView 仍持有焦点时生效，作为焦点迁移后的最后一道保险
        scrollView.setOnGenericMotionListener { v, event ->
            if (CrownScrollHelper.isCrownScrollEvent(event)) {
                onCrownDelta(CrownScrollHelper.extractCrownDelta(event), v)
                true
            } else {
                false
            }
        }
    }
}
