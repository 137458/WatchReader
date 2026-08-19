package com.watchreader

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import java.lang.reflect.Method

/**
 * 表冠旋转与线性马达触觉管理器
 *
 * 针对 OPPO Watch X2 / ColorOS Watch 深度逆向与官方对齐：
 * 1. 优先直调 OPPO 官方底层线性马达私有服务 (LinearmotorVibrator / Waveform 302)
 *    - 服务名: context.getSystemService("linearmotor")
 *    - 官方波形: WaveformEffect.Builder().setEffectStrength(2).setEffectType(302).build()
 * 2. 0ms 延迟同步触发：移除多余的单线程任务排队机制，彻底解决“手停了还在震”的振感粘连问题
 * 3. 补充降级通道：View.performHapticFeedback(CLOCK_TICK) 与标准 Vibrator
 */
object RotaryHapticManager {

    private const val TAG = "RotaryHaptic"

    @Volatile
    private var lastVibrateTime = 0L
    private const val MIN_TICK_INTERVAL_MS = 20L

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
                    val hasMotorMethod = serviceClass.getMethod("hasLinearMotorVibrator")
                    val hasMotor = hasMotorMethod.invoke(service) as? Boolean ?: false
                    Log.d(TAG, "initOplusLinearmotor: hasLinearMotor = $hasMotor")

                    if (hasMotor) {
                        oplusLinearMotorService = service
                        val builderClass = Class.forName("android.os.linearmotorvibrator.WaveformEffect\$Builder")
                        val buildMethod = builderClass.getMethod("build")
                        val setStrengthMethod = builderClass.getMethod("setEffectStrength", Int::class.javaPrimitiveType)
                        val setTypeMethod = builderClass.getMethod("setEffectType", Int::class.javaPrimitiveType)
                        val setLoopMethod = builderClass.getMethod("setEffectLoop", Boolean::class.javaPrimitiveType)

                        // 构造 302 官方表冠齿轮微振波形 (EffectType.CROWN_TICK)
                        val tickBuilder = builderClass.getDeclaredConstructor().newInstance()
                        setStrengthMethod.invoke(tickBuilder, 2)
                        setTypeMethod.invoke(tickBuilder, 302)
                        setLoopMethod.invoke(tickBuilder, false)
                        oplusPrebuiltTickEffect = buildMethod.invoke(tickBuilder)

                        // 构造 301 触底边界波形
                        val boundaryBuilder = builderClass.getDeclaredConstructor().newInstance()
                        setStrengthMethod.invoke(boundaryBuilder, 2)
                        setTypeMethod.invoke(boundaryBuilder, 301)
                        setLoopMethod.invoke(boundaryBuilder, false)
                        oplusPrebuiltBoundaryEffect = buildMethod.invoke(boundaryBuilder)

                        oplusVibrateMethod = serviceClass.getMethod("vibrate", oplusPrebuiltTickEffect!!.javaClass)
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
     * 触发表冠旋转一格时的微振反馈（双通道保障：Linearmotor 302 + 硬件 Vibrator 25ms 强力微脉冲）
     */
    fun performScrollTick(context: Context?, view: View? = null) {
        val now = System.currentTimeMillis()
        if (now - lastVibrateTime < MIN_TICK_INTERVAL_MS) {
            return
        }
        lastVibrateTime = now

        // 1. View 级触觉反馈
        view?.let { v ->
            try {
                val flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK, flags)
            } catch (_: Throwable) {}
        }

        if (context == null) return

        // 2. OPPO 私有 Linearmotor 尝试
        try {
            if (!oplusLinearMotorInitialized) {
                initOplusLinearmotor(context)
            }
            if (oplusLinearMotorService != null && oplusVibrateMethod != null && oplusPrebuiltTickEffect != null) {
                oplusVibrateMethod!!.invoke(oplusLinearMotorService, oplusPrebuiltTickEffect)
            }
        } catch (_: Throwable) {}

        // 3. 标准 Android 硬件马达强力微脉冲（25ms，全功率输出，确保表冠旋转清晰有力的机械齿轮感）
        try {
            val vibrator = getVibratorFast(context)
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(28, 255),
                        touchAudioAttributes
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(28)
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * 触底/触顶边界振感
     */
    fun performBoundaryFeedback(context: Context?, view: View? = null) {
        view?.let { v ->
            try {
                val flags = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                        HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS, flags)
            } catch (_: Throwable) {}
        }

        if (context == null) return
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
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(24, 255),
                        touchAudioAttributes
                    )
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(24)
                }
            }
        } catch (_: Throwable) {}
    }
}



