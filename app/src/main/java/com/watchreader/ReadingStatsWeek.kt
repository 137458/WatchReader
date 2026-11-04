package com.watchreader

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.coroutines.delay

/**
 * 阅读统计：今日分钟与周摘要的纯计算 + 周条形视图 + 统计分区。
 *
 * 原有三个成员都写成 private 顶层函数/Composable，唯一的锚点是「今天」由
 * System.currentTimeMillis() 与 LocalDate.now() 直接取，因此周摘要与今日分钟
 * 无法在 JVM 测试里喂入固定日期。这里把取「现在」收成默认参数，
 * 行为不变而调用方可注入时点；页面仍然只调 [StatsSettingsBlock]。
 */
internal object ReadingStatsWeek {

    /** 今日已读分钟数：按整分钟截断，负数脏数据原样截断（与旧实现一致） */
    fun todayMinutes(
        days: Map<String, Long>,
        todayKey: String = ReadingStats.dateKeyOf(System.currentTimeMillis())
    ): Int = ((days[todayKey] ?: 0L) / 60L).toInt()

    /** 周摘要文案：本周分钟数 + 读完本数（无完读时省略后半句） */
    fun summaryLabel(
        days: Map<String, Long>,
        finishedCount: Int,
        today: LocalDate = LocalDate.now()
    ): String {
        val weekMinutes = ReadingStats.weekBars(today, days).sumOf { it.minutes }
        val sb = StringBuilder("本周 ").append(weekMinutes).append(" 分钟")
        if (finishedCount > 0) sb.append(" · 已读完 ").append(finishedCount).append(" 本")
        return sb.toString()
    }
}

/**
 * 周阅读条形图：周一至周日 7 根迷你柱，今日高亮，仅随 readDays 变化重算
 */
@Composable
internal fun WeekBars(days: Map<String, Long>) {
    val colors = MaterialTheme.colorScheme
    val today = remember { LocalDate.now() }
    val bars = remember(days, today) { ReadingStats.weekBars(today, days) }
    val maxMinutes = bars.maxOf { it.minutes }.coerceAtLeast(1)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        bars.forEachIndexed { index, bar ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.weight(1f)
            ) {
                val barHeight = if (bar.minutes <= 0) 3.dp
                else (4f + 24f * bar.minutes / maxMinutes).dp
                // 柱体自底部弹性生长，逐柱错峰；只在首次组合播放入场，
                // 阅读时长 tick 引发的数据刷新不重放（remember 不随 bars 重建）
                val grow = remember { Animatable(0f) }
                LaunchedEffect(Unit) {
                    delay(index * WatchMotion.STAGGER_STEP_MS)
                    grow.animateTo(1f, WatchMotion.pressSpring())
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                        .graphicsLayer {
                            scaleY = grow.value
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        }
                        .clip(RoundedCornerShape(3.dp))
                        // 非今日柱用中性表面色，今日柱用强调色——零 alpha 的两档语义
                        .background(if (bar.isToday) colors.primary else colors.surfaceVariant)
                )
                Text(
                    text = bar.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (bar.isToday) colors.primary else colors.onSurfaceVariant
                )
            }
        }
    }
}

/** 统计分区：周条形图 + 周摘要 + 每日目标档 + 今日达成进度 */
@Composable
internal fun StatsSettingsBlock(
    readDays: Map<String, Long>,
    finishedCount: Int,
    readGoalMinutes: Int,
    onReadGoalChange: (Int) -> Unit
) {
    val colors = MaterialTheme.colorScheme
    SettingSection("统计", modifier = Modifier.staggeredEnter(7)) {
        WeekBars(readDays)
        AnimatedValue(ReadingStatsWeek.summaryLabel(readDays, finishedCount)) { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
        }
        HairlineDivider()
        val goalLabel = if (readGoalMinutes <= 0) "关闭" else "$readGoalMinutes 分钟"
        CycleLine("每日目标", goalLabel) {
            val options = ReadingStats.GOAL_OPTIONS_MINUTES
            val nextIdx = (options.indexOf(readGoalMinutes) + 1).mod(options.size)
            onReadGoalChange(options[nextIdx])
        }
        if (readGoalMinutes > 0) {
            val todayMinutes = ReadingStatsWeek.todayMinutes(readDays)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ProgressTrack(
                    progress = (todayMinutes.toFloat() / readGoalMinutes).coerceIn(0f, 1f),
                    height = 3.dp
                )
                Text(
                    text = "今日 $todayMinutes / $readGoalMinutes 分钟",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }
}
