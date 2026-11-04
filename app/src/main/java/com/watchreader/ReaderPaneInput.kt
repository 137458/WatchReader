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

        // 表冠物理旋转监听：自动滚屏中可动态调速，未开启时按系统列表口径连续滚动并触发齿轮微振
        scrollView.setOnGenericMotionListener { v, event ->
            if (CrownScrollHelper.isCrownScrollEvent(event)) {
                keepScreenOnTrigger()
                onScrollActivity()
                val delta = CrownScrollHelper.extractCrownDelta(event)
                if (autoEngine.isRunning) {
                    if (ReaderPaneLogic.isCrownAdjustDelta(delta)) {
                        autoEngine.adjustSpeed(ReaderPaneLogic.autoScrollSpeedDelta(delta))
                        onAutoScrollSpeedChange(autoEngine.speedPxPerSec)
                        // 调速档位微振：每物理档位（12 单位）一振，与全应用表冠振感节奏统一
                        CrownScrollHelper.dispatchAdjustTick(delta, context, v)
                    }
                    return@setOnGenericMotionListener true
                }
                // 常规阅读：统一走表冠管线（系统滚动系数 × 0.5 连续下发 + 距离门限齿轮微振），
                // 替代原先落给原生 ScrollView 的裸滚动（无振感且步进随系统滚动系数漂移）
                if (ReaderPaneLogic.isCrownScrollDelta(delta)) {
                    CrownScrollHelper.dispatchScroll(delta, scrollView, context, v)
                }
                return@setOnGenericMotionListener true
            }
            false
        }
    }
}
