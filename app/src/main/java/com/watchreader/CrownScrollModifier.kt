package com.watchreader

import android.content.Context
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.ListView
import android.widget.ScrollView
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.gestures.ScrollableState
import kotlin.math.abs

/**
 * OPPO Watch (ColorOS Watch / Wear OS) 表冠转动与触觉交互管理器
 *
 * 核心对齐（逆向自本机系统列表内核，与设置 / 桌面同源）：
 * 1. 【输入源与极性】SOURCE_ROTARY_ENCODER 使用 AXIS_SCROLL 原值；oplus_crown（SOURCE_MOUSE /
 *    SOURCE_CLASS_POINTER）使用 AXIS_VSCROLL 取反。统一为顺时针向下为正 (+)，逆时针向上为负 (-)。
 * 2. 【连续滚动】每个事件按 ViewConfiguration 系统滚动系数的 50% 立即下发（至少 1px），
 *    不再按物理档位量子化步进；转动过程与屏幕位移 1:1 跟手。
 * 3. 【刻度累加与振感】对齐 OPPO 系统 mDelta / crown_vibrator_instance（默认 24px）：
 *    按实际滚动像素有符号累积，满门限立即触发一次原厂 302 齿轮微振并保留超出部分的余量；
 *    触底/触顶静默，不响振感；马达投递采用 remove-before 语义。
 * 4. 【惯性】累计位移 × 700 / 窗口毫秒估算速度，超过系统最小起振速度 500px/s 即启动
 *    OverScroller 惯性；任何新表冠事件立即中止惯性恢复跟手，手势数据 25ms 无事件后终止。
 */
object CrownScrollHelper {

    // 每物理档位对应的相对滚轮单位数（2026-09-25 OWW251 实机 getevent 标定）：
    // oplus_crown 上报的是高分辨率相对滚轮单位流而非档位脉冲 —— 约 12~13 单位 = 一个物理棘轮档位。
    // 该标定仅保留给 RSVP / 自动滚屏调速的档位微振，不再参与滚动位移换算。
    const val CROWN_UNITS_PER_NOTCH = 12f

    // 系统列表内核的滚动阻尼：原生每事件只消费系统滚动系数的一半
    internal const val NATIVE_SCROLL_DAMPING = 0.5f

    // OPPO 官方系统默认每 24px 触发一次表冠齿轮微振 (crown_vibrator_instance)
    const val VIBRATION_THRESHOLD_PIXELS = 24f

    // 惯性起振速度门限（px/s）：对齐系统 RecyclerView 的 minFlingVelocity
    internal const val FLING_MIN_VELOCITY_PX_S = 500f
    private const val FLING_MAX_VELOCITY_PX_S = 2000f

    // 速度估计公式：窗口累计位移 × 700 / 窗口毫秒数（launcher 原实现即此系数）
    private const val VELOCITY_ESTIMATE_FACTOR = 700f

    // 系统滚动系数（px/轴单位）：与系统应用共用同一缩放，保证全设备灵敏度一致。
    // 启动时由 ensureSystemScrollFactor 覆盖；默认值仅供 JVM 单测兜底。
    internal const val DEFAULT_SCROLL_FACTOR_PX_PER_UNIT = 2f

    @Volatile
    internal var scrollFactorPxPerUnit = DEFAULT_SCROLL_FACTOR_PX_PER_UNIT

    /** 重置跨界面共享的振感节流状态（JVM 单测隔离用；生产勿调） */
    @VisibleForTesting
    internal fun resetFeedbackGatesForTest() {
        CrownScrollDispatcher.resetForTest()
        scrollFactorPxPerUnit = DEFAULT_SCROLL_FACTOR_PX_PER_UNIT
    }

    /**
     * 判断 MotionEvent 是否为表冠滚动事件
     */
    fun isCrownScrollEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL) return false
        val source = event.source
        return (source and InputDevice.SOURCE_ROTARY_ENCODER) == InputDevice.SOURCE_ROTARY_ENCODER ||
                (source and InputDevice.SOURCE_CLASS_POINTER) != 0 ||
                (source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
    }

    /**
     * 极性归一化提取表冠旋转增量（向下滚动为正数 +，向上滚动为负数 -）
     *
     * 系统列表内核按输入源区分轴向：旋转编码器读 AXIS_SCROLL 原值，
     * oplus_crown 鼠标类读 AXIS_VSCROLL 并取反。
     */
    fun extractCrownDelta(event: MotionEvent): Float {
        if ((event.source and InputDevice.SOURCE_ROTARY_ENCODER) != 0) {
            val scroll = event.getAxisValue(MotionEvent.AXIS_SCROLL)
            if (abs(scroll) > 0.0001f) return scroll
        }
        val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (abs(vertical) > 0.0001f) return -vertical
        return 0f
    }

    /**
     * 单事件滚动像素：对齐系统 RecyclerView 的换算——先乘系统滚动系数，
     * 再乘 0.5 阻尼；结果不足 1px 时按方向补足 1px，保证微小转动也跟手。
     */
    internal fun scrollPixelsFor(rawDelta: Float): Int =
        CrownScrollArithmetic.scrollPixelsFor(rawDelta, scrollFactorPxPerUnit, NATIVE_SCROLL_DAMPING)

    /** 读取系统滚动系数（ViewConfiguration.getScaledVerticalScrollFactor，API 26+）。 */
    fun ensureSystemScrollFactor(context: Context?) {
        CrownScrollDispatcher.ensureSystemScrollFactor(context)
    }

    /** 读取系统表冠振感门限 crown_vibrator_instance（默认 24px），与设置 / 桌面同源。 */
    fun ensureSystemHapticThreshold(context: Context?) {
        CrownScrollDispatcher.ensureSystemHapticThreshold(context)
    }

    /** 表冠滚动分发（Compose 滚动容器）：系统列表同款连续滚动 + 距离门限微振 + 惯性 */
    fun dispatchScroll(
        rawOrPixelDelta: Float,
        scrollState: ScrollableState,
        context: Context?,
        view: View? = null
    ) {
        CrownScrollDispatcher.dispatchScroll(rawOrPixelDelta, scrollState, context, view)
    }

    /** 调速场景（自动滚屏调速 / RSVP 速率）的表冠档位微振 */
    fun dispatchAdjustTick(rawDeltaUnits: Float, context: Context?, view: View? = null) {
        CrownScrollDispatcher.dispatchAdjustTick(rawDeltaUnits, context, view)
    }

    /** 表冠滚动分发（原生 ListView：目录 / 书签 / 选卷） */
    fun dispatchScroll(
        rawOrPixelDelta: Float,
        listView: ListView,
        context: Context?,
        view: View? = null
    ) {
        CrownScrollDispatcher.dispatchScroll(rawOrPixelDelta, listView, context, view)
    }

    /** 表冠滚动分发（原生 ScrollView：阅读页） */
    fun dispatchScroll(
        rawOrPixelDelta: Float,
        scrollView: ScrollView,
        context: Context?,
        view: View? = null
    ) {
        CrownScrollDispatcher.dispatchScroll(rawOrPixelDelta, scrollView, context, view)
    }

    /** 速度估计（纯函数，可测）：累计位移 × 700 / 毫秒 → px/s，钳制 ±2000（系统同源） */
    internal fun computeFlingVelocity(accumDeltaPx: Float, windowMs: Long): Float =
        CrownScrollArithmetic.flingVelocity(
            accumDeltaPx,
            windowMs,
            VELOCITY_ESTIMATE_FACTOR,
            FLING_MAX_VELOCITY_PX_S
        )

    /** 终止惯性滑动（新表冠事件 / 章节切换恢复滚动位置时调用） */
    fun abortFling(surface: Any?) {
        CrownScrollDispatcher.abortFling(surface)
    }
}

/**
 * 为原生 ListView 统一安装表冠滚动管线（连续滚动 + 距离门限齿轮微振 + 静默边界钳制）
 *
 * 目录 / 书签 / 选卷三处 ListView 曾各自内联同一段 setOnGenericMotionListener，
 * 统一收敛到本扩展，杜绝三份拷贝在后续调参时漂移。
 */
fun ListView.bindCrownScroll(context: Context) {
    setOnGenericMotionListener { v, event ->
        if (CrownScrollHelper.isCrownScrollEvent(event)) {
            CrownScrollHelper.dispatchScroll(CrownScrollHelper.extractCrownDelta(event), this, context, v)
            true
        } else {
            false
        }
    }
}

/**
 * 页面级表冠滚动目标：由非原生滚动容器的 Compose 页面（书架 / 菜单 / 无线传书等）实现
 *
 * 与阅读页（原生 ScrollView 自持 OnGenericMotionListener）、目录页（原生 ListView）并列的
 * 第三条表冠路径：CrownRouter 的第二条策略（RSVP 调速之后、原生 View 树之前）优先寻址，
 * 彻底取代 "1dp 隐形焦点锚点"方案 —— 后者依赖原生焦点存活，触点命中与焦点迁移都会使事件丢失，
 * 造成表冠无响应、无振感。
 */
fun interface CrownScrollTarget {
    /**
     * 处理一次表冠旋转增量（已经 CrownScrollHelper 极性归一化：顺时针向下为正）
     *
     * @return true 表示本次增量已消费，CrownRouter 终止继续尝试后续策略
     */
    fun onCrownDelta(delta: Float): Boolean
}

/**
 * 表冠滚动目标注册表（当前前台页面唯一持有者）
 *
 * 这是一个显式的注册接缝而不是分派器：Compose 页面在组合期登记、离开时注销，
 * 何时消费以及消费失败后继续下沉到哪条策略，全部由 CrownRouter 的声明顺序决定。
 */
object CrownScrollTargetRegistry {

    @Volatile
    var active: CrownScrollTarget? = null
        private set

    fun activate(target: CrownScrollTarget) {
        active = target
    }

    /** 惰性注销：仅当仍是自己时清除，避免页面切换过渡期误清后到目标 */
    fun deactivate(target: CrownScrollTarget) {
        if (active === target) {
            active = null
        }
    }
}
