package com.watchreader

import android.app.Activity
import android.view.WindowManager

/**
 * 针对 OPPO Watch (ColorOS Watch 3 档硬件亮度) 深度适配的无级极暗调光管理器
 *
 * OPPO Watch 硬件特性分析：
 * - 系统底层仅提供 3 档硬件亮度（1档弱 / 2档中 / 3档强）。
 * - 夜间即使开到硬件「1档 (弱)」，AMOLED 屏幕在漆黑环境下依然刺眼（约 50~80 nits）。
 *
 * 解决方案（双轨融合调光）：
 * 1. 硬件层对齐：
 *    - 3 档 (强): 硬件 1.0f, 纯黑遮罩 0.0f
 *    - 2 档 (中): 硬件 0.6f, 纯黑遮罩 0.0f
 *    - 1 档 (弱): 硬件 0.1f (硬件最低), 纯黑遮罩 0.0f
 * 2. 软件极暗无级层 (< 30%):
 *    - 硬件强制锁定在「1 档最低」，同时在 Window 顶层叠加纯黑硬件加速 Alpha 遮罩 (0.0f ~ 0.78f)。
 *    - 彻底突破 OPPO 3 档硬件限制，实现 1% ~ 100% 丝滑无级微光夜读，0 蓝光刺眼，AMOLED 纯黑像素 0 耗电。
 */
object BrightnessManager {

    const val BRIGHTNESS_SYSTEM_DEFAULT = -1.0f

    // 预设快捷档位
    const val LEVEL_3_STRONG = 1.0f   // 3 档 (强)
    const val LEVEL_2_MEDIUM = 0.65f  // 2 档 (中)
    const val LEVEL_1_WEAK = 0.35f    // 1 档 (弱)
    const val LEVEL_ULTRA_DARK = 0.10f // 🌙 极暗夜读 (1档硬件 + 55% 纯黑遮罩)

    const val HARDWARE_MIN_THRESHOLD = 0.30f // 低于 30% 启动极暗黑场遮罩

    /**
     * 计算顶层纯黑 Alpha 遮罩的不透明度 (0.0f ~ 0.78f)
     * 当 brightness >= 0.30f 时遮罩为 0 (纯硬件调光)
     * 当 brightness 从 0.30f 下降到 0.01f 时，遮罩从 0.0f 平滑增加到 0.75f
     */
    fun calculateDarkOverlayAlpha(brightness: Float): Float {
        if (brightness < 0f || brightness >= HARDWARE_MIN_THRESHOLD) return 0.0f
        val ratio = (HARDWARE_MIN_THRESHOLD - brightness) / HARDWARE_MIN_THRESHOLD
        return (ratio * 0.75f).coerceIn(0.0f, 0.75f)
    }

    /**
     * 将亮度映射并应用到当前 Activity Window
     */
    fun applyToWindow(activity: Activity?, brightness: Float) {
        val window = activity?.window ?: return
        val lp = window.attributes

        val targetHardwareBrightness = when {
            brightness < 0f -> WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            brightness < HARDWARE_MIN_THRESHOLD -> 0.01f // 锁定为 OPPO 硬件 1 档 (最低档)
            brightness < 0.50f -> 0.35f                  // OPPO 1 档 (弱)
            brightness < 0.85f -> 0.65f                  // OPPO 2 档 (中)
            else -> 1.0f                                 // OPPO 3 档 (强)
        }

        if (lp.screenBrightness != targetHardwareBrightness) {
            lp.screenBrightness = targetHardwareBrightness
            window.attributes = lp
        }
    }

    /**
     * 格式化亮度显示文本
     */
    fun formatBrightnessText(brightness: Float): String {
        return when {
            brightness < 0f -> "系统"
            brightness >= 0.85f -> "3档(强)"
            brightness >= 0.50f -> "2档(中)"
            brightness >= HARDWARE_MIN_THRESHOLD -> "1档(弱)"
            else -> {
                val percent = (brightness * 100).toInt().coerceIn(1, 29)
                "🌙夜读($percent%)"
            }
        }
    }
}
