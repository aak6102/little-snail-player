package dev.anilbeesetti.nextplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每日额度的判定必须钉死，它决定孩子今天还能不能看。
 *
 * 这里锁住五件事：事件流要能折成正确的前台区间（未闭合、重复 RESUMED 都不能算错）；
 * 分屏时学习时长必须扣掉与播放器重叠的部分；奖励发放要幂等且受总量封顶；
 * 换天只在日期往前走时清空；系统时间往回拨不能白拿额度。
 */
class DailyQuotaTest {

    private fun at(minute: Int): Long = minute * 60_000L

    /** 固定日期的时间戳，避免用例随「今天星期几」飘。2026-11-17 是周二。 */
    private fun dateAt(year: Int, month: Int, day: Int, hour: Int = 12): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.clear()
        calendar.set(year, month - 1, day, hour, 0, 0)
        return calendar.timeInMillis
    }

    private val weekday: Long = dateAt(2026, 11, 17)
    private val weekend: Long = dateAt(2026, 11, 21)

    private val self = "dev.anilbeesetti.nextplayer"

    private fun rule(
        id: String,
        pkg: String = "com.learn.a",
        required: Int = 30,
        reward: Int = 15,
    ) = StudyRewardRule(
        id = id,
        packageName = pkg,
        appLabel = "学习 A",
        requiredMinutes = required,
        rewardMinutes = reward,
    )

    @Test
    fun `未闭合区间以当前时刻收尾`() {
        val events = listOf(
            UsageEvent("com.learn.a", UsageEventType.FOREGROUND, at(10)),
        )
        val intervals = toUsageIntervals(events, nowMs = at(40))
        assertEquals(1, intervals.size)
        assertEquals(at(10), intervals[0].startMs)
        assertEquals(at(40), intervals[0].endMs)
        assertEquals(30 * 60_000L, intervals[0].durationMs)
    }

    @Test
    fun `重复的前台事件只认第一次`() {
        val events = listOf(
            UsageEvent("com.learn.a", UsageEventType.FOREGROUND, at(10)),
            UsageEvent("com.learn.a", UsageEventType.FOREGROUND, at(12)),
            UsageEvent("com.learn.a", UsageEventType.BACKGROUND, at(20)),
        )
        val intervals = toUsageIntervals(events, nowMs = at(30))
        assertEquals(1, intervals.size)
        assertEquals(10 * 60_000L, intervals[0].durationMs)
    }

    @Test
    fun `没有起点的离开事件被丢弃`() {
        val events = listOf(
            UsageEvent("com.learn.a", UsageEventType.BACKGROUND, at(10)),
        )
        assertTrue(toUsageIntervals(events, nowMs = at(30)).isEmpty())
    }

    @Test
    fun `分屏时学习时长扣掉与播放器重叠的部分`() {
        val intervals = listOf(
            // 学习 App：10 → 40 分钟
            UsageInterval("com.learn.a", at(10), at(40)),
            // 播放器自己也在这段时间开着（15 → 35），重叠 20 分钟
            UsageInterval(self, at(15), at(35)),
        )
        val perPackage = exclusiveForegroundDurations(intervals, setOf(self))
        // 30 分钟里只有 10 分钟是独占的
        assertEquals(10 * 60_000L, perPackage["com.learn.a"])
        // 自己不计入结果
        assertFalse(perPackage.containsKey(self))
    }

    @Test
    fun `完全重叠的学习时长归零`() {
        val intervals = listOf(
            UsageInterval("com.learn.a", at(10), at(40)),
            UsageInterval(self, at(10), at(40)),
        )
        assertEquals(0L, exclusiveForegroundDurations(intervals, setOf(self))["com.learn.a"])
    }

    @Test
    fun `不重叠时按原样计入`() {
        val intervals = listOf(
            UsageInterval("com.learn.a", at(0), at(30)),
            UsageInterval(self, at(40), at(60)),
        )
        assertEquals(
            30 * 60_000L,
            exclusiveForegroundDurations(intervals, setOf(self))["com.learn.a"],
        )
    }

    @Test
    fun `同一 App 两条阶梯都达标时累加`() {
        val prefs = DailyQuotaPreferences(
            rewardRules = listOf(
                rule("a30", required = 30, reward = 15),
                rule("a60", required = 60, reward = 30),
            ),
        )
        val grants = grantEligibleRules(prefs, mapOf("com.learn.a" to at(65)), emptyMap())
        assertEquals(mapOf("a30" to 15, "a60" to 30), grants)
        // 基本 20 + 奖励 45 = 65 分钟
        assertEquals(20 + 45, prefs.totalMinutesFor(grants.values.sum(), weekday))
    }

    @Test
    fun `未达标不发`() {
        val prefs = DailyQuotaPreferences(rewardRules = listOf(rule("a30", required = 30)))
        assertTrue(grantEligibleRules(prefs, mapOf("com.learn.a" to at(29)), emptyMap()).isEmpty())
    }

    @Test
    fun `已发放的规则不会重复发`() {
        val prefs = DailyQuotaPreferences(rewardRules = listOf(rule("a30", required = 30)))
        val first = grantEligibleRules(prefs, mapOf("com.learn.a" to at(35)), emptyMap())
        val second = grantEligibleRules(prefs, mapOf("com.learn.a" to at(40)), first)
        assertEquals(mapOf("a30" to 15), first)
        assertTrue(second.isEmpty())
    }

    @Test
    fun `奖励被单日上限截断`() {
        val prefs = DailyQuotaPreferences(
            maxRewardMinutes = 20,
            rewardRules = listOf(
                rule("a", pkg = "com.learn.a", required = 10, reward = 15),
                rule("b", pkg = "com.learn.b", required = 10, reward = 15),
            ),
        )
        val usage = mapOf("com.learn.a" to at(30), "com.learn.b" to at(30))
        val grants = grantEligibleRules(prefs, usage, emptyMap())
        // 第一条拿满 15，第二条只剩 5 的额度
        assertEquals(mapOf("a" to 15, "b" to 5), grants)
        assertEquals(20, grants.values.sum())
    }

    @Test
    fun `停用的规则不参与判定`() {
        val prefs = DailyQuotaPreferences(
            rewardRules = listOf(rule("a30").copy(enabled = false)),
        )
        assertTrue(grantEligibleRules(prefs, mapOf("com.learn.a" to at(60)), emptyMap()).isEmpty())
    }

    @Test
    fun `换天清空当日状态但保留配置`() {
        val prefs = DailyQuotaPreferences(baseMinutes = 20, rewardRules = listOf(rule("a30")))
        val yesterday = DailyQuotaProgress(
            preferences = prefs,
            date = "2026-11-16",
            watchedMs = at(20),
            granted = mapOf("a30" to 15),
            noticeBucket = 3,
            lowBalanceNotified = setOf(10, 5),
            maxSeenDayKey = "2026-11-16",
        )
        val today = yesterday.rollToDay("2026-11-17")
        assertEquals("2026-11-17", today.date)
        assertEquals(0L, today.watchedMs)
        assertTrue(today.granted.isEmpty())
        assertEquals(0, today.noticeBucket)
        assertTrue(today.lowBalanceNotified.isEmpty())
        assertEquals(prefs, today.preferences)
        assertEquals("2026-11-17", today.maxSeenDayKey)
    }

    @Test
    fun `系统时间往回拨不重置也不发额度`() {
        val progress = DailyQuotaProgress(
            preferences = DailyQuotaPreferences(enabled = true),
            date = "2026-11-17",
            watchedMs = at(20),
            maxSeenDayKey = "2026-11-17",
        )
        val rolled = progress.rollToDay("2026-11-16")
        assertEquals("2026-11-17", rolled.date)
        assertEquals(at(20), rolled.watchedMs)
        assertTrue(rolled.isExhaustedAt(weekday))
    }

    @Test
    fun `跨零点累加落到新的一天`() {
        // 23:59 的进度，到 00:01 再记一秒，应当先换天再累加
        val progress = DailyQuotaProgress(
            preferences = DailyQuotaPreferences(enabled = true),
            date = "2026-11-16",
            watchedMs = at(20),
            maxSeenDayKey = "2026-11-16",
        )
        val next = progress.withWatched(1_000L, "2026-11-17")
        assertEquals("2026-11-17", next.date)
        assertEquals(1_000L, next.watchedMs)
        assertFalse(next.isExhaustedAt(weekday))
    }

    @Test
    fun `剩余分钟向上取整`() {
        val progress = DailyQuotaProgress(
            preferences = DailyQuotaPreferences(enabled = true, baseMinutes = 20),
            date = "2026-11-17",
            watchedMs = at(19) + 30_000L,
            maxSeenDayKey = "2026-11-17",
        )
        // 还剩 30 秒，显示「1 分钟」而不是「0 分钟」
        assertEquals(1, progress.remainingMinutesAt(weekday))
        assertFalse(progress.isExhaustedAt(weekday))
    }

    @Test
    fun `提醒档位按累计观看整除`() {
        assertEquals(0, noticeBucketFor(0, 5))
        assertEquals(0, noticeBucketFor(at(4), 5))
        assertEquals(1, noticeBucketFor(at(5), 5))
        assertEquals(3, noticeBucketFor(at(17), 5))
        // 关闭提醒时永远是 0，调用方据此跳过
        assertEquals(0, noticeBucketFor(at(60), 0))
    }

    @Test
    fun `低余额档位在整分钟处命中`() {
        val prefs = DailyQuotaPreferences()
        assertEquals(10, lowBalanceStepFor(at(10), prefs))
        assertEquals(5, lowBalanceStepFor(at(5) - 1_000L, prefs))
        assertEquals(1, lowBalanceStepFor(at(1) - 500L, prefs))
        assertEquals(null, lowBalanceStepFor(at(9), prefs))
        // 关掉低余额提醒后一律不命中
        assertEquals(null, lowBalanceStepFor(at(5), prefs.copy(lowBalanceWarn = false)))
    }

    @Test
    fun `剩余一分四十九秒不算剩一分钟`() {
        // 实机回归：剩余 1:49 时曾判成「剩 1 分钟」并弹出「到点会自动暂停」，
        // 提示比事实提前了将近一分钟。档位必须按向上取整判定。
        val prefs = DailyQuotaPreferences()
        assertEquals(null, lowBalanceStepFor(at(1) + 49_000L, prefs))
        assertEquals(null, lowBalanceStepFor(at(1) + 1_000L, prefs))
        // 刚好剩 1 分钟才报
        assertEquals(1, lowBalanceStepFor(at(1), prefs))
        // 已经用完就不再提示，交给锁屏
        assertEquals(null, lowBalanceStepFor(0L, prefs))
    }

    @Test
    fun `默认基本时长是 20 分钟`() {
        val prefs = DailyQuotaPreferences()
        assertEquals(20, prefs.baseMinutesClamped)
        assertEquals(40, prefs.weekendBaseMinutesClamped)
        assertEquals(5, prefs.noticeIntervalMinutesClamped)
        assertEquals(120, prefs.maxRewardMinutesClamped)
        assertFalse(prefs.enabled)
    }

    @Test
    fun `周末按周末那档算基本时长`() {
        val prefs = DailyQuotaPreferences(baseMinutes = 20, weekendBaseMinutes = 45)
        assertEquals(20, prefs.totalMinutesFor(0, weekday))
        assertEquals(45, prefs.totalMinutesFor(0, weekend))
        // 奖励部分与当天的档位叠加，不受档位切换影响
        assertEquals(45 + 15, prefs.totalMinutesFor(15, weekend))
        assertEquals(20 + 15, prefs.totalMinutesFor(15, weekday))
    }

    @Test
    fun `周末判定只认周六周日`() {
        assertFalse(isWeekendDay(weekday))
        assertTrue(isWeekendDay(weekend))
        assertTrue(isWeekendDay(dateAt(2026, 11, 22)))
    }

    @Test
    fun `达标时长下限是 1 分钟`() {
        val rule = StudyRewardRule(
            id = "a1",
            packageName = "com.learn.a",
            appLabel = "学习 A",
            requiredMinutes = 0,
            rewardMinutes = 5,
        )
        assertEquals(1, rule.requiredMinutesClamped)
        val prefs = DailyQuotaPreferences(enabled = true, rewardRules = listOf(rule))
        // 学满 1 分钟就发，这正是「低龄孩子 3 分钟一节小课」能配出方案的前提
        assertEquals(mapOf("a1" to 5), grantEligibleRules(prefs, mapOf("com.learn.a" to at(1)), emptyMap()))
        // 差 1 秒都不发
        assertTrue(
            grantEligibleRules(prefs, mapOf("com.learn.a" to at(1) - 1_000L), emptyMap()).isEmpty(),
        )
    }

    @Test
    fun `学习进度给出已用分钟与还差多少`() {
        val rule = rule("a30", required = 30, reward = 15)
        val prefs = DailyQuotaPreferences(rewardRules = listOf(rule))
        val progress = studyRewardProgress(
            preferences = prefs,
            usageMsByPackage = mapOf("com.learn.a" to at(12) + 40_000L),
            granted = emptyMap(),
        )
        assertEquals(1, progress.size)
        // 已用分钟向下取整：12 分 40 秒显示 12 分钟，与「再学 18 分钟就到账」一致
        assertEquals(12, progress.first().usedMinutes)
        assertEquals(18, progress.first().missingMinutes)
        assertFalse(progress.first().granted)
    }

    @Test
    fun `未达标的规则在锁屏上显示还差多少`() {
        val rule = rule("a30", required = 30, reward = 15)
        val prefs = DailyQuotaPreferences(rewardRules = listOf(rule))
        val granted = studyRewardProgress(prefs, mapOf("com.learn.a" to at(40)), mapOf("a30" to 15))
        assertTrue(granted.first().granted)
        assertEquals(0, granted.first().missingMinutes)
    }
}
