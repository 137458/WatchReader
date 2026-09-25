package com.watchreader

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import java.lang.reflect.Method

/**
 * 表冠旋转与线性马达触觉管理器
 *
 * 逆向对齐 OPPO Watch X2 / ColorOS Watch 系统桌面（HeyLauncher）RecyclerView 表冠管线：
 * 1. 【后台线程】所有马达调用投递到专用 "crown_vibrate" HandlerThread（launcher 原样实现）——
 *    UI 线程直调 linearmotor 服务会因 IPC 阻塞造成输入与显示脱节、滚动卡顿，这是历史上
 *    "震动连成一片、没有惯性观感" 的直接根因之一；
 * 2. 【原厂波形】齿轮微振 Waveform 302（strength 2，与 launcher 字节码一致），
 *    View CLOCK_TICK 与标准 Vibrator 仅作非 OPPO 设备兜底；
 *    触底/触顶不响任何振感（产品决策：边界静默钳制），301 波形仅保留给成功确认反馈；
 * 3. 【节流】20ms 最小间隔兜底（launcher 仅靠 24px 位移门限，马达服务自身会抢占旧波形）。
 */
object RotaryHapticManager {

    private const val TAG = "RotaryHaptic"

    @Volatile
    private var lastVibrateTime = 0L
    private const val MIN_TICK_INTERVAL_MS = 20L

    // 表冠震动专用后台线程（对齐 HeyLauncher "crown_vibate" 线程）
    private val vibrateHandler: Handler by lazy {
        val thread = HandlerThread("crown_vibrate")
        thread.start()
        Handler(thread.looper)
    }

    // OPPO Linearmotor 反射缓存
    @Volatile private var oplusLinearMotorInitialized = false
    private var oplusLinearMotorService: Any? = null
    private var oplusVibrateMethod: Method? = null
    private var oplusPrebuiltTickEffect: Any? = null
    private var oplusPrebuiltBoundaryEffect: Any? = null

    // 标准 Vibrator 缓存
    @Volatile private var cachedVibrator: Vibrator? = null
    @Volatile private var vibratorInitialized = false

    private val touchAudioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .build()
    }

    private val fallbackTickEffect: VibrationEffect? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                VibrationEffect.createOneShot(12, 200)
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
    }

    /**
     * 预初始化 OPPO 官方 Linearmotor 引擎
     */
    fun initOplusLinearmotor(context: Context) {
        if (oplusLinearMotorInitialized) return
        synchronized(this) {
            if (oplusLinearMotorInitialized) return
            try {
                val appContext = context.applicationContext
                val service = appContext.getSystemService("linearmotor")
                Log.d(TAG, "initOplusLinearmotor: service = $service")
                if (service != null) {
                    val serviceClass = service.javaClass
                    val hasMotorMethod = serviceClass.getMethod("hasLinearMotorVibrator").apply { isAccessible = true }
                    val hasMotor = hasMotorMethod.invoke(service) as? Boolean ?: false
                    Log.d(TAG, "initOplusLinearmotor: hasLinearMotor = $hasMotor")

                    if (hasMotor) {
                        oplusLinearMotorService = service
                        val builderClass = Class.forName("android.os.linearmotorvibrator.WaveformEffect\$Builder")
                        val buildMethod = builderClass.getMethod("build").apply { isAccessible = true }
                        val setStrengthMethod = builderClass.getMethod("setEffectStrength", Int::class.javaPrimitiveType).apply { isAccessible = true }
                        val setTypeMethod = builderClass.getMethod("setEffectType", Int::class.javaPrimitiveType).apply { isAccessible = true }
                        val setLoopMethod = builderClass.getMethod("setEffectLoop", Boolean::class.javaPrimitiveType).apply { isAccessible = true }

                        // 构造 302 官方表冠齿轮微振波形 (EffectType.CROWN_TICK)
                        val tickBuilder = builderClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                        setStrengthMethod.invoke(tickBuilder, 2)
                        setTypeMethod.invoke(tickBuilder, 302)
                        setLoopMethod.invoke(tickBuilder, false)
                        oplusPrebuiltTickEffect = buildMethod.invoke(tickBuilder)

                        // 构造 301 触底边界波形
                        val boundaryBuilder = builderClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
                        setStrengthMethod.invoke(boundaryBuilder, 2)
                        setTypeMethod.invoke(boundaryBuilder, 301)
                        setLoopMethod.invoke(boundaryBuilder, false)
                        oplusPrebuiltBoundaryEffect = buildMethod.invoke(boundaryBuilder)

                        oplusVibrateMethod = serviceClass.getMethod("vibrate", oplusPrebuiltTickEffect!!.javaClass).apply { isAccessible = true }
                        Log.i(TAG, "OPPO Official Linearmotor initialized successfully (Waveform 302)")
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Linearmotor reflection failed: ${e.message}", e)
            }
            oplusLinearMotorInitialized = true
        }
    }

    private fun getVibratorFast(context: Context): Vibrator? {
        if (!vibratorInitialized) {
            synchronized(this) {
                if (!vibratorInitialized) {
                    val appContext = context.applicationContext
                    cachedVibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val manager = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                        manager?.defaultVibrator
                    } else {
                        @Suppress("DEPRECATION")
                        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    }
                    vibratorInitialized = true
                }
            }
        }
        return cachedVibrator
    }

    /**
     * 触发表冠旋转一格时的微振反馈（原厂 302 齿轮微振，后台线程投递）
     */
    fun performScrollTick(context: Context?, view: View? = null) {
        val now = SystemClock.uptimeMillis()
        if (now - lastVibrateTime < MIN_TICK_INTERVAL_MS) {
            return
        }
        lastVibrateTime = now
        vibrateHandler.post { vibrateScrollTick(context, view) }
    }

    private fun vibrateScrollTick(context: Context?, view: View?) {
        // 1. OPPO 私有 Linearmotor 原厂 302 瞬态齿轮波形（对齐 HeyLauncher）
        if (context != null) {
            try {
                if (!oplusLinearMotorInitialized) {
                    initOplusLinearmotor(context)
                }
                if (oplusLinearMotorService != null && oplusVibrateMethod != null && oplusPrebuiltTickEffect != null) {
                    oplusVibrateMethod!!.invoke(oplusLinearMotorService, oplusPrebuiltTickEffect)
                    return
                }
            } catch (_: Throwable) {}
        }

        // 2. View 级 CLOCK_TICK 兜底（非 OPPO 设备的系统刻度波形）
        view?.let { v ->
            try {
                val flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                if (v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, flags)) {
                    return
                }
            } catch (_: Throwable) {}
        }

        if (context == null) return

        // 3. 通用 Android 马达极短瞬态脉冲保底（8ms 瞬态杜绝拖尾）
        try {
            val vibrator = getVibratorFast(context)
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(
                        VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK),
                        touchAudioAttributes
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(8, 120),
                        touchAudioAttributes
                    )
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * 成功完成（如 Wi-Fi 传书接收成功 / 书签保存成功）的双重确认振感（后台线程投递）
     */
    fun performSuccessFeedback(context: Context?) {
        if (context == null) return
        vibrateHandler.post { vibrateSuccess(context) }
    }

    private fun vibrateSuccess(context: Context) {
        try {
            if (!oplusLinearMotorInitialized) {
                initOplusLinearmotor(context)
            }
            if (oplusLinearMotorService != null && oplusVibrateMethod != null && oplusPrebuiltBoundaryEffect != null) {
                oplusVibrateMethod!!.invoke(oplusLinearMotorService, oplusPrebuiltBoundaryEffect)
                return
            }
        } catch (_: Throwable) {}

        try {
            val vibrator = getVibratorFast(context)
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val timings = longArrayOf(0, 30, 60, 45)
                    val amplitudes = intArrayOf(0, 200, 0, 255)
                    vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1), touchAudioAttributes)
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(longArrayOf(0, 30, 60, 45), -1)
                }
            }
        } catch (_: Throwable) {}
    }
}



