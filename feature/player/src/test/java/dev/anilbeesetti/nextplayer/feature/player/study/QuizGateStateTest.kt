package dev.anilbeesetti.nextplayer.feature.player.study

import dev.anilbeesetti.nextplayer.core.model.StudyPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 答题脚手架的回归测试。
 *
 * 这里锁住的是真机上被抓到过的一个 bug：答错两次，界面就把「正确答案：xxx」
 * 显示出来了。根因是 UI 层另写了一套提示分支，和状态机的 attempt 计数不同步。
 * 现在把迁移收拢到 [afterWrongPick]，任何提前 revealed 都会让这些用例失败。
 */
class QuizGateStateTest {

    private val maxAttempts = QuizGate.MAX_WRONG_ATTEMPTS

    @Test
    fun `first wrong pick only eliminates the chosen option`() {
        val state = QuizUiState(visible = true).afterWrongPick("kitchen", maxAttempts)

        assertEquals(1, state.attempt)
        assertFalse(state.revealed)
        assertEquals(setOf("kitchen"), state.eliminated)
    }

    @Test
    fun `second wrong pick still does not reveal the answer`() {
        var state = QuizUiState(visible = true)
        state = state.afterWrongPick("kitchen", maxAttempts)
        state = state.afterWrongPick("bedroom", maxAttempts)

        assertEquals(2, state.attempt)
        assertFalse("错两次就给答案，脚手架就退化成送分题", state.revealed)
        assertEquals(setOf("kitchen", "bedroom"), state.eliminated)
    }

    @Test
    fun `third wrong pick reveals the answer`() {
        var state = QuizUiState(visible = true)
        listOf("kitchen", "bedroom", "phone").forEach { state = state.afterWrongPick(it, maxAttempts) }

        assertEquals(3, state.attempt)
        assertTrue(state.revealed)
    }

    @Test
    fun `two wrong picks leave the answer plus one distractor selectable`() {
        val answer = "fridge"
        val options = listOf("kitchen", "bedroom", "phone", answer)
        var state = QuizUiState(visible = true)
        options.filter { it != answer }.take(2).forEach { state = state.afterWrongPick(it, maxAttempts) }

        val selectable = options.filterNot { it in state.eliminated }
        assertEquals(listOf("phone", answer), selectable)
        assertTrue("正确答案不能被排除掉", answer in selectable)
    }

    /**
     * 欠账词看到答案后不给「我记住了」这条捷径，必须自己点中正确答案。
     * 这是堵「随便点三下过关」的最后一道：错误不再被一次性消费掉。
     */
    @Test
    fun `debt word requires picking the answer instead of a confirm button`() {
        var state = QuizUiState(visible = true)
        listOf("kitchen", "bedroom", "phone").forEach {
            state = state.afterWrongPick(it, maxAttempts, debtWord = true)
        }

        assertTrue(state.revealed)
        assertTrue("欠账词应要求点答案", state.mustPickAnswer)
        // 三次错误后只剩正确答案可选，所以这里不会变成新的卡死点
        assertEquals(3, state.eliminated.size)
    }

    @Test
    fun `normal word still uses the confirm button after reveal`() {
        var state = QuizUiState(visible = true)
        listOf("kitchen", "bedroom", "phone").forEach {
            state = state.afterWrongPick(it, maxAttempts, debtWord = false)
        }

        assertTrue(state.revealed)
        assertFalse(state.mustPickAnswer)
    }

    /**
     * 浮动解锁按错题数打折，不是「全对 / 有错」两档截断。
     *
     * 锁住三件事：错得越多拿得越少（否则错一题之后剩下的题就失去意义）；
     * 短间隔下依然递减（间隔可以自由设到 1 分钟，固定 3 分钟下限会吃掉整个折扣）；
     * 以及任何间隔下都不会出现「答错比全对还能多看」的倒挂。
     */
    @Test
    fun `wrong answers scale the next unlock by wrong count`() {
        val ten = StudyPreferences(intervalMinutes = 10)
        assertEquals(10 * 60_000L, ten.intervalMillis)
        assertEquals(10 * 60_000L, ten.unlockMillisFor(0))
        assertEquals(6 * 60_000L, ten.unlockMillisFor(1))
        assertEquals(4 * 60_000L, ten.unlockMillisFor(2))
        // 错第 3 题不会继续往下掉：40% 是这一档的地板
        assertEquals(4 * 60_000L, ten.unlockMillisFor(3))
        assertEquals(6, ten.unlockMinutesFor(1))

        val fifteen = StudyPreferences(intervalMinutes = 15)
        assertEquals(9 * 60_000L, fifteen.unlockMillisFor(1))
        assertEquals(6 * 60_000L, fifteen.unlockMillisFor(2))

        val five = StudyPreferences(intervalMinutes = 5)
        assertEquals(5 * 60_000L, five.unlockMillisFor(0))
        assertEquals(3 * 60_000L, five.unlockMillisFor(1))
        assertEquals(2 * 60_000L, five.unlockMillisFor(2))

        // 间隔放宽到 1 分钟之后的关键分支：折扣必须在短间隔下仍然成立
        val three = StudyPreferences(intervalMinutes = 3)
        assertEquals(3 * 60_000L, three.unlockMillisFor(0))
        assertEquals(2 * 60_000L, three.unlockMillisFor(1))
        assertEquals(1 * 60_000L, three.unlockMillisFor(2))

        val two = StudyPreferences(intervalMinutes = 2)
        assertEquals(2 * 60_000L, two.unlockMillisFor(0))
        assertEquals(1 * 60_000L, two.unlockMillisFor(1))

        // 1 分钟已经没有任何折扣空间，但绝不能算成 0 分钟
        val one = StudyPreferences(intervalMinutes = 1)
        assertEquals(1 * 60_000L, one.unlockMillisFor(0))
        assertEquals(1 * 60_000L, one.unlockMillisFor(1))
        assertEquals(1 * 60_000L, one.unlockMillisFor(2))
    }

    /** 任何间隔下解锁时长都不能超过全对时长，否则「答错」反而更划算。 */
    @Test
    fun `discounted unlock never exceeds the full interval`() {
        (1..120).forEach { interval ->
            val prefs = StudyPreferences(intervalMinutes = interval)
            (0..5).forEach { wrong ->
                assertTrue(
                    "间隔 $interval 分钟、错 $wrong 题时解锁超过了全对时长",
                    prefs.unlockMillisFor(wrong) <= prefs.intervalMillis,
                )
            }
        }
    }

    /**
     * 题量可以自定义到 10 题，题位必须按比例分配。
     *
     * 4 题以上如果还沿用「热身 + 新词 + 随机 + 随机」，新词只占五分之一，
     * 与家长「想让他多学点新词」的预期相反；全给新词又会把欠账永远压着还不掉。
     */
    @Test
    fun `slot plan keeps about one third new words for any question count`() {
        assertEquals(listOf(QuestionSlot.SMART), planSlots(1, Random(1)))

        val two = planSlots(2, Random(1))
        assertEquals(2, two.size)
        assertEquals(1, two.count { it == QuestionSlot.WARM_UP })
        assertEquals("2 题只剩一个位置，应该让给还账而不是新词", 0, two.count { it == QuestionSlot.NEW_WORD })

        for (total in 3..10) {
            val slots = planSlots(total, Random(total.toLong()))
            assertEquals("题量 $total 的槽位数应等于题量", total, slots.size)
            assertEquals("首位固定热身", QuestionSlot.WARM_UP, slots.first())
            val newWords = slots.count { it == QuestionSlot.NEW_WORD }
            val reviewSlots = slots.count { it == QuestionSlot.SMART }
            assertTrue("题量 $total 至少要有一个新词位", newWords >= 1)
            assertTrue("题量 $total 的新词位不应超过三分之一上取整", newWords <= (total + 2) / 3)
            assertTrue("题量 $total 至少要留一个还账位", reviewSlots >= 1)
            assertEquals(total, 1 + newWords + reviewSlots)
        }
    }
}
