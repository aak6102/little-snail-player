package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

/**
 * 每日观看额度的家长配置。
 *
 * 这是继 [StudyPreferences]（看多久要答题）、[RestPreferences]（连续看多久要休息）
 * 之后的第三层家长管控：管「一天总共能看多久」。
 *
 * 与前两者最大的不同是**它有外部输入**：额度不是家长一个人说了算，还有一部分由
 * 「孩子今天在别的学习 App 上花了多少时间」决定。因此这套配置里除了几个数字，
 * 还带一张 [rewardRules] 规则表，每条规则描述一个学习 App 的兑换比例。
 *
 * 默认 `enabled = false`：老用户升级后不该因为一个没配过的功能突然被限时。
 */
@Serializable
data class DailyQuotaPreferences(
    /** 总开关。关掉等于这一层不存在，已发放的奖励保留但不生效。 */
    val enabled: Boolean = false,
    /**
     * 基本时长（分钟）——不看任何学习 App 也能拿到的那部分。
     *
     * 默认 20 分钟：一集动画片左右，够「看一集」这个最基本的需求，
     * 又不至于让家长觉得「不开也没影响」从而永远不去配奖励。
     */
    val baseMinutes: Int = DEFAULT_BASE_MINUTES,
    /** 奖励方案，0..N 条。同一 App 可以配多条阶梯（学 30 → +15、学 60 → +30）。 */
    val rewardRules: List<StudyRewardRule> = emptyList(),
    /**
     * 单日奖励总上限（分钟）。
     *
     * 必须有这个封顶：家长很容易一口气配五条「学 15 分钟给 30 分钟」的方案，
     * 加起来等于没设上限。默认 120 分钟，约为基本时长的六倍，够用又不失控。
     */
    val maxRewardMinutes: Int = DEFAULT_MAX_REWARD_MINUTES,
    /**
     * 播放中的定时提醒间隔（分钟）。0 表示关闭提醒。
     *
     * 提醒与拦截是两件事：提醒让「还剩多少」始终可见，避免孩子突然被拦时的对抗情绪；
     * 拦截才是真正的约束。默认 5 分钟——20 分钟的默认额度下，一天会提示 4 次，
     * 密度刚好够形成预期，又不至于变成骚扰。
     */
    val noticeIntervalMinutes: Int = DEFAULT_NOTICE_INTERVAL_MINUTES,
    /** 剩余 10 / 5 / 1 分钟时是否额外提醒（不受 [noticeIntervalMinutes] 影响）。 */
    val lowBalanceWarn: Boolean = true,
) {

    /**
     * 越界保护后的基本时长。
     *
     * 下限 1 而不是 0：0 会让「今日上限 = 0」变成一进播放器就锁死，
     * 家长想表达「不限」的意图是关掉总开关，不该靠填 0 来猜。
     */
    val baseMinutesClamped: Int
        get() = baseMinutes.coerceIn(MIN_BASE_MINUTES, MAX_BASE_MINUTES)

    val maxRewardMinutesClamped: Int
        get() = maxRewardMinutes.coerceIn(MIN_MAX_REWARD_MINUTES, MAX_MAX_REWARD_MINUTES)

    /** 越界保护后的提醒间隔。0 保留原义（关闭）。 */
    val noticeIntervalMinutesClamped: Int
        get() = if (noticeIntervalMinutes <= 0) {
            0
        } else {
            noticeIntervalMinutes.coerceIn(MIN_NOTICE_INTERVAL_MINUTES, MAX_NOTICE_INTERVAL_MINUTES)
        }

    /** 生效中的规则。停用的规则不参与判定，但保留在列表里等家长重新打开。 */
    val activeRules: List<StudyRewardRule>
        get() = rewardRules.filter { it.enabled && it.packageName.isNotBlank() }

    /** 今日上限（分钟）：基本时长 + 已发放奖励，后者被 [maxRewardMinutesClamped] 截断。 */
    fun totalMinutesFor(grantedMinutes: Int): Int =
        baseMinutesClamped + grantedMinutes.coerceIn(0, maxRewardMinutesClamped)

    companion object {
        /** 默认基本时长：一集动画片。 */
        const val DEFAULT_BASE_MINUTES = 20

        const val DEFAULT_MAX_REWARD_MINUTES = 120

        const val DEFAULT_NOTICE_INTERVAL_MINUTES = 5

        const val MIN_BASE_MINUTES = 1

        /** 10 小时。纯粹防手滑输入 9999。 */
        const val MAX_BASE_MINUTES = 600

        const val MIN_MAX_REWARD_MINUTES = 0

        const val MAX_MAX_REWARD_MINUTES = 300

        const val MIN_NOTICE_INTERVAL_MINUTES = 1

        const val MAX_NOTICE_INTERVAL_MINUTES = 60

        /** 设置页预设值。默认 20 排在第一位，家长多数情况点一下就完事。 */
        val ALLOWED_BASE_MINUTES = listOf(20, 30, 45, 60, 90, 120)

        /** 0 表示关闭提醒。 */
        val ALLOWED_NOTICE_INTERVALS = listOf(0, 5, 10, 15, 20, 30)

        /** 低余额必提醒的档位（剩余分钟），从大到小。 */
        val LOW_BALANCE_STEPS = listOf(10, 5, 1)
    }
}

/**
 * 一条「学习 App 换视频时长」的兑换规则。
 *
 * 刻意做成「单一阈值 + 固定奖励」而不是按比例兑换：按比例（学 1 分钟换 0.5 分钟）
 * 听起来更公平，但对孩子是黑箱——他算不清自己现在攒了多少，也就不会为了它去学习。
 * 「学满 30 分钟给 15 分钟」是可以被记住的规则，这才是激励能起作用的前提。
 */
@Serializable
data class StudyRewardRule(
    /**
     * 稳定标识。
     *
     * 用 UUID 而不是列表下标做主键：删除中间一条时下标会整体前移，
     * 已发放记录会错配到别的规则上，变成「删掉 A 之后 B 也发不出来了」。
     */
    val id: String = "",
    /** 学习 App 的包名，判定与跳转都靠它。 */
    val packageName: String = "",
    /**
     * App 名称快照。
     *
     * 不实时查包名对应的名称：App 被卸载后 `PackageManager` 查不到名字，
     * 设置页会变成一行空白，家长根本不知道删的是哪条。
     */
    val appLabel: String = "",
    /** 当日使用该 App 达到多少分钟算达标。 */
    val requiredMinutes: Int = DEFAULT_REQUIRED_MINUTES,
    /** 达标后奖励多少分钟视频时长。 */
    val rewardMinutes: Int = DEFAULT_REWARD_MINUTES,
    val enabled: Boolean = true,
) {

    val requiredMinutesClamped: Int
        get() = requiredMinutes.coerceIn(MIN_REQUIRED_MINUTES, MAX_REQUIRED_MINUTES)

    val rewardMinutesClamped: Int
        get() = rewardMinutes.coerceIn(MIN_REWARD_MINUTES, MAX_REWARD_MINUTES)

    /** 达标阈值（毫秒）。与分钟数严格同源，避免「说 30 分钟实际按 29 分 59 秒算」。 */
    val requiredMs: Long
        get() = requiredMinutesClamped * 60_000L

    companion object {
        const val DEFAULT_REQUIRED_MINUTES = 30

        const val DEFAULT_REWARD_MINUTES = 15

        /** 下限 5 分钟：再短的「学习」没有意义，只是点开又关掉。 */
        const val MIN_REQUIRED_MINUTES = 5

        /** 10 小时，同基本时长的防手滑上限。 */
        const val MAX_REQUIRED_MINUTES = 600

        const val MIN_REWARD_MINUTES = 1

        /** 单条最多 3 小时。再多就该拆成多条阶梯，而不是一条通吃。 */
        const val MAX_REWARD_MINUTES = 180

        val ALLOWED_REQUIRED_MINUTES = listOf(15, 20, 30, 45, 60, 90)

        val ALLOWED_REWARD_MINUTES = listOf(5, 10, 15, 20, 30, 45)
    }
}
