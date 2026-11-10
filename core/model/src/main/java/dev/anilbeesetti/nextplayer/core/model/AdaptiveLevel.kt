package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

/**
 * 自适应难度的状态。
 *
 * 家长选的档位是「上限」，[offset] 是这个上限被临时压低了几档：
 * 选初中（4）且 [offset] = 1 时，实际按小学（3）出题。
 *
 * 为什么降级要持久化而不是只在本轮生效：一次拦截只出 2–3 题，
 * 连错 3 次的阈值几乎永远不会在一轮里达成，做在内存里等于没做。
 *
 * 为什么降级不会变成「故意答错换简单题」的漏洞：
 * 每答错一题都要记欠账、本轮解锁时长按错题数打折，
 * 降级只是换更简单的词，题量一题不少——这笔账对孩子是亏的。
 */
@Serializable
data class AdaptiveLevel(
    /** 已降档数，0 表示按家长设定出题。 */
    val offset: Int = 0,
    /** 连续答错次数，达到 [DOWN_AFTER_WRONG] 时降一档并清零。 */
    val wrongStreak: Int = 0,
    /** 降级后连续答对次数，达到 [UP_AFTER_RIGHT] 时升回一档并清零。 */
    val rightStreak: Int = 0,
) {

    /**
     * 本次实际使用的档位。
     *
     * 用 [maxLevel] 现算而不是存一个「当前档位」字段：家长随时可能把上限从
     * 高中改成小学，存下来的绝对档位会立刻自相矛盾；存偏移量则天然跟随。
     */
    fun effectiveLevel(maxLevel: Int): Int {
        val ceiling = maxLevel.coerceIn(StudyPreferences.MIN_LEVEL, StudyPreferences.MAX_LEVEL)
        return (ceiling - offset).coerceAtLeast(StudyPreferences.MIN_LEVEL)
    }

    /**
     * 把偏移量收进当前上限允许的范围。
     *
     * 家长从高中档改到小学档时，旧 offset 可能大于 2，不收就会有
     * 「effectiveLevel 永远是 1」的错觉——实际只是偏移量没跟着缩。
     */
    fun clampedTo(maxLevel: Int): AdaptiveLevel {
        val ceiling = maxLevel.coerceIn(StudyPreferences.MIN_LEVEL, StudyPreferences.MAX_LEVEL)
        val maxOffset = ceiling - StudyPreferences.MIN_LEVEL
        return if (offset <= maxOffset) this else copy(offset = maxOffset)
    }

    /** 是否处于降级状态（决定结果页是否说一句「先练简单的」）。 */
    val isDowngraded: Boolean get() = offset > 0

    companion object {
        /** 连续答错多少次降一档。三次是一轮拦截的量级：两次太灵敏，四次孩子已经挫败了。 */
        const val DOWN_AFTER_WRONG = 3

        /** 降级后连续答对多少次升回一档。比降级门槛高，避免在边界反复横跳。 */
        const val UP_AFTER_RIGHT = 6
    }
}

/**
 * 一次作答之后的自适应状态迁移。
 *
 * 纯函数，与 [withAnswer] 一起构成「记账」的完整规则，Local 与 Fake 仓库共用，
 * 单测可以直接钉住阈值：错两次不降、第三次降；降级后连对五次不升、第六次升。
 */
fun AdaptiveLevel.afterAnswer(correct: Boolean, maxLevel: Int): AdaptiveLevel {
    val clamped = clampedTo(maxLevel)
    if (!correct) {
        val wrong = clamped.wrongStreak + 1
        val maxOffset = maxLevel.coerceIn(StudyPreferences.MIN_LEVEL, StudyPreferences.MAX_LEVEL) -
            StudyPreferences.MIN_LEVEL
        val shouldDrop = wrong >= AdaptiveLevel.DOWN_AFTER_WRONG && clamped.offset < maxOffset
        return when {
            // 已经降到底：继续累计连错没有意义，清零反而会让「再错一次」重新计时
            wrong >= AdaptiveLevel.DOWN_AFTER_WRONG && clamped.offset >= maxOffset ->
                clamped.copy(wrongStreak = AdaptiveLevel.DOWN_AFTER_WRONG, rightStreak = 0)
            shouldDrop -> clamped.copy(
                offset = clamped.offset + 1,
                wrongStreak = 0,
                rightStreak = 0,
            )
            else -> clamped.copy(wrongStreak = wrong, rightStreak = 0)
        }
    }

    // 没降级时不累计连对：那个计数只为「升回去」服务，留着只会在下次降级时
    // 立刻凑满 6 次又弹上去，形成来回横跳。
    if (clamped.offset == 0) return clamped.copy(wrongStreak = 0, rightStreak = 0)

    val right = clamped.rightStreak + 1
    return if (right >= AdaptiveLevel.UP_AFTER_RIGHT) {
        clamped.copy(offset = clamped.offset - 1, rightStreak = 0, wrongStreak = 0)
    } else {
        clamped.copy(rightStreak = right, wrongStreak = 0)
    }
}
