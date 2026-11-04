package com.watchreader

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey

/**
 * 阅读与显示偏好 store：字号、行距、字距、字体、亮度、自动滚屏、主题、点按热区、排版净化。
 *
 * 取值域校验收在这里（亮度、自动滚屏、主题归一、档位枚举），
 * 调用方不再需要知道「深色只是主题的一种取值」这类历史包袱。
 */
object ReadingSettingsStore {

    const val DEFAULT_FONT_SIZE = 14
    const val DEFAULT_AUTO_SCROLL_SPEED = 45f // 默认 45 像素/秒 (约 2~3 行/秒)
    const val DEFAULT_BRIGHTNESS = -1.0f // 默认跟随系统

    private val KEY_FONT_SIZE = intPreferencesKey("font_size")
    private val KEY_DARK_MODE = booleanPreferencesKey("is_dark_mode")
    private val KEY_AUTO_SCROLL_SPEED = floatPreferencesKey("auto_scroll_speed")
    private val KEY_APP_BRIGHTNESS = floatPreferencesKey("app_brightness")
    private val KEY_THEME_MODE = intPreferencesKey("theme_mode")
    private val KEY_TAP_PAGE_AREA = intPreferencesKey("tap_page_area")
    private val KEY_CLEAN_TYPOGRAPHY = booleanPreferencesKey("clean_typography")
    private val KEY_FONT_TYPE = intPreferencesKey("font_type")
    private val KEY_LINE_SPACING = intPreferencesKey("line_spacing")
    private val KEY_LETTER_SPACING = intPreferencesKey("letter_spacing")

    /** 一次读齐的偏好快照；默认值即「从未设置过」时的冷启动取值 */
    data class Settings(
        val fontSize: Int = DEFAULT_FONT_SIZE,
        val autoScrollSpeed: Float = DEFAULT_AUTO_SCROLL_SPEED,
        val appBrightness: Float = DEFAULT_BRIGHTNESS,
        val themeMode: Int = ThemeMode.LIGHT.value,
        val tapPageArea: Int = 0,
        val cleanTypography: Boolean = true,
        val fontType: Int = 0,
        val lineSpacing: Int = LineSpacingMode.STANDARD.value,
        val letterSpacing: Int = LetterSpacingMode.STANDARD.value
    )

    // ── 对外 interface：写入入口（整域读取由门面 loadInitialConfig 的单次 I/O 承担） ──

    suspend fun saveFontSize(context: Context, size: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_FONT_SIZE] = size }
    }

    suspend fun saveAutoScrollSpeed(context: Context, speed: Float) {
        context.dataStore.edit { prefs ->
            // 取值域收敛到 AutoScrollSpeed：此前持久层 10..200、阅读页 15..200、自动滚屏 15..250 三处各钳一套
            prefs[KEY_AUTO_SCROLL_SPEED] = AutoScrollSpeed.clamp(speed)
        }
    }

    suspend fun saveAppBrightness(context: Context, brightness: Float) {
        context.dataStore.edit { prefs ->
            prefs[KEY_APP_BRIGHTNESS] = if (brightness < 0f) -1.0f else brightness.coerceIn(0.01f, 1.0f)
        }
    }

    /** 主题是配色的唯一状态源；KEY_DARK_MODE 仅作旧版遗留数据的镜像写，读侧只用主题 */
    suspend fun saveThemeMode(context: Context, mode: Int) {
        val normalized = ThemeMode.fromValue(mode).value
        context.dataStore.edit { prefs ->
            prefs[KEY_THEME_MODE] = normalized
            prefs[KEY_DARK_MODE] = (normalized != ThemeMode.LIGHT.value)
        }
    }

    suspend fun saveTapPageArea(context: Context, area: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_TAP_PAGE_AREA] = area }
    }

    suspend fun saveCleanTypography(context: Context, enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_CLEAN_TYPOGRAPHY] = enabled }
    }

    suspend fun saveFontType(context: Context, type: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_FONT_TYPE] = type }
    }

    suspend fun saveLineSpacing(context: Context, mode: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_LINE_SPACING] = LineSpacingMode.fromValue(mode).value }
    }

    suspend fun saveLetterSpacing(context: Context, mode: Int) {
        context.dataStore.edit { prefs -> prefs[KEY_LETTER_SPACING] = LetterSpacingMode.fromValue(mode).value }
    }

    // ── 事务级核心（internal） ──

    internal fun read(prefs: Preferences): Settings = Settings(
        fontSize = prefs[KEY_FONT_SIZE] ?: DEFAULT_FONT_SIZE,
        autoScrollSpeed = AutoScrollSpeed.fromStored(prefs[KEY_AUTO_SCROLL_SPEED] ?: DEFAULT_AUTO_SCROLL_SPEED),
        appBrightness = prefs[KEY_APP_BRIGHTNESS] ?: DEFAULT_BRIGHTNESS,
        themeMode = ThemeMode.fromValue(
            prefs[KEY_THEME_MODE] ?: (if (prefs[KEY_DARK_MODE] == true) 1 else 0)
        ).value,
        tapPageArea = prefs[KEY_TAP_PAGE_AREA] ?: 0,
        cleanTypography = prefs[KEY_CLEAN_TYPOGRAPHY] ?: true,
        fontType = prefs[KEY_FONT_TYPE] ?: 0,
        lineSpacing = prefs[KEY_LINE_SPACING] ?: LineSpacingMode.STANDARD.value,
        letterSpacing = prefs[KEY_LETTER_SPACING] ?: LetterSpacingMode.STANDARD.value
    )

    /**
     * 恢复备份时写偏好键。
     * 亮度与行距/字距按备份原值落盘（不做保存路径的钳制与归一），与历史恢复行为一致；
     * 自动滚屏仍过 fromStored，因为它是历史备份里唯一可能越界的浮点值域。
     */
    internal fun writeRestore(prefs: MutablePreferences, settings: Settings) {
        val restoredTheme = ThemeMode.fromValue(settings.themeMode).value
        prefs[KEY_FONT_SIZE] = settings.fontSize
        prefs[KEY_THEME_MODE] = restoredTheme
        prefs[KEY_DARK_MODE] = restoredTheme != ThemeMode.LIGHT.value
        prefs[KEY_AUTO_SCROLL_SPEED] = AutoScrollSpeed.fromStored(settings.autoScrollSpeed)
        prefs[KEY_APP_BRIGHTNESS] = settings.appBrightness
        prefs[KEY_TAP_PAGE_AREA] = settings.tapPageArea
        prefs[KEY_CLEAN_TYPOGRAPHY] = settings.cleanTypography
        prefs[KEY_FONT_TYPE] = settings.fontType
        prefs[KEY_LINE_SPACING] = settings.lineSpacing
        prefs[KEY_LETTER_SPACING] = settings.letterSpacing
    }
}
