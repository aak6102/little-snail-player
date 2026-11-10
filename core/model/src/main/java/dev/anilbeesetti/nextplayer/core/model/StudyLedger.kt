package dev.anilbeesetti.nextplayer.core.model

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 历史保留天数。够统计页算「近 7 天」，也留出一倍余量用于事后扩大窗口。
 *
 * 只在写入时裁剪，读取端不做假设：即使存档里多出更早的记录也照常消费。
 */
const val HISTORY_KEEP_DAYS = 14

/**
 * 记录一次作答，返回更新后的进度。
 *
 * 抽成纯函数是为了让 `LocalStudyRepository` 与 `FakeStudyRepository` 共用同一份
 * 记账规则——它们此前各自复制了一段 `recordAnswer`，改统计字段时极易只改一边，
 * 导致测试里绿灯、真机上数据不对。
 *
 * 换天时会把前一天的 [StudyProgress.dailyStats] 收进 [StudyProgress.dailyHistory]：
 * 否则只有「今天」有数据，家长装上一周也画不出趋势。
 */
fun StudyProgress.withAnswer(
    statKey: String,
    correct: Boolean,
    nowMs: Long,
    keepDays: Int = HISTORY_KEEP_DAYS,
    maxLevel: Int = StudyPreferences.DEFAULT_LEVEL,
): StudyProgress {
    val todayKey = dayKey(nowMs)
    val isNewDay = dailyStats.date != todayKey
    val previous = wordStats[statKey] ?: WordStat()

    // 换天：昨天的记录先归档，再开一格空的今天。历史为 0 的日子不落盘，省得存档里全是零。
    val archivedHistory = if (isNewDay && dailyStats.answered > 0) {
        dailyHistory + (dailyStats.date to dailyStats)
    } else {
        dailyHistory
    }

    val today = if (isNewDay) DailyStudyStat(date = todayKey) else dailyStats
    val updatedToday = today.copy(
        answered = today.answered + 1,
        correct = today.correct + if (correct) 1 else 0,
    )

    return copy(
        wordStats = wordStats + (
            statKey to previous.copy(
                seen = previous.seen + 1,
                correct = previous.correct + if (correct) 1 else 0,
                streak = if (correct) previous.streak + 1 else 0,
                lastSeenAtMs = nowMs,
                // 答对就销账，答错（含「错满三次看答案放行」）就留账，等下次独立答对
                debt = !correct,
            )
            ),
        dailyStats = updatedToday,
        dailyHistory = pruneHistory(archivedHistory, nowMs, keepDays),
        // 自适应难度的计数与单词记账在同一次写入里推进：
        // 分成两次 update 会有中间态（词记了、档没降），进程恰好在此刻被杀就丢一次降级
        adaptive = adaptive.afterAnswer(correct, maxLevel),
    )
}

/**
 * 只留最近 [keepDays] 天（含今天）。
 *
 * 日期键是 yyyy-MM-dd，字典序等于时间序，所以直接比字符串，不必解析成时间戳。
 */
internal fun pruneHistory(
    history: Map<String, DailyStudyStat>,
    nowMs: Long,
    keepDays: Int,
): Map<String, DailyStudyStat> {
    if (history.isEmpty()) return history
    val cutoffKey = recentDayKeys(nowMs, keepDays.coerceAtLeast(1)).first()
    val pruned = history.filterKeys { it >= cutoffKey }
    return if (pruned.size == history.size) history else pruned
}

/**
 * 把 `wordStats` 的键从「词条 id」迁移到「词面小写」，只生效一次。
 *
 * 为什么必须迁移：进度以前按 `num_thirteen` 这类人编的 id 存，换一套教材词表
 * 后 id 全变了，孩子的错题池和欠账会在切换的瞬间全部对不上号。
 * 改成按词面存之后，两套词表里拼写相同的词共享掌握情况。
 *
 * @param resolveStatKey 已知词条 id 时返回它的 [QuizWord.statKey]；查不到返回 null，
 *   此时退化为「原键小写」——至少不会把数据丢掉。
 */
fun StudyProgress.migrateStatKeys(
    targetVersion: Int = StudyPreferences.STAT_KEY_VERSION_WORD,
    resolveStatKey: (String) -> String?,
): StudyProgress {
    if (preferences.statKeyVersion >= targetVersion) return this
    return copy(
        wordStats = wordStats.mapKeys { (key, _) ->
            resolveStatKey(key) ?: key.lowercase(Locale.ROOT)
        },
        preferences = preferences.copy(statKeyVersion = targetVersion),
    )
}

/**
 * 生成最近 [days] 天的日期键，**升序**，末位是今天。
 *
 * 用 [Calendar] 而不是自己减 86_400_000：跨夏令时的一天不是 24 小时，
 * 直接减毫秒会在切换日的凌晨算错一天。
 */
fun recentDayKeys(nowMs: Long, days: Int): List<String> {
    val count = days.coerceAtLeast(1)
    val formatter = SimpleDateFormat(DAY_KEY_PATTERN, Locale.US)
    val calendar = Calendar.getInstance().apply { timeInMillis = nowMs }
    // 从今天开始逐日往前，得到一个降序序列，最后反转
    val keys = ArrayList<String>(count)
    repeat(count) { offset ->
        if (offset > 0) calendar.add(Calendar.DAY_OF_YEAR, -1)
        keys += formatter.format(Date(calendar.timeInMillis))
    }
    return keys.asReversed().toList()
}
