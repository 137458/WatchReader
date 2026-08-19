package com.watchreader

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.gestures.ScrollableState
import kotlin.math.abs
import kotlin.math.sign

/**
 * OPPO Watch (ColorOS Watch / Wear OS) 表冠转动与触觉交互管理器
 *
 * 核心对齐（逆向自 OPPO 官方 HeyLauncher）：
 * 1. 【输入源与极性】精准识别 oplus_crown (SOURCE_MOUSE / SOURCE_CLASS_POINTER) 与 SOURCE_ROTARY_ENCODER。
 * 2. 【极性归一化】AXIS_VSCROLL 负号取反，统一为：顺时针向下翻页为正 (+)，逆时针向上回退为负 (-)。
 * 3. 【刻度累加与振感】对齐 OPPO 系统 crown_vibrator_instance 默认 24px 门限，位移达到阈值即触发原厂 302 齿轮微振。
 * 4. 【Compose 方向修正】通过 dispatchRawDelta(-pixels) 实现即时无延迟的正向翻页（顺时针向下）。
 */
object CrownScrollHelper {

    // 默认单格滚动步进（对应 ViewConfiguration 或标准 40px，约 1.5~2 行文字）
    const val DEFAULT_STEP_PIXELS = 40f
    // OPPO 官方系统默认每 24px 触发一次表冠齿轮微振 (crown_vibrator_instance)
    const val VIBRATION_THRESHOLD_PIXELS = 24f

    @Volatile
    private var accumulatedDistance = 0f

    /**
     * 判断 MotionEvent 是否为合法的表冠/滚轮滚动事件（严格对齐 OPPO 官方）
     */
    fun isCrownScrollEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL) return false
        val source = event.source
        return (source and InputDevice.SOURCE_CLASS_POINTER) != 0 ||
                (source and InputDevice.SOURCE_ROTARY_ENCODER) != 0 ||
                (source and InputDevice.SOURCE_MOUSE) != 0
    }

    /**
     * 从 MotionEvent 中提取归一化的表冠滚动增量
     * 返回值语义：> 0 表示顺时针向下滚动，< 0 表示逆时针向上回滚
     */
    fun extractCrownDelta(event: MotionEvent): Float {
        if (event.action != MotionEvent.ACTION_SCROLL) return 0f
        val source = event.source

        return if ((source and InputDevice.SOURCE_CLASS_POINTER) != 0 ||
            (source and InputDevice.SOURCE_MOUSE) != 0) {
            // OPPO oplus_crown 鼠标滚轮模式：AXIS_VSCROLL 向下为 -1.0，取反后变为 +1.0
            -event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        } else if ((source and InputDevice.SOURCE_ROTARY_ENCODER) != 0) {
            // 标准 Wear OS 旋转编码器模式：AXIS_SCROLL 向下本身为 +1.0
            val axisScroll = event.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (abs(axisScroll) > 0.001f) axisScroll else -event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        } else {
            0f
        }
    }

    /**
     * 将原始表冠档位增量折算为实际屏幕像素滚动量
     */
    fun scaleDeltaToPixels(context: Context?, rawDelta: Float): Float {
        if (abs(rawDelta) < 0.0001f) return 0f
        val scrollFactor = if (context != null) {
            try {
                ViewConfiguration.get(context).scaledVerticalScrollFactor
            } catch (_: Throwable) {
                DEFAULT_STEP_PIXELS
            }
        } else {
            DEFAULT_STEP_PIXELS
        }
        val factor = if (scrollFactor > 1f) scrollFactor else DEFAULT_STEP_PIXELS
        return rawDelta * factor
    }

    /**
     * 执行表冠滚动分发：精准物理移动 + 24px 累加原厂 302 齿轮微振
     *
     * @param scrollPixels 像素滚动量（顺时针向下为正数，逆时针向上为负数）
     * @param scrollState Compose 可滚动状态（LazyListState / ScrollState）
     */
    fun dispatchScroll(
        rawOrPixelDelta: Float,
        scrollState: ScrollableState,
        context: Context?,
        view: View? = null
    ) {
        if (abs(rawOrPixelDelta) < 0.001f) return

        // 无论单次传入的是档位 (±1.0) 还是像素，统一归一化为舒适的阅读行步进（单格 36~44px）
        val scrollPixels = if (abs(rawOrPixelDelta) <= 2.5f) {
            rawOrPixelDelta * DEFAULT_STEP_PIXELS
        } else {
            rawOrPixelDelta
        }

        android.util.Log.d("CrownDebug", "dispatchScroll: raw=$rawOrPixelDelta, scrollPixels=$scrollPixels")

        // 顺时针旋转向下滚动：使用正向 dispatchRawDelta(scrollPixels)
        scrollState.dispatchRawDelta(scrollPixels)

        // 每次有效旋转直接触发 1 次微振（单格对齐 1 振）
        RotaryHapticManager.performScrollTick(context, view)
    }

    /**
     * 兼容快捷方法：处理 Compose onRotaryScrollEvent 或原始事件
     * （Compose onRotaryScrollEvent 传入的 verticalScrollPixels 已经按系统滚动系数放大且顺时针为正）
     */
    fun handleRotaryScroll(
        pixelDelta: Float,
        scrollState: ScrollableState,
        context: Context?,
        view: View? = null
    ) {
        dispatchScroll(pixelDelta, scrollState, context, view)
    }
}

/**
 * 扩展方法：MotionEvent 是否为表冠滚动
 */
fun MotionEvent.isCrownScrollEvent(): Boolean = CrownScrollHelper.isCrownScrollEvent(this)

/**
 * 扩展方法：获取 MotionEvent 表冠滚动增量
 */
fun MotionEvent.getCrownScrollDelta(): Float = CrownScrollHelper.extractCrownDelta(this)



