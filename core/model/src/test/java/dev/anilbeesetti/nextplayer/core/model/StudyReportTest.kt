package dev.anilbeesetti.nextplayer.core.model

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 报表数字错了不会崩，只会让家长误判「要不要继续开这个功能」。
 * 这些用例负责把口径钉住：窗口怎么算、缺记录怎么补、错词怎么排。
 */
class StudyReportTest {

    private val now = System.currentTimeMillis()

    private fun daysAgo(days: Int): Long {
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        calendar.add(Calendar.DAY_OF_YEAR, -days)
        return calendar.timeInMillis
    }

    @Test
    fun `窗口恒为七天且末位是今天`() {
        val report = StudyReportCalculator.build(StudyProgress(), now)

        assertEquals(7, report.days.size)
        assertEquals(dayKey(now), report.days.last().date)
        assertEquals(dayKey(daysAgo(6)), report.days.first().date)
        assertFalse(report.hasData)
        assertEquals(0, report.accuracyPercent)
    }

    @Test
    fun `没有记录的日子补零值保证图表连续`() {
        val progress = StudyProgress(
            dailyStats = DailyStudyStat(date = dayKey(now), answered = 2, correct = 2),
        )
        val report = StudyReportCalculator.build(progress, now)

        assertEquals(7, report.days.size)
        // 前六天都没答题，但柱子要占位，不能塌成 1 根
        assertEquals(6, report.days.count { it.answered == 0 })
        assertEquals(1, report.activeDays)
    }

    @Test
    fun `正确率按窗口内总量计算而不是按天平均`() {
        val yesterday = daysAgo(1)
        val progress = StudyProgress(
            dailyStats = DailyStudyStat(date = dayKey(now), answered = 4, correct = 3),
            dailyHistory = mapOf(
                dayKey(yesterday) to DailyStudyStat(date = dayKey(yesterday), answered = 6, correct = 3),
            ),
        )
        val report = StudyReportCalculator.build(progress, now)

        assertEquals(10, report.totalAnswered)
        assertEquals(6, report.totalCorrect)
        // 按天平均会是 (75 + 50) / 2 = 62，正确答案是 6/10 = 60
        assertEquals(60, report.accuracyPercent)
        assertEquals(2, report.activeDays)
    }

    @Test
    fun `今天的数据优先取自 dailyStats`() {
        val todayKey = dayKey(now)
        val progress = StudyProgress(
            dailyStats = DailyStudyStat(date = todayKey, answered = 5, correct = 4),
            // 历史表里塞一个同名的旧值，验证不会被它覆盖
            dailyHistory = mapOf(todayKey to DailyStudyStat(date = todayKey, answered = 99, correct = 99)),
        )
        val report = StudyReportCalculator.build(progress, now)

        assertEquals(5, report.days.last().answered)
    }

    @Test
    fun `错词按错误次数降序并把欠账排在同分前列`() {
        val progress = StudyProgress(
            wordStats = mapOf(
                // 错 3 次
                "a" to WordStat(seen = 4, correct = 1),
                // 错 1 次，欠账
                "b" to WordStat(seen = 2, correct = 1, debt = true),
                // 错 1 次，欠账；正确率比 b 高，同分时排在后面
                "c" to WordStat(seen = 5, correct = 4, debt = true),
                // 全对，不该出现在清单里
                "d" to WordStat(seen = 3, correct = 3),
            ),
        )
        val report = StudyReportCalculator.build(progress, now)
        val ids = report.hardestWords.map { it.statKey }

        assertEquals(listOf("a", "b", "c"), ids)
        assertEquals(3, report.hardestWords.first().wrong)
    }

    @Test
    fun `错词清单按 topWords 截断`() {
        val stats = (1..12).associate { index ->
            "w$index" to WordStat(seen = index + 1, correct = 1)
        }
        val report = StudyReportCalculator.build(
            progress = StudyProgress(wordStats = stats),
            nowMs = now,
            topWords = 5,
        )
        assertEquals(5, report.hardestWords.size)
    }

    @Test
    fun `全对时错词清单为空`() {
        val progress = StudyProgress(
            wordStats = mapOf("a" to WordStat(seen = 3, correct = 3)),
        )
        assertTrue(StudyReportCalculator.build(progress, now).hardestWords.isEmpty())
    }

    @Test
    fun `单词正确率不会出现除零`() {
        val untouched = WordReport(statKey = "a", seen = 0, correct = 0, debt = false, lastSeenAtMs = 0L)
        assertEquals(0, untouched.accuracyPercent)
        assertEquals(0, untouched.wrong)
    }

    @Test
    fun `跨天窗口能读到昨天的归档`() {
        val progress = StudyProgress()
            .withAnswer("apple", correct = false, nowMs = daysAgo(1))
            .withAnswer("apple", correct = true, nowMs = now)

        val report = StudyReportCalculator.build(progress, now)
        assertEquals(2, report.totalAnswered)
        assertEquals(1, report.totalCorrect)
        assertEquals(50, report.accuracyPercent)
    }
}
