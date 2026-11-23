package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

/**
 * 每日额度的全部持久化状态，存放在独立的 `quota.json` 中。
 *
 * 单独一个文件而不是塞进 `study.json`：这三套家长管控（答题 / 休息 / 每日额度）
 * 各自有独立的存档寿命——家长想重置额度时不该把答题进度一起清掉。
 * 与 [RestProgress] 同一个理由。
 *
 * 配置（[preferences]）与当日状态放在一起，因为额度有一半是**当天生成**的
 * （奖励什么时候发、发给了哪条规则），脱离运行时上下文单独看配置是没有意义的。
 */
@Serializable
data class DailyQuotaProgress(
    val preferences: DailyQuotaPreferences = DailyQuotaPreferences(),
    /** 归属日期，yyyy-MM-dd。与 [StudyProgress.dailyStats] 同一套 [dayKey] 口径。 */
    val date: String = "",
    /** 今日有效播放累计毫秒。口径与答题锁一致：只有「真在播」的秒才计入。 */
    val watchedMs: Long = 0L,
    /**
     * ruleId → 已发放分钟。
     *
     * 发放是**一次性事件**，所以这里存快照而不是每次从使用时长重算：
     * 重算会在「卸载了学习 App」「关了使用情况权限」时把已经到手的时长收走，
     * 那是最伤信任的一种行为——孩子按规则学了，回头却被告知不算。
     */
    val granted: Map<String, Int> = emptyMap(),
    /** ruleId → 发放时刻。播放器据此弹「刚获得 +15 分钟」，只显示两分钟内的新鲜记录。 */
    val grantedAtMs: Map<String, Long> = emptyMap(),
    /** 已提醒到第几个间隔档（第 n 档 = 累计观看 n × 间隔分钟）。跨档才弹，同档不重复。 */
    val noticeBucket: Int = 0,
    /** 今日已提示过的低余额档位（10 / 5 / 1 分钟）。落盘是为了跨播放会话也只提示一次。 */
    val lowBalanceNotified: Set<Int> = emptySet(),
    /** 上次结算时刻，用于节流——连续切前台不该反复扫事件流。 */
    val lastSettledAtMs: Long = 0L,
    /**
     * 见过的最晚日期键，只增不减。
     *
     * 用来识别系统时间回拨：孩子把日期改回昨天，若按 [date] 直接比较就会白拿一份新额度。
     * 有这行之后，往回拨不重置、不发放，进度原地保留。
     */
    val maxSeenDayKey: String = "",
) {

    /** 已发放的奖励总分钟数（未截断，截断在 [totalMsAt] 里做）。 */
    val grantedMinutes: Int
        get() = granted.values.sum()

    /**
     * 今日总上限（毫秒）：当天档位的基本时长 + 奖励，奖励部分受 `maxRewardMinutes` 截断。
     *
     * 必须带时刻：基本时长有周内/周末两档，脱离「今天几号」算不出正确上限。
     */
    fun totalMsAt(nowMs: Long): Long =
        preferences.totalMinutesFor(grantedMinutes, nowMs) * 60_000L

    /** 今日剩余（毫秒），不为负。 */
    fun remainingMsAt(nowMs: Long): Long =
        (totalMsAt(nowMs) - watchedMs).coerceAtLeast(0L)

    /** 今日已看的整分钟数（向下取整，用于「已看 25 分钟」这种展示）。 */
    val watchedMinutes: Int
        get() = (watchedMs / 60_000L).toInt()

    /**
     * 今日剩余的整分钟数，**向上取整**。
     *
     * 向上取整而不是向下：剩 30 秒时显示「还剩 1 分钟」比「还剩 0 分钟」诚实，
     * 后者会让家长以为已经被锁了，实际还能看完这半分钟。
     */
    fun remainingMinutesAt(nowMs: Long): Int =
        ((remainingMsAt(nowMs) + 59_999L) / 60_000L).toInt()

    val isEnabled: Boolean
        get() = preferences.enabled

    /** 是否已耗尽。只有开关打开时才成立——关掉开关不该因为旧数据把播放器锁住。 */
    fun isExhaustedAt(nowMs: Long): Boolean =
        isEnabled && watchedMs >= totalMsAt(nowMs)

    /** 还没达标、也没发过的规则，用于锁屏上提示「去学什么还能再换多少」。 */
    fun pendingRules(): List<StudyRewardRule> =
        preferences.activeRules.filter { it.id !in granted }
}

/**
 * 换天处理。
 *
 * 三种情况必须分开：
 *  1. 同一天 → 什么都不做（只顺带把 `maxSeenDayKey` 抬到不小于今天）；
 *  2. 正常往前走 → 清空当日状态，保留配置；
 *  3. 日期比见过的最大值还小（系统时间被回拨）→ **原地不动**，不重置也不发放。
 *
 * 第 3 条是这套机制里唯一能防「改时间白拿额度」的地方，写错就等于没做。
 */
fun DailyQuotaProgress.rollToDay(todayKey: String): DailyQuotaProgress {
    val seen = maxOf(maxSeenDayKey, date)
    if (date == todayKey) {
        return if (seen == maxSeenDayKey) this else copy(maxSeenDayKey = seen)
    }
    if (todayKey < seen) return this
    return DailyQuotaProgress(
        preferences = preferences,
        date = todayKey,
        maxSeenDayKey = maxOf(seen, todayKey),
    )
}

/** 累加有效播放时长（先换天，再累加）。[deltaMs] 为 0 时只做换天。 */
fun DailyQuotaProgress.withWatched(deltaMs: Long, todayKey: String): DailyQuotaProgress {
    val rolled = rollToDay(todayKey)
    if (deltaMs <= 0L) return rolled
    return rolled.copy(watchedMs = rolled.watchedMs + deltaMs)
}

/**
 * 记入新发放的奖励。
 *
 * 只接受**新增**的 ruleId：调用方（[dev.anilbeesetti.nextplayer.core.model.grantEligibleRules]）
 * 已经排除过重复发放，这里再兜一层，避免时钟抖动导致的重复调用把时长翻倍。
 */
fun DailyQuotaProgress.withGrants(newGrants: Map<String, Int>, nowMs: Long): DailyQuotaProgress {
    if (newGrants.isEmpty()) return this
    val fresh = newGrants.filterKeys { it !in granted }
    if (fresh.isEmpty()) return this
    return copy(
        granted = granted + fresh,
        grantedAtMs = grantedAtMs + fresh.keys.associateWith { nowMs },
    )
}
