package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

/**
 * 防沉迷（休息）偏好：限制「连续不休息」地看视频。
 *
 * 与 [StudyPreferences] 解耦但同属家长管控：它管「看多久必须答一道题（学英语）」，
 * 这里管「看多久必须真的休息一会（护眼/防沉迷）」。两套目标不同，分开放置，
 * 关闭口子都只在家长设置页（4 位 PIN），不污染彼此。
 *
 * 核心规则（滑动窗口）：在任意 [windowMinutes] 分钟窗口内，不看视频的时长必须 ≥
 * [requiredRestMinutes] 分钟；一旦越界就进入「疯狂答题」——复用答题锁，但切到
 * [crazyIntervalMinutes] 分钟一轮的紧凑节奏（题量/难度跟随学习答题），且答完不进结果页、直接续播再拦。
 * 唯一退出办法是真歇够 [requiredRestMinutes] 分钟，让窗口里的看视频时长回落到阈值以下。
 */
@Serializable
data class RestPreferences(
    /** 是否开启防沉迷。默认关闭，需家长在设置里手动打开。 */
    val enabled: Boolean = false,
    /** 滑动窗口长度（分钟）。 */
    val windowMinutes: Int = DEFAULT_WINDOW_MINUTES,
    /** 窗口内要求的最少「不看视频」时长（分钟）。 */
    val requiredRestMinutes: Int = DEFAULT_REST_MINUTES,
    /** 疯狂模式下两次答题的间隔（分钟），即「每 N 分钟一轮」。 */
    val crazyIntervalMinutes: Int = DEFAULT_CRAZY_INTERVAL_MINUTES,
    /** 进入疯狂模式前多少秒弹出预警浮层。 */
    val preWarnSeconds: Int = DEFAULT_PREWARN_SECONDS,
) {

    /** 滑动窗口秒数。 */
    val windowSeconds: Int get() = windowMinutesClamped * 60

    /** 窗口内要求的最少休息秒数（已夹到窗口以内）。 */
    val requiredRestSeconds: Int
        get() = requiredRestMinutesClamped * 60

    /**
     * 触发「疯狂答题」的看视频时长阈值（毫秒）。
     *
     * 等于 `窗口 − 要求休息`：看视频一旦超过这个值，窗口内的休息就不够了。
     * 例如 40 分钟窗口、要求休息 10 分钟 → 看满 30 分钟触发。
     */
    val thresholdWatchMs: Long
        get() = ((windowSeconds - requiredRestSeconds).coerceAtLeast(0) * 1000L)

    /** 进入疯狂前多少毫秒开始预警。 */
    val preWarnMs: Long get() = preWarnSecondsClamped * 1000L

    /** 疯狂模式每轮解锁的毫秒数（即两次答题之间的可看时长）。 */
    val crazyIntervalMillis: Long get() = crazyIntervalMinutesClamped * 60_000L

    val windowMinutesClamped: Int
        get() = windowMinutes.coerceIn(MIN_WINDOW_MINUTES, MAX_WINDOW_MINUTES)

    /** 要求休息必须严格小于窗口，否则阈值会 ≤ 0（看一眼就触发）。 */
    val requiredRestMinutesClamped: Int
        get() = requiredRestMinutes.coerceIn(
            MIN_REST_MINUTES,
            (windowMinutesClamped - 1).coerceAtLeast(MIN_REST_MINUTES),
        )

    val crazyIntervalMinutesClamped: Int
        get() = crazyIntervalMinutes.coerceIn(MIN_CRAZY_INTERVAL_MINUTES, MAX_CRAZY_INTERVAL_MINUTES)

    val preWarnSecondsClamped: Int
        get() = preWarnSeconds.coerceIn(MIN_PREWARN_SECONDS, MAX_PREWARN_SECONDS)

    companion object {
        const val DEFAULT_WINDOW_MINUTES = 40
        const val DEFAULT_REST_MINUTES = 10
        const val DEFAULT_CRAZY_INTERVAL_MINUTES = 1
        const val DEFAULT_PREWARN_SECONDS = 30

        // 下限放宽到 2 / 1：方便调试时把窗口压到极短、立刻看到疯狂模式触发，
        // 不必真等 40 分钟。上限仍封顶防手滑（9999 之类的输入）。
        const val MIN_WINDOW_MINUTES = 2
        const val MAX_WINDOW_MINUTES = 120
        const val MIN_REST_MINUTES = 1
        const val MAX_REST_MINUTES = 60

        /** 节奏下限 1 分钟：和答题锁的最小间隔一致，再密就变成纯骚扰。 */
        const val MIN_CRAZY_INTERVAL_MINUTES = 1
        const val MAX_CRAZY_INTERVAL_MINUTES = 5
        const val MIN_PREWARN_SECONDS = 15
        const val MAX_PREWARN_SECONDS = 60

        // 预设里带上 2 / 5 / 10 等小值，调试时直接点选即可，不必每次都进自定义输入框。
        val ALLOWED_WINDOWS = listOf(2, 5, 10, 30, 40, 60)
        val ALLOWED_RESTS = listOf(1, 5, 10, 15, 20, 30)
        val ALLOWED_CRAZY_INTERVALS = listOf(1, 2, 3, 5)
    }
}
