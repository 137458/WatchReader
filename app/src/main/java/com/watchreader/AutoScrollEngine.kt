package com.watchreader

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.widget.ScrollView

/**
 * 基于 Choreographer 的 0 GC 高性能自动平滑滚屏引擎
 *
 * 性能优化特点：
 * 1. 硬件 VSYNC 对齐：通过 Choreographer 帧回调严格对齐 60/120Hz 刷新率，动态根据精确纳秒时间差 dt 推进。
 * 2. 0 GC 分配：预分配单例 FrameCallback，滚动期间绝不产生任何临时对象与内存抖动。
 * 3. 亚像素累加器：微小位移平滑累积为整像素步进，彻底告别 Timer 定时器卡顿跳跃感。
 * 4. 触底智能联动：触底时自动通知 ViewModel 切换下一章。
 */
class AutoScrollEngine(
    private val scrollView: ScrollView,
    private val onNextChapterRequest: () -> Unit,
    private val onAutoScrollStateChange: ((Boolean) -> Unit)? = null
) {
    var isRunning: Boolean = false
        private set

    var speedPxPerSec: Float = 45f // 默认每秒 45 像素 (约 2~3 行)
        set(value) {
            field = value.coerceIn(15f, 250f)
        }

    private var lastFrameTimeNanos: Long = 0L
    private var accumulatedDeltaY: Float = 0f

    private val mainHandler = Handler(Looper.getMainLooper())
    private var resumeRunnable: Runnable? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning) return

            if (lastFrameTimeNanos > 0L) {
                val dtSec = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f
                // 限制单帧最大时间差为 50ms，防止锁屏或后台唤醒产生巨大位移突变
                val safeDt = dtSec.coerceIn(0.001f, 0.05f)
                val deltaPixels = speedPxPerSec * safeDt
                accumulatedDeltaY += deltaPixels

                if (accumulatedDeltaY >= 1.0f) {
                    val step = accumulatedDeltaY.toInt()
                    val child = scrollView.getChildAt(0)
                    val maxScroll = if (child != null) maxOf(0, child.height - scrollView.height) else 0
                    val currentY = scrollView.scrollY

                    if (maxScroll > 0 && currentY >= maxScroll) {
                        // 到达章节末尾，触发自动翻章
                        stop()
                        onNextChapterRequest()
                        return
                    } else {
                        scrollView.scrollBy(0, step)
                        accumulatedDeltaY -= step
                    }
                }
            }

            lastFrameTimeNanos = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * 启动自动滚屏
     */
    fun start() {
        if (isRunning) return
        resumeRunnable?.let { mainHandler.removeCallbacks(it) }
        isRunning = true
        lastFrameTimeNanos = 0L
        accumulatedDeltaY = 0f
        Choreographer.getInstance().postFrameCallback(frameCallback)
        onAutoScrollStateChange?.invoke(true)
    }

    /**
     * 停止自动滚屏
     */
    fun stop() {
        if (!isRunning) return
        resumeRunnable?.let { mainHandler.removeCallbacks(it) }
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        onAutoScrollStateChange?.invoke(false)
    }

    /**
     * 切换自动滚屏开关
     */
    fun toggle() {
        if (isRunning) stop() else start()
    }

    /**
     * 触摸屏幕时临时暂停，并在松手后延时自动恢复
     */
    fun pauseTemporarily(delayMs: Long = 1800L) {
        if (!isRunning) return
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        resumeRunnable?.let { mainHandler.removeCallbacks(it) }

        resumeRunnable = Runnable {
            if (isRunning) {
                lastFrameTimeNanos = 0L
                Choreographer.getInstance().postFrameCallback(frameCallback)
            }
        }
        mainHandler.postDelayed(resumeRunnable!!, delayMs)
    }

    /**
     * 增加或减少速度
     */
    fun adjustSpeed(delta: Float) {
        speedPxPerSec = (speedPxPerSec + delta).coerceIn(15f, 250f)
    }

    fun release() {
        stop()
    }
}
