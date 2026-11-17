package dev.anilbeesetti.nextplayer.core.data.repository

import dev.anilbeesetti.nextplayer.core.model.DailyQuotaPreferences
import dev.anilbeesetti.nextplayer.core.model.DailyQuotaProgress
import kotlinx.coroutines.flow.StateFlow

/**
 * 每日观看额度的读写。
 *
 * 与 [StudyRepository]、[RestRepository] 并列的第三套家长管控状态，但职责更接近记账：
 * 家长配好规则之后，这里每天要回答两个问题——「今天还能看多久」和「今天该发多少奖励」。
 *
 * 状态必须落盘：额度是本 App 唯一能拦住孩子的总量约束，进程重启就归零等于没有。
 */
interface DailyQuotaRepository {

    val progress: StateFlow<DailyQuotaProgress>

    val preferences: DailyQuotaPreferences
        get() = progress.value.preferences

    suspend fun updatePreferences(transform: (DailyQuotaPreferences) -> DailyQuotaPreferences)

    /**
     * 把今日累计观看时长写成绝对值。
     *
     * 传绝对值而不是增量：调用方（每秒 tick 的守卫）自己维护内存计数并定期落盘，
     * 用绝对值可以容忍重复写、乱序写，也不会因为一次写入失败就永久少算。
     */
    suspend fun saveWatched(watchedMs: Long, nowMs: Long)

    /**
     * 结算奖励。幂等——同一天连续调用多次不会重复发放。
     *
     * @param usageMsByPackage 今日各学习 App 的独占前台时长（已扣除与播放器重叠的部分）。
     * @return 本次新发放的 ruleId → 分钟数；没有新发放时为空表。
     */
    suspend fun settle(
        usageMsByPackage: Map<String, Long>,
        nowMs: Long,
    ): Map<String, Int>

    /** 记下已经提醒过的档位，避免同一档反复弹。 */
    suspend fun markNotice(noticeBucket: Int, lowBalanceNotified: Set<Int>)
}
