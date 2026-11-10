package dev.anilbeesetti.nextplayer.core.model

/** 单个词的掌握情况，供家长页的错词清单使用。 */
data class WordReport(
    val statKey: String,
    val seen: Int,
    val correct: Int,
    val debt: Boolean,
    val lastSeenAtMs: Long,
) {
    /** 累计答错次数。 */
    val wrong: Int get() = (seen - correct).coerceAtLeast(0)

    /** 正确率百分比，四舍五入到整数；没答过时为 0。 */
    val accuracyPercent: Int
        get() = if (seen <= 0) 0 else (correct * 100f / seen).toInt()
}

/**
 * 家长用来判断「学习模式到底有没有用」的报表。
 *
 * 只做统计，不碰存储和 UI：数字算错了在这里就能被单测抓住，
 * 不必等到装到手机上才发现。
 */
data class StudyReport(
    /** 最近 N 天，按日期升序；没有记录的日子补零值，保证图表横轴连续。 */
    val days: List<DailyStudyStat> = emptyList(),
    /** 错词，按「错得多 → 欠账 → 正确率低」排序，已截断到 Top N。 */
    val hardestWords: List<WordReport> = emptyList(),
) {
    val totalAnswered: Int = days.sumOf { it.answered }
    val totalCorrect: Int = days.sumOf { it.correct }
    val accuracyPercent: Int =
        if (totalAnswered <= 0) 0 else (totalCorrect * 100f / totalAnswered).toInt()

    /** 真正答过题的天数，用来区分「坚持了 5 天」和「装了 5 天没看」。 */
    val activeDays: Int = days.count { it.answered > 0 }

    val hasData: Boolean = totalAnswered > 0
}

object StudyReportCalculator {

    const val DEFAULT_DAYS = 7
    const val DEFAULT_TOP_WORDS = 10

    /**
     * @param days 统计窗口天数，含今天。
     * @param topWords 错词清单最多保留多少个。
     *
     * 错词按 [WordStat] 的**全量**累计排序，不按窗口过滤：`WordStat` 只记了
     * 「最后一次作答时间」，没有逐次时间线，硬按窗口切会把上周错的词算成没答过。
     * 全量排序更诚实——一个错了 6 次的词，不该因为恰好最近没抽到就被藏起来。
     */
    fun build(
        progress: StudyProgress,
        nowMs: Long,
        days: Int = DEFAULT_DAYS,
        topWords: Int = DEFAULT_TOP_WORDS,
    ): StudyReport {
        val keys = recentDayKeys(nowMs, days)
        val todayKey = keys.last()
        val rows = keys.map { key ->
            when {
                // 今天优先读 dailyStats（写入端实时更新），历史表里不含今天
                key == todayKey && progress.dailyStats.date == todayKey -> progress.dailyStats
                else -> progress.dailyHistory[key] ?: DailyStudyStat(date = key)
            }
        }

        val hardest = progress.wordStats
            .map { (statKey, stat) ->
                WordReport(
                    statKey = statKey,
                    seen = stat.seen,
                    correct = stat.correct,
                    debt = stat.debt,
                    lastSeenAtMs = stat.lastSeenAtMs,
                )
            }
            .filter { it.wrong > 0 }
            .sortedWith(
                compareByDescending<WordReport> { it.wrong }
                    .thenByDescending { it.debt }
                    .thenBy { it.accuracyPercent }
                    .thenByDescending { it.lastSeenAtMs },
            )
            .take(topWords.coerceAtLeast(0))

        return StudyReport(days = rows, hardestWords = hardest)
    }
}
