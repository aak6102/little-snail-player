package dev.anilbeesetti.nextplayer.core.model

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记账是统计页的唯一数据来源，错了不会报错、只会让家长看到假的趋势。
 * 这里把「换天归档」和「裁剪」这两个最容易写错的地方钉死。
 */
class StudyLedgerTest {

    private val now = System.currentTimeMillis()

    /** 用 Calendar 而不是减 86_400_000：跨夏令时的一天不是 24 小时，与实现保持同一套算法。 */
    private fun daysAgo(days: Int): Long {
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        calendar.add(Calendar.DAY_OF_YEAR, -days)
        return calendar.timeInMillis
    }

    @Test
    fun `首次答题落在今天且不产生历史`() {
        val progress = StudyProgress().withAnswer(statKey = "apple", correct = true, nowMs = now)

        assertEquals(dayKey(now), progress.dailyStats.date)
        assertEquals(1, progress.dailyStats.answered)
        assertEquals(1, progress.dailyStats.correct)
        assertTrue(progress.dailyHistory.isEmpty())
    }

    @Test
    fun `同一天连续答题累加而不是覆盖`() {
        val progress = StudyProgress()
            .withAnswer("apple", correct = true, nowMs = now)
            .withAnswer("banana", correct = false, nowMs = now)

        assertEquals(2, progress.dailyStats.answered)
        assertEquals(1, progress.dailyStats.correct)
        assertTrue("同一天不该产生历史记录", progress.dailyHistory.isEmpty())
    }

    @Test
    fun `换天时前一天的记录进入历史`() {
        val yesterday = daysAgo(1)
        val progress = StudyProgress()
            .withAnswer("apple", correct = true, nowMs = yesterday)
            .withAnswer("apple", correct = false, nowMs = now)

        val archived = progress.dailyHistory[dayKey(yesterday)]
        assertEquals(1, archived?.answered)
        assertEquals(1, archived?.correct)

        // 今天从零开始，而不是接着昨天的数继续加
        assertEquals(dayKey(now), progress.dailyStats.date)
        assertEquals(1, progress.dailyStats.answered)
        assertEquals(0, progress.dailyStats.correct)
    }

    @Test
    fun `昨天没答题就不产生空历史`() {
        val progress = StudyProgress()
            .withAnswer("apple", correct = true, nowMs = daysAgo(3))
            .withAnswer("apple", correct = true, nowMs = now)

        val archived = progress.dailyHistory[dayKey(daysAgo(3))]
        assertEquals(1, archived?.answered)
        // 中间两天没答题，不该出现 answered = 0 的空壳记录
        assertEquals(1, progress.dailyHistory.size)
    }

    @Test
    fun `超出保留天数的历史在换天时被裁掉`() {
        val tooOld = daysAgo(HISTORY_KEEP_DAYS + 1)
        val progress = StudyProgress()
            .withAnswer("apple", correct = true, nowMs = tooOld)
            .withAnswer("apple", correct = true, nowMs = now)

        assertTrue("早于窗口的记录必须清掉，否则存档无限膨胀", progress.dailyHistory.isEmpty())
    }

    @Test
    fun `保留天数以内的历史不会被裁掉`() {
        val recent = daysAgo(2)
        val progress = StudyProgress()
            .withAnswer("apple", correct = true, nowMs = recent)
            .withAnswer("apple", correct = true, nowMs = now)

        assertEquals(1, progress.dailyHistory[dayKey(recent)]?.answered)
    }

    @Test
    fun `答错留下欠账且清零连对`() {
        val progress = StudyProgress()
            .withAnswer("apple", correct = true, nowMs = now)
            .withAnswer("apple", correct = true, nowMs = now)
            .withAnswer("apple", correct = false, nowMs = now)

        val stat = progress.wordStats.getValue("apple")
        assertEquals(3, stat.seen)
        assertEquals(2, stat.correct)
        assertEquals("答错必须清零连对，否则错词会被误判为已掌握", 0, stat.streak)
        assertTrue(stat.debt)
    }

    @Test
    fun `答对销账`() {
        val progress = StudyProgress()
            .withAnswer("apple", correct = false, nowMs = now)
            .withAnswer("apple", correct = true, nowMs = now)

        val stat = progress.wordStats.getValue("apple")
        assertFalse(stat.debt)
        assertEquals(1, stat.streak)
    }

    @Test
    fun `迁移把词条 id 换成词面小写`() {
        val progress = StudyProgress(
            wordStats = mapOf("num_thirteen" to WordStat(seen = 3, correct = 1, debt = true)),
        )
        val migrated = progress.migrateStatKeys { id ->
            if (id == "num_thirteen") "thirteen" else null
        }

        assertEquals(3, migrated.wordStats["thirteen"]?.seen)
        assertTrue("旧键必须消失，否则同一个词会有两份记录", "num_thirteen" !in migrated.wordStats)
        assertEquals(StudyPreferences.STAT_KEY_VERSION_WORD, migrated.preferences.statKeyVersion)
    }

    @Test
    fun `已经迁移过就不再改动`() {
        val progress = StudyProgress(
            preferences = StudyPreferences(statKeyVersion = StudyPreferences.STAT_KEY_VERSION_WORD),
            wordStats = mapOf("Thirteen" to WordStat(seen = 1)),
        )
        val migrated = progress.migrateStatKeys { "should_not_be_used" }

        assertEquals(setOf("Thirteen"), migrated.wordStats.keys)
    }

    @Test
    fun `查不到的键退化为小写而不是丢数据`() {
        val progress = StudyProgress(
            wordStats = mapOf("Unknown_Word" to WordStat(seen = 2)),
        )
        val migrated = progress.migrateStatKeys { null }

        assertEquals(2, migrated.wordStats["unknown_word"]?.seen)
    }

    @Test
    fun `迁移不影响其它字段`() {
        val progress = StudyProgress(
            dailyStats = DailyStudyStat(date = "2026-11-04", answered = 5, correct = 4),
            pendingLock = PendingQuizLock(mediaId = "m1", createdAtMs = 123L),
            wordStats = mapOf("num_thirteen" to WordStat(seen = 1)),
        )
        val migrated = progress.migrateStatKeys { "thirteen" }

        assertEquals(5, migrated.dailyStats.answered)
        assertEquals("m1", migrated.pendingLock?.mediaId)
        assertEquals(1, migrated.wordStats.size)
    }
}
