package dev.anilbeesetti.nextplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自适应难度的状态机。
 *
 * 这几个数字（错 3 降、连对 6 升）直接决定孩子会不会被卡住，
 * 而且改动起来没有任何编译期信号——只能靠测试钉住。
 */
class AdaptiveLevelTest {

    private fun after(vararg results: Boolean, maxLevel: Int = 5): AdaptiveLevel =
        results.fold(AdaptiveLevel()) { state, correct -> state.afterAnswer(correct, maxLevel) }

    @Test
    fun `错两次不降档`() {
        val state = after(false, false)
        assertEquals(0, state.offset)
        assertEquals(2, state.wrongStreak)
    }

    @Test
    fun `错第三次降一档并清零连错`() {
        val state = after(false, false, false)
        assertEquals(1, state.offset)
        assertEquals(0, state.wrongStreak)
    }

    @Test
    fun `连错只降一档而不是每次错都降`() {
        // 已经降了一档，再连错三次才会降第二档
        val state = after(false, false, false, false, false)
        assertEquals(1, state.offset)
        assertEquals(2, state.wrongStreak)
    }

    @Test
    fun `降到最低档后不再继续降`() {
        val state = (1..20).fold(AdaptiveLevel()) { acc, _ -> acc.afterAnswer(false, maxLevel = 5) }
        assertEquals(4, state.offset)
        assertEquals(1, state.effectiveLevel(maxLevel = 5))
    }

    @Test
    fun `降级后连对六次升回一档`() {
        val downgraded = after(false, false, false)
        assertEquals(1, downgraded.offset)

        val fiveRight = (1..5).fold(downgraded) { acc, _ -> acc.afterAnswer(true, 5) }
        assertEquals("连对五次不该升档", 1, fiveRight.offset)

        val sixRight = fiveRight.afterAnswer(true, 5)
        assertEquals("连对六次应该升回", 0, sixRight.offset)
        assertEquals(0, sixRight.rightStreak)
    }

    @Test
    fun `没降级时连对不累计`() {
        // 留着这个计数会在下次降级后立刻凑满 6 次又弹上去，形成来回横跳
        val state = (1..10).fold(AdaptiveLevel()) { acc, _ -> acc.afterAnswer(true, 5) }
        assertEquals(0, state.rightStreak)
        assertEquals(0, state.offset)
    }

    @Test
    fun `答错会清空连对进度`() {
        val state = after(false, false, false, true, true).afterAnswer(false, 5)
        assertEquals(0, state.rightStreak)
    }

    @Test
    fun `家长调低上限后偏移量自动收缩`() {
        // 高中档降了 3 档，offset=3；改成小学档（3）后最多只能降 2 档
        val state = AdaptiveLevel(offset = 3)
        assertEquals(2, state.clampedTo(maxLevel = 3).offset)
        assertEquals(1, state.clampedTo(maxLevel = 3).effectiveLevel(maxLevel = 3))
    }

    @Test
    fun `有效档位不会低于一档`() {
        assertEquals(1, AdaptiveLevel(offset = 9).effectiveLevel(maxLevel = 2))
    }

    @Test
    fun `没有降级时有效档位等于上限`() {
        val state = AdaptiveLevel()
        assertFalse(state.isDowngraded)
        assertEquals(4, state.effectiveLevel(maxLevel = 4))
    }

    @Test
    fun `记账时同步推进自适应状态`() {
        val progress = StudyProgress()
            .withAnswer("apple", correct = false, nowMs = 0L, maxLevel = 5)
            .withAnswer("banana", correct = false, nowMs = 0L, maxLevel = 5)
        assertEquals(0, progress.adaptive.offset)

        val third = progress.withAnswer("cherry", correct = false, nowMs = 0L, maxLevel = 5)
        assertEquals("第三次答错应当降档", 1, third.adaptive.offset)
        // 单词账本本身不能受影响
        assertEquals(3, third.dailyStats.answered)
        assertEquals(0, third.dailyStats.correct)
        assertTrue(third.wordStats.getValue("apple").debt)
    }

    @Test
    fun `上限为一档时不会有降级空间`() {
        val state = (1..5).fold(AdaptiveLevel()) { acc, _ -> acc.afterAnswer(false, maxLevel = 1) }
        assertEquals(0, state.offset)
        assertEquals(1, state.effectiveLevel(maxLevel = 1))
    }
}
