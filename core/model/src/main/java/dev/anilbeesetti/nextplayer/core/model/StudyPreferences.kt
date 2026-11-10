package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

/**
 * 家长管控偏好：控制「看多久必须答一道题」与「多久才能快进一次」。
 *
 * 与 [PlayerPreferences] 分开存放：这些属于家长管控能力，不是播放器的行为参数，
 * 混在一起会让「重置播放器设置」误伤家长的配置。
 *
 * 两者放在同一个偏好里而不是拆成两份，是因为它们共享同一件事：
 * 关闭口子只有家长能进（4 位 PIN），拆开会让这套保护逻辑写两遍。
 */
@Serializable
data class StudyPreferences(
    /** 是否开启答题锁。默认关闭，需要家长在设置里手动打开。 */
    val enabled: Boolean = false,
    /** 每累计多少分钟有效播放，触发一次答题。 */
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    /**
     * 每次拦截需要完成几道题。
     *
     * 中断成本是固定的（注意力被拔出来再重新接上），所以加题量远比加频率划算：
     * 「10 分钟 2 题」和「5 分钟 1 题」每小时题量相同，前者少打断一半的剧情。
     */
    val questionsPerLock: Int = DEFAULT_QUESTIONS_PER_LOCK,
    /** 快进节流：开启后两次快进之间必须等待 [seekIntervalMinutes] 分钟。 */
    val seekThrottleEnabled: Boolean = true,
    /**
     * 快进冷却时长（分钟）。
     *
     * 这是唯一能拦住「跳着看」的约束——答题锁只管看多久，管不了快进跳过剧情。
     * 默认 2 分钟是原版硬编码值，沿用以免升级后手感突变。
     */
    val seekIntervalMinutes: Int = DEFAULT_SEEK_INTERVAL_MINUTES,
    /**
     * 当前使用的词表 id，对应 assets/quiz/index.json 里的条目。
     *
     * 切换词表**不会**清空学习记录：进度按词面（[QuizWord.statKey]）而不是词条 id 存，
     * 两套词表里拼写相同的词共享掌握情况。
     *
     * 升级到五档词表后，出题改由 [activeLevel] 决定范围，这个字段只剩兼容用途：
     * 老存档里存的是 `kebiao_primary`，读出来也不会影响按档位过滤。
     */
    val activeBankId: String = DEFAULT_BANK_ID,
    /**
     * 家长设定的难度上限：1 起步 / 2 进阶 / 3 小学 / 4 初中 / 5 高中。
     *
     * 它是**上限**而不是等值筛选：选初中时，起步到初中的词都在出题池里。
     * 真正出题用的是「上限 − 自适应降级数」，见 [AdaptiveLevel.effectiveLevel]。
     */
    val activeLevel: Int = DEFAULT_LEVEL,
    /**
     * 进度主键的迁移版本。
     *
     * 0（或旧存档缺字段）：`wordStats` 的键是词条 id（如 `num_thirteen`）；
     * 1：键已迁移为词面小写（如 `thirteen`）。
     *
     * 用版本号而不是布尔值，是因为将来若再改一次主键规则，
     * 只需要认下一个数字，不必回收一个已经用掉的布尔值。
     */
    val statKeyVersion: Int = 0,
) {

    /**
     * 此刻学习模式是否真的在拦人。
     *
     * 就是 [enabled] 本身。原来这里还要叠加「延迟 24 小时生效」的判定 ——
     * 那是没有 PIN 时代的兜底：孩子摸到隐藏入口点了关闭，家长还有一天时间撤销。
     * 现在进设置页要输 4 位 PIN，孩子根本关不掉，这层缓冲只剩下副作用：
     * 家长自己想关也得等一天，只能来问客服。所以连同字段一起删掉了。
     *
     * 保留成函数而不是让调用方直接读 [enabled]，是为了将来若要再加条件（比如
     * 按星期几生效）时，消费端不用跟着改。
     */
    fun isActiveAt(nowMs: Long): Boolean = enabled

    /** 快进冷却时长（毫秒），已做越界保护。0 表示不限制。 */
    val seekIntervalMillis: Long
        get() = seekIntervalMinutesClamped * 60_000L

    /** 单次拦截的时长（毫秒）：本轮全对时解锁这么久。 */
    val intervalMillis: Long
        get() = intervalMinutesClamped * 60_000L

    /**
     * 越界保护后的间隔分钟数。
     *
     * 下限是 [MIN_INTERVAL_MINUTES]（1 分钟）。原来定在 5 分钟是为了给解锁折扣留空间，
     * 后来允许家长自定义到 1 分钟，就改成按比例算、下限跟着原时长走（见 [unlockMinutesFor]），
     * 不再靠「间隔不许设太短」来保折扣。
     */
    val intervalMinutesClamped: Int
        get() = intervalMinutes.coerceIn(MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES)

    /** 越界保护后的快进冷却分钟数。0 表示不限制（等价于关掉节流）。 */
    val seekIntervalMinutesClamped: Int
        get() = seekIntervalMinutes.coerceIn(MIN_SEEK_INTERVAL_MINUTES, MAX_SEEK_INTERVAL_MINUTES)

    /**
     * 越界保护后的难度上限。
     *
     * 旧存档没有这个字段、手改 JSON 也可能写进 0 或 99：
     * 0 会让出题池为空（锁直接失效），99 没有对应词表（同样为空）。
     */
    val activeLevelClamped: Int
        get() = activeLevel.coerceIn(MIN_LEVEL, MAX_LEVEL)

    /**
     * 本轮解锁分钟数：按**一次答错的题数**打折。
     *
     * 这是整套机制里唯一「不靠说教、但立刻有代价」的约束：
     * 乱点两下照样能过，只是过几分钟又要被拦一次。
     *
     * 为什么从「两档截断」改成乘法：原来全对拿满、有错统一压到 5 分钟，
     * 错 1 题和错 2 题代价完全一样。孩子一旦错了第一题，后面几题就失去意义，
     * 索性随便点——机制反而在鼓励摆烂。按错题数递减之后，多蒙对一题都是实打实的。
     *
     * 分钟与毫秒**从同一个整数算出**：先算分钟再乘 60_000。
     * 原来毫秒和分钟各算一遍，浮点误差会让结果页说 6 分钟、实际只给 5 分 59 秒。
     *
     * 下限固定 1 分钟而不是 3 分钟：间隔允许设成 1 分钟之后，固定下限会吃掉整个折扣
     * （3 分钟间隔 × 40% 只剩 1.2 分钟，兜回 3 分钟＝原值），答错和答对拿到一样的时长。
     * 现在短间隔下依然递减：3 分钟 → 3 / 2 / 1。
     *
     * @param wrongCount 本轮没一次答对的题数（含「错满三次看答案放行」的）。
     */
    fun unlockMinutesFor(wrongCount: Int): Int {
        val base = intervalMinutesClamped
        val percent = unlockPercentFor(wrongCount)
        // +50 是四舍五入：3 分钟 × 60% = 1.8，向下取整会变成 1 分钟，惩罚重得不合理
        val minutes = (base * percent + 50) / 100
        // 夹在 [1, base]：既要至少 1 分钟（0 分钟等于不让看），
        // 又绝不能超过全对时长（否则「答错」比「答对」还划算）
        return minutes.coerceIn(MIN_UNLOCK_MINUTES, base)
    }

    /** 本轮解锁的毫秒数，与 [unlockMinutesFor] 严格同源。 */
    fun unlockMillisFor(wrongCount: Int): Long = unlockMinutesFor(wrongCount) * 60_000L

    /** 错 0 / 1 / 2+ 题分别能拿到家长设定时长的百分之多少。 */
    fun unlockPercentFor(wrongCount: Int): Int = when {
        wrongCount <= 0 -> 100
        wrongCount == 1 -> WRONG_ONE_PERCENT
        else -> WRONG_TWO_PLUS_PERCENT
    }

    /**
     * 越界保护：旧配置文件、手改 JSON、将来改可选范围都可能写进异常值，
     * 而一次出 0 题等于把锁直接作废，一次出 99 题等于把孩子锁死。
     */
    val questionsPerLockClamped: Int
        get() = questionsPerLock.coerceIn(MIN_QUESTIONS_PER_LOCK, MAX_QUESTIONS_PER_LOCK)

    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 10

        const val DEFAULT_QUESTIONS_PER_LOCK = 2

        /**
         * 自定义题量的上限。
         *
         * 定在 10 而不是再往上：一次中断要连着答 10 题，低龄孩子基本撑不住；
         * 而且一轮里「学新词」只占三分之一，再多的位置都被复习占掉，
         * 与家长「想让他多学点新词」的预期相反。
         */
        const val MAX_QUESTIONS_PER_LOCK = 10

        const val MIN_QUESTIONS_PER_LOCK = 1

        /** 错 1 题：拿原时长的 60%（10 分钟 → 6 分钟）。 */
        const val WRONG_ONE_PERCENT = 60

        /** 错 2 题及以上：拿原时长的 40%（10 分钟 → 4 分钟）。 */
        const val WRONG_TWO_PLUS_PERCENT = 40

        /**
         * 本轮至少还能看这么多分钟。
         *
         * 原来是 3 分钟，用来防「刚答完题、片头都没放完就又被拦住」。
         * 间隔允许自定义到 1 分钟之后，固定 3 分钟下限会把折扣整段吃掉
         * （3 分钟间隔 × 40% ＝ 1.2 分钟，兜回 3 分钟等于没惩罚），
         * 所以降到 1 分钟：只守住「不能算成 0 分钟」，不再替家长决定最短间隔。
         */
        const val MIN_UNLOCK_MINUTES = 1

        /**
         * 自定义间隔的允许范围。
         *
         * 下限 1 分钟由家长自己决定要不要这么密；上限 120 分钟纯粹是防手滑输入 9999。
         */
        const val MIN_INTERVAL_MINUTES = 1

        const val MAX_INTERVAL_MINUTES = 120

        /** 预设值：家长多数情况直接点一个，不必展开自定义。 */
        val ALLOWED_INTERVALS = listOf(5, 10, 15)

        val ALLOWED_QUESTIONS_PER_LOCK = listOf(1, 2, 3)

        const val DEFAULT_SEEK_INTERVAL_MINUTES = 2

        /** 0 表示不限制：与关掉节流开关同义，但允许停在开关打开的状态下调出来。 */
        const val MIN_SEEK_INTERVAL_MINUTES = 0

        /** 30 分钟已经超过一集动画片的长度，再长就等于全程不能跳。 */
        const val MAX_SEEK_INTERVAL_MINUTES = 60

        val ALLOWED_SEEK_INTERVALS = listOf(1, 2, 5, 10)

        /**
         * 默认词表：课标五档。
         *
         * 旧存档里存的是 `core_words` / `kebiao_primary`，它们已从 index.json 移除，
         * [dev.anilbeesetti.nextplayer.core.data.repository.QuizBankRepository.bank] 会回落到这张表——
         * 文件仍在包内，只为老统计能翻出释义，不再参与出题。
         */
        const val DEFAULT_BANK_ID = "kebiao_five"

        /** 档位取值范围：1 起步 … 5 高中。 */
        const val MIN_LEVEL = 1

        const val MAX_LEVEL = 5

        /**
         * 默认档位：小学（课标二级 505）。
         *
         * 沿用旧版唯一档位，升级后家长不用重新设置；与 [QuizWord.DEFAULT_LEVEL] 保持一致。
         */
        const val DEFAULT_LEVEL = 3

        /** 设置页展示顺序，也是「档位从低到高」的唯一定义。 */
        val ALLOWED_LEVELS = (MIN_LEVEL..MAX_LEVEL).toList()

        /** 当前进度主键规则对应的版本，见 [statKeyVersion]。 */
        const val STAT_KEY_VERSION_WORD = 1
    }
}
