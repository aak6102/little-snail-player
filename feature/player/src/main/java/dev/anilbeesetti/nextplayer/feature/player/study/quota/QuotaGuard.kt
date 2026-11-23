package dev.anilbeesetti.nextplayer.feature.player.study.quota

import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.data.repository.DailyQuotaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.DailyQuotaUsageReader
import dev.anilbeesetti.nextplayer.core.data.repository.LicenseRepository
import dev.anilbeesetti.nextplayer.core.model.DailyQuotaPreferences
import dev.anilbeesetti.nextplayer.core.model.StudyRewardProgress
import dev.anilbeesetti.nextplayer.core.model.StudyRewardRule
import dev.anilbeesetti.nextplayer.core.model.dayKey
import dev.anilbeesetti.nextplayer.core.model.lowBalanceStepFor
import dev.anilbeesetti.nextplayer.core.model.noticeBucketFor
import dev.anilbeesetti.nextplayer.core.model.startOfDayMs
import dev.anilbeesetti.nextplayer.core.model.studyRewardProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/**
 * 每日额度的状态机（第三层家长管控）。
 *
 * 与前两层分工：
 *  - [dev.anilbeesetti.nextplayer.feature.player.study.QuizGate]：看够 N 分钟要答题；
 *  - [dev.anilbeesetti.nextplayer.feature.player.study.RestGuard]：窗口内看太多要休息；
 *  - 本类：一天总共能看多久，到点**直接停**，不解锁、不协商。
 *
 * 三者的计时口径必须完全一致（都只算「真在播」的秒），否则同一段视频在三个页面
 * 会显示出三个数字，家长第一反应就是「这软件不准」。所以计时入口就是 PlayerService 里
 * 那一个 ticker，参数也和第两者一样是「这一秒算不算在看」。
 */
@Single
class QuotaGuard(
    private val quotaRepository: DailyQuotaRepository,
    private val usageReader: DailyQuotaUsageReader,
    private val licenseRepository: LicenseRepository,
    @Named(DiQualifiers.APPLICATION_SCOPE) private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(QuotaUiState())
    val state: StateFlow<QuotaUiState> = _state.asStateFlow()

    /** 内存中的今日累计。每秒都写 DataStore 太重，这里维护计数、定期落盘。 */
    private var watchedMs: Long = 0L

    /** 内存计数归属的日期键，用于发现跨零点。 */
    private var currentDayKey: String = ""

    /** 上次落盘时刻，用于把每秒累加节流成每 10 秒一次。 */
    private var lastPersistMs: Long = 0L

    /** 上次结算时刻。连续切前台时不该反复扫一天的事件流。 */
    private var lastSettleMs: Long = 0L

    /** 上次刷新「学习进度」的时刻，限制锁屏上的数字刷新频率。 */
    private var lastLearningRefreshMs: Long = 0L

    /** 本次锁屏是否已经强制结算过，避免 recompute ↔ settle 互相触发成死循环。 */
    private var settleForcedForLock: Boolean = false

    /** 已弹过的间隔档（内存镜像，落盘值在 progress.noticeBucket）。 */
    private var lastNoticeBucket: Int = 0

    /** 今日已提示过的低余额档位。 */
    private var lowBalanceNotified: Set<Int> = emptySet()

    /**
     * 上一次计算时的剩余分钟数（向上取整），用于判断「刚刚跨过某一档」。
     *
     * 只靠 `lowBalanceNotified` 去重是不够的：家长把基本时长设成 10 分钟时，
     * 孩子一开播剩余就正好是 10 分钟，会立刻弹一条「还剩 10 分钟」——
     * 信息没错，但刚打开就被提醒一次，孩子只会把它当噪音。
     * 真正要提醒的是从 11 分钟掉到 10 分钟那一刻。
     */
    private var lastRemainingMinutes: Int = Int.MAX_VALUE

    /** 提示序号，用于「延迟 3 秒清空」时判断是不是同一条提示。 */
    private var noticeSeq: Int = 0

    /** 是否已从存档恢复过。首次 tick 前必须恢复，否则会把昨天的累计当成今天。 */
    private var restored: Boolean = false

    /**
     * 额度这一层此刻是否真的在起作用：家长开着 **且** 本地授权有效。
     *
     * 与 [dev.anilbeesetti.nextplayer.feature.player.study.RestGuard] 同一判定：
     * 家长管控能力属于付费功能，判否时只是「不拦」，绝不弹付费墙——
     * 在播放中途弹付费墙是最招人烦的做法，也是审核最容易挑的地方。
     */
    private fun isEffective(): Boolean =
        licenseRepository.statusNow().pro && quotaRepository.preferences.enabled

    /**
     * 每秒由 PlayerService 调用。
     *
     * @param progressingMs 这一秒算不算在看：算则传入 1000，不算传 0。
     *   与答题锁同源，暂停、缓冲、后台都不计入。
     */
    fun onTick(progressingMs: Long) {
        if (!isEffective()) {
            if (_state.value.phase != QuotaPhase.IDLE) {
                _state.value = QuotaUiState()
            }
            // 关掉开关再打开时要从存档重新恢复，不能沿用关闭期间的内存计数。
            restored = false
            return
        }
        ensureRestored()

        val now = System.currentTimeMillis()
        rollDayIfNeeded(now)

        if (progressingMs > 0L) {
            watchedMs += progressingMs
        }
        recompute(now)
        persistMaybe(now)
    }

    /**
     * 结算奖励。调用点在「打开/回到播放器」「打开新视频」「答完题」三处。
     *
     * 结算不需要进程重启：`queryEvents` 读的是系统数据库里已经落盘的事件，
     * 孩子刚用完学习 App 的那一段在切走的瞬间就写进去了。这里只做一次查询，
     * 30 秒内重复调用直接被节流挡掉。
     */
    /**
     * 结算奖励。调用点在「打开/回到播放器」「打开新视频」「答完题」「回到列表页」四处。
     *
     * 结算不需要进程重启：系统已经把使用时长记进自己的账本里，孩子切走那一刻就记上了。
     * 这里只做一次查询，正常调用 30 秒内重复会被节流挡掉。
     *
     * @param force 跳过节流。用于「刚刚锁屏」这一刻——如果孩子其实已经学够了、
     *   只是还没结算，这一下就能直接把他放回来，而不是先让他看一秒锁屏。
     */
    fun settleIfDue(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSettleMs < SETTLE_THROTTLE_MS) return
        lastSettleMs = now

        // 这里刻意**不做**「配置是否打开」「授权是否有效」的前置判断。
        //
        // 两个状态都是 DataStore 驱动的 StateFlow / 内存快照，冷启动的那一瞬间还是默认值
        // （enabled = false、未授权）。而「孩子学完回来」恰恰最容易走冷启动这条路，
        // 前置判断一失败，整条结算就静默跳过——表现正是「学满了、时长一分不涨」。
        // 该不该发由仓库对着**磁盘上的真实配置**判断（见 LocalDailyQuotaRepository.settle），
        // 能不能用由 onTick 里的 isEffective() 判断。结算本身只是记账。
        scope.launch {
            val usage = withContext(Dispatchers.IO) {
                usageReader.foregroundDurations(startOfDayMs(now), now)
            }
            val granted = quotaRepository.settle(usage, now)
            if (granted.isNotEmpty()) {
                pushNotice(QuotaNoticeKind.REWARD, granted.values.sum())
            }
            // 结算可能带来了新的额度，立刻按新值重算一次，避免「到账了但还显示 0」。
            ensureRestored()
            recompute(System.currentTimeMillis())
            refreshLearning(force = true)
        }
    }

    /** 孩子点掉提示条。 */
    fun dismissNotice() {
        _state.update { it.copy(notice = null) }
    }

    /** 强制从存档重读（家长在设置页改完配置回到播放器时用）。 */
    fun reload() {
        restored = false
        ensureRestored()
        recompute(System.currentTimeMillis())
        refreshLearning(force = true)
    }

    /**
     * 刷新各方案今天的学习进度，供锁屏回答「已经用了多久、还差多久」。
     *
     * 只在三个时机调用：结算之后、额度耗尽期间每 30 秒一次、家长改完配置回来。
     * 不做每秒查询——读系统统计要过 Binder，每秒一次是纯白烧电。
     */
    private fun refreshLearning(force: Boolean = false) {
        val nowMs = System.currentTimeMillis()
        val prefs = quotaRepository.preferences
        if (!prefs.enabled || prefs.activeRules.isEmpty()) {
            _state.update { if (it.learningProgress.isEmpty()) it else it.copy(learningProgress = emptyList()) }
            return
        }
        if (!force && nowMs - lastLearningRefreshMs < LEARNING_REFRESH_MS) return
        lastLearningRefreshMs = nowMs
        scope.launch {
            val usage = withContext(Dispatchers.IO) {
                usageReader.foregroundDurations(startOfDayMs(nowMs), nowMs)
            }
            val list = studyRewardProgress(
                preferences = quotaRepository.preferences,
                usageMsByPackage = usage,
                granted = quotaRepository.progress.value.granted,
            )
            _state.update { it.copy(learningProgress = list) }
        }
    }

    /** 低余额/间隔提醒的去重与节流状态都在内存里，恢复时一并重建。 */
    private fun ensureRestored() {
        if (restored) return
        val progress = quotaRepository.progress.value
        val nowMs = System.currentTimeMillis()
        watchedMs = progress.watchedMs
        currentDayKey = progress.date
        // 恢复时把「已提醒档位」抬到当前进度所在档，避免一进播放器就弹一条
        // 「今天已看 12 分钟」——那条提示应该出现在真的跨档那一刻，不是每次开播。
        lastNoticeBucket = maxOf(
            progress.noticeBucket,
            noticeBucketFor(watchedMs, progress.preferences.noticeIntervalMinutesClamped),
        )
        lowBalanceNotified = progress.lowBalanceNotified
        // 恢复时把「上一档剩余」也抬到当前值，这样开播那一刻不算「跨档」，
        // 不会一进播放器就冒出「还剩 10 分钟」。
        lastRemainingMinutes = ceilMinutes((progress.totalMsAt(nowMs) - watchedMs).coerceAtLeast(0L))
        restored = true
    }

    /**
     * 跨零点处理。
     *
     * 内存计数必须在换天时归零，否则「昨晚看了 20 分钟」会继续压在今天头上，
     * 孩子一早打开就被锁。这里直接落盘一次，把新的一天确立下来。
     */
    private fun rollDayIfNeeded(nowMs: Long) {
        val today = dayKey(nowMs)
        if (currentDayKey == today) return
        // 时间被往回拨时不动（today < currentDayKey），沿用当前计数——
        // 与 DailyQuotaProgress.rollToDay 同一条规则，两处必须一致，
        // 否则会出现「存档说没换天、内存说换天了」的撕裂状态。
        if (currentDayKey.isNotEmpty() && today < currentDayKey) return
        watchedMs = 0L
        currentDayKey = today
        lastNoticeBucket = 0
        lowBalanceNotified = emptySet()
        persistNow(nowMs)
    }

    /** 剩余毫秒折成「还剩几分钟」，向上取整——与提示文案、设置页的口径保持一致。 */
    private fun ceilMinutes(ms: Long): Int = ((ms + 59_999L) / 60_000L).toInt()

    private fun recompute(nowMs: Long) {
        val progress = quotaRepository.progress.value
        val prefs = progress.preferences
        val grantedMinutes = progress.grantedMinutes
        val totalMinutes = prefs.totalMinutesFor(grantedMinutes, nowMs)
        val totalMs = totalMinutes * 60_000L
        val remainingMs = (totalMs - watchedMs).coerceAtLeast(0L)
        val exhausted = watchedMs >= totalMs
        val phase = if (exhausted) QuotaPhase.EXHAUSTED else QuotaPhase.ACTIVE

        val prev = _state.value
        val next = prev.copy(
            phase = phase,
            watchedMinutes = (watchedMs / 60_000L).toInt(),
            remainingMinutes = ((remainingMs + 59_999L) / 60_000L).toInt(),
            totalMinutes = totalMinutes,
            grantedMinutes = grantedMinutes,
            pendingRules = if (exhausted) progress.pendingRules() else emptyList(),
        )
        if (next != prev) _state.value = next

        if (!exhausted) {
            settleForcedForLock = false
            checkNotice(prefs, nowMs)
        } else {
            // 刚锁上的那一刻强制结算一次：孩子可能已经学够、只是还没结算，
            // 这一下能直接放他回来，而不是先给一块锁屏再自己消失。
            if (!settleForcedForLock) {
                settleForcedForLock = true
                settleIfDue(force = true)
            } else {
                // 系统统计有几十秒的落盘延迟：孩子刚学完就切回来时，第一次结算可能还读不到
                // 最后那几分钟。锁着的时候每 30 秒再试一次，等数据落盘就能自动放行，
                // 不必让孩子再退出重进一次。
                settleIfDue()
            }
            // 被锁期间也要让「已用 / 还差」动起来：孩子可能就停在锁屏上去学习，
            // 回来一眼就能看到进度涨了，不必再点一次播放。
            refreshLearning()
        }
    }

    /**
     * 提醒判定。两套规则并行：
     *  - 间隔档：累计观看每跨过 N 分钟弹一次，`N` 由家长配置；
     *  - 低余额：剩 10 / 5 / 1 分钟各弹一次，不受间隔影响——这三档是「快没得看了」
     *    的唯一预告，比任何固定频率都重要。
     */
    private fun checkNotice(prefs: DailyQuotaPreferences, nowMs: Long) {
        val progress = quotaRepository.progress.value
        val remainingMs = (prefs.totalMinutesFor(progress.grantedMinutes, nowMs) * 60_000L - watchedMs)
            .coerceAtLeast(0L)
        val watchedMinutes = (watchedMs / 60_000L).toInt()
        val remainingMinutes = ((remainingMs + 59_999L) / 60_000L).toInt()

        val lowStep = lowBalanceStepFor(remainingMs, prefs)
        // 「刚刚跨过」才提：开播时就正好停在这一档不当成跨档（见 lastRemainingMinutes）。
        val crossed = remainingMinutes < lastRemainingMinutes
        lastRemainingMinutes = remainingMinutes
        if (lowStep != null && lowStep !in lowBalanceNotified && crossed) {
            lowBalanceNotified = lowBalanceNotified + lowStep
            pushNotice(
                kind = if (lowStep <= 1) QuotaNoticeKind.LAST_MINUTE else QuotaNoticeKind.LOW_BALANCE,
                minutes = lowStep,
                watchedMinutes = watchedMinutes,
                remainingMinutes = remainingMinutes,
            )
            persistNotice(nowMs)
            return
        }

        val bucket = noticeBucketFor(watchedMs, prefs.noticeIntervalMinutesClamped)
        if (bucket > lastNoticeBucket) {
            lastNoticeBucket = bucket
            pushNotice(
                kind = QuotaNoticeKind.INTERVAL,
                minutes = prefs.noticeIntervalMinutesClamped,
                watchedMinutes = watchedMinutes,
                remainingMinutes = remainingMinutes,
            )
            persistNotice(nowMs)
        }
    }

    private fun pushNotice(
        kind: QuotaNoticeKind,
        minutes: Int,
        watchedMinutes: Int = 0,
        remainingMinutes: Int = 0,
    ) {
        val id = ++noticeSeq
        _state.update {
            it.copy(
                notice = QuotaNotice(
                    id = id,
                    kind = kind,
                    minutes = minutes,
                    watchedMinutes = watchedMinutes,
                    remainingMinutes = remainingMinutes,
                ),
            )
        }
        scope.launch {
            delay(NOTICE_DURATION_MS)
            _state.update { if (it.notice?.id == id) it.copy(notice = null) else it }
        }
    }

    /** 每 10 秒落盘一次；进入「用完」状态时立即落盘，避免强杀重进又白拿一分钟。 */
    private fun persistMaybe(nowMs: Long) {
        val due = nowMs - lastPersistMs >= PERSIST_INTERVAL_MS
        if (!due && _state.value.phase != QuotaPhase.EXHAUSTED) return
        persistNow(nowMs)
    }

    private fun persistNow(nowMs: Long) {
        lastPersistMs = nowMs
        val snapshot = watchedMs
        scope.launch { quotaRepository.saveWatched(snapshot, nowMs) }
    }

    private fun persistNotice(nowMs: Long) {
        val bucket = lastNoticeBucket
        val low = lowBalanceNotified
        scope.launch { quotaRepository.markNotice(bucket, low) }
    }

    companion object {
        /** 正常推进每 10 秒落盘一次。比防沉迷的 30 秒更密：额度是整数分钟，丢 30 秒可能少扣半分钟。 */
        private const val PERSIST_INTERVAL_MS = 10_000L

        /** 结算节流。孩子反复开关播放器时不该每次都扫一天的事件流。 */
        private const val SETTLE_THROTTLE_MS = 30_000L

        /** 锁屏上「学习进度」的刷新间隔。30 秒足够看出进展，又不至于频繁过 Binder。 */
        private const val LEARNING_REFRESH_MS = 30_000L

        /** 提示条停留时长。够看清一句话，又不至于挡住画面。 */
        private const val NOTICE_DURATION_MS = 3_000L
    }
}

enum class QuotaPhase { IDLE, ACTIVE, EXHAUSTED }

enum class QuotaNoticeKind {
    /** 到点报数：已看 N 分钟、还剩多少。 */
    INTERVAL,

    /** 剩余 10 / 5 分钟。 */
    LOW_BALANCE,

    /** 剩余 1 分钟，到点会自动暂停。 */
    LAST_MINUTE,

    /** 学习奖励到账。 */
    REWARD,
}

data class QuotaNotice(
    val id: Int,
    val kind: QuotaNoticeKind,
    /** 语义随 [kind] 变：INTERVAL 是间隔分钟数，LOW_BALANCE/LAST_MINUTE 是剩余分钟，REWARD 是到账分钟。 */
    val minutes: Int,
    /** 触发那一刻的累计观看分钟数（快照）。 */
    val watchedMinutes: Int = 0,
    /** 触发那一刻的剩余分钟数（快照）。不复用实时值，否则提示条上的数字会随秒跳动。 */
    val remainingMinutes: Int = 0,
)

data class QuotaUiState(
    val phase: QuotaPhase = QuotaPhase.IDLE,
    val watchedMinutes: Int = 0,
    val remainingMinutes: Int = 0,
    val totalMinutes: Int = 0,
    val grantedMinutes: Int = 0,
    /** 额度用完时还没达标的规则，用于锁屏上告诉孩子「去学什么还能再换多少」。 */
    val pendingRules: List<StudyRewardRule> = emptyList(),
    /** 各方案今天的学习进度（已用 / 还差）。锁屏直接读它，不再各自去查系统。 */
    val learningProgress: List<StudyRewardProgress> = emptyList(),
    val notice: QuotaNotice? = null,
) {
    /** 取某条方案的学习进度；没查到数据时返回 null（不编造 0 分钟）。 */
    fun progressFor(ruleId: String): StudyRewardProgress? =
        learningProgress.firstOrNull { it.ruleId == ruleId }
}
