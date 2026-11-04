package com.watchreader

import android.graphics.Typeface
import androidx.compose.runtime.Immutable

/**
 * 阅读页原生视图的「已应用外观」快照（唯一真相）
 *
 * 收口前，同一件事被 9 个散落在持有器上的 sentinel 字段分别记录
 * （appliedBgColor / appliedChromeColor / appliedFontSize / appliedBodyTypeface / ...），
 * 每个字段都靠"记得在应用时同步写回"维持正确：新增一个样式项就要同时新增字段、新增分支、
 * 新增写回，漏一处就退化成每帧重建整章 StaticLayout（滚动掉帧的主源之一）。
 * 现在已应用状态只有这一个值，"哪些项变了"只在 [ReaderPaneStyles.apply] 一处判定。
 *
 * @property letterSpacingEm 为 null 表示尚未下发过（首次绑定前的视图初值），强制走一次下发
 * @property bodyTypefaceToken 字面句柄，比较语义是引用相等（[ReaderPaneStyles.apply] 用 !==）：
 *   宋体字面预热完成后同一档位会解析出新的 Typeface 实例，必须重新下发，
 *   因此这里存的是"已下发的那一个字面"而不是字体档位值
 */
@Immutable
data class ReaderPaneAppearance(
    val backgroundArgb: Int,
    val chromeArgb: Int,
    val bodyColorArgb: Int,
    val titleColorArgb: Int,
    val metaColorArgb: Int,
    val fontSizeSp: Int,
    val lineSpacingMultiplier: Float,
    val letterSpacingEm: Float? = null,
    val bodyTypefaceToken: Any? = null
)

/**
 * 外观下发的视图端口
 *
 * 模块只决定"下发哪些项"，不碰 TextView / GradientDrawable：Android 实件是
 * [ReaderViewHolder]（实现本端口），单测用记录调用的假件。这是本模块唯一的接缝。
 */
interface ReaderPaneStylePort {

    fun setBackgroundArgb(argb: Int)

    /** 上一章 / 下一章卡片底色（GradientDrawable 重着色，不重建 Drawable） */
    fun setChromeArgb(argb: Int)

    fun setBodyColorArgb(argb: Int)

    /** 标题色同时作用于章节标题与其下方的"下一章"按钮文字 */
    fun setTitleColorArgb(argb: Int)

    /** 元信息色同时作用于"上一章"按钮文字与全书完结指示 */
    fun setMetaColorArgb(argb: Int)

    fun setTitleTextSizeSp(sp: Float)

    fun setBodyTextSizeSp(sp: Float)

    fun setLineSpacingMultiplier(multiplier: Float)

    fun setLetterSpacingEm(em: Float)

    fun setBodyTypefaceToken(token: Any?)
}

/**
 * 外观差量下发：只触碰真正变化的属性
 *
 * 为什么必须逐项守卫：TextView 的 setTextSize / setTextColor / typeface 各自都会经
 * checkForRelayout() 同步重建整章 StaticLayout。阅读页在滚动、阅读时长 tick、底部百分比
 * 跳变时都会重组，无守卫就等于把低频状态变化放大成全 ScrollView 重排版。
 *
 * @param previous 已应用外观；null 表示尚未应用（全量下发）
 * @return 新的已应用外观，调用方原样存回
 */
object ReaderPaneStyles {

    /** 标题相对正文的字号偏移（sp）：标题恒比正文大两档 */
    const val TITLE_SIZE_OFFSET_SP = 2

    fun apply(
        port: ReaderPaneStylePort,
        previous: ReaderPaneAppearance?,
        target: ReaderPaneAppearance
    ): ReaderPaneAppearance {
        // 不做整体相等短路：data class 的 == 会把字面句柄交给 equals，
        // 而字面的正确语义是引用相等（同一档位可能解析出新字面），这里逐项判定
        if (previous == null || previous.backgroundArgb != target.backgroundArgb) {
            port.setBackgroundArgb(target.backgroundArgb)
        }
        if (previous == null || previous.chromeArgb != target.chromeArgb) {
            port.setChromeArgb(target.chromeArgb)
        }
        if (previous == null || previous.bodyColorArgb != target.bodyColorArgb) {
            port.setBodyColorArgb(target.bodyColorArgb)
        }
        if (previous == null || previous.titleColorArgb != target.titleColorArgb) {
            port.setTitleColorArgb(target.titleColorArgb)
        }
        if (previous == null || previous.metaColorArgb != target.metaColorArgb) {
            port.setMetaColorArgb(target.metaColorArgb)
        }
        if (previous == null || previous.fontSizeSp != target.fontSizeSp) {
            // 字号一项驱动两个 View：标题与正文必须同一次下发，否则两者行高不同步
            port.setTitleTextSizeSp((target.fontSizeSp + TITLE_SIZE_OFFSET_SP).toFloat())
            port.setBodyTextSizeSp(target.fontSizeSp.toFloat())
        }
        if (previous == null || previous.lineSpacingMultiplier != target.lineSpacingMultiplier) {
            port.setLineSpacingMultiplier(target.lineSpacingMultiplier)
        }
        if (previous == null || previous.letterSpacingEm != target.letterSpacingEm) {
            target.letterSpacingEm?.let { port.setLetterSpacingEm(it) }
        }
        if (previous == null || previous.bodyTypefaceToken !== target.bodyTypefaceToken) {
            port.setBodyTypefaceToken(target.bodyTypefaceToken)
        }
        return target
    }
}

/**
 * 字体档位 → 正文目标字面
 *
 * 衬线档必须走中文宋体字面：本 ROM 的 serif 族不含中文字形、zh-Hans 回退又被换成黑体，
 * Typeface.SERIF 下汉字与黑体同形（详见 CjkSerifFont）。此处只读取已解析结果，
 * 绝不在排版路径上同步加载字面；预热完成后的下一次重组会自动改用真正的宋体字面。
 */
internal fun bodyTypefaceFor(fontType: Int): Typeface =
    if (FontType.fromValue(fontType) == FontType.SERIF) {
        CjkSerifFont.resolvedOrNull() ?: Typeface.SERIF
    } else {
        Typeface.SANS_SERIF
    }

/**
 * 阅读位置恢复的换算口径（纯函数，无 View 依赖）
 *
 * 收口前这段换算内联在 ReaderScreen 的 View 操作里：行号映射、行高比例、顶部锚点三套
 * 公式各自散落，边界条件（负值、超过可滚动上限）只能靠 ScrollView.scrollTo 内部 clamp
 * 兜底，出错时表现为"恢复到章节开头"或"停在半屏"，而没有任何一处能单测。
 */
object ReaderScrollMath {

    /** 章节开头：对齐标题上方留白（标题顶端减 6dp） */
    fun topAnchorScrollY(titleTop: Int, sixDpPx: Int): Int = maxOf(0, titleTop - sixDpPx)

    /** 行定位：正文行顶端 + 正文控件在容器内的偏移 */
    fun lineScrollY(bodyTop: Int, lineTop: Int): Int = maxOf(0, bodyTop + lineTop)

    /** 比例定位：正文控件高度 × 章内比例 + 正文控件偏移 */
    fun ratioScrollY(bodyTop: Int, bodyHeight: Int, ratio: Float): Int =
        maxOf(0, (bodyTop + bodyHeight * ratio).toInt())

    /**
     * 收窄到可滚动范围：与 ScrollView.scrollTo 内部 clamp 同口径（见 [ScrollMetrics]）
     *
     * @param maxScrollY 可滚动上限；<= 0 表示布局尚未就绪、上限未知，此时只保证非负，
     *   不收窄——否则恢复位置会被压成 0，表现为"每次进章节都回到顶部"
     */
    fun clampScrollY(desired: Int, maxScrollY: Int): Int =
        if (maxScrollY <= 0) maxOf(0, desired) else desired.coerceIn(0, maxScrollY)

    /** 恢复前置条件：正文控件已 measure 且已产出 Layout，二者缺一都会归零 */
    fun isLayoutReady(bodyHeightPx: Int, hasLayout: Boolean): Boolean = hasLayout && bodyHeightPx > 0
}
