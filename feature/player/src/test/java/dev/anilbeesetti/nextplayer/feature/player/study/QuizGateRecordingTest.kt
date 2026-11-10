package dev.anilbeesetti.nextplayer.feature.player.study

import dev.anilbeesetti.nextplayer.core.data.repository.ContentPackRepository
import dev.anilbeesetti.nextplayer.core.data.repository.FakeLicenseRepository
import dev.anilbeesetti.nextplayer.core.data.repository.FakeStudyRepository
import dev.anilbeesetti.nextplayer.core.data.repository.QuizBankRepository
import dev.anilbeesetti.nextplayer.core.model.StudyPreferences
import dev.anilbeesetti.nextplayer.core.model.StudyProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 答题记账口径的回归测试。
 *
 * 这里锁住的是一个「看起来没问题、实际让排除法白用」的漏洞：
 * 孩子第一次点错、第二次点对，记账曾经按答对处理——这个词既不进错词池，
 * 下一轮也不会被优先复习，「划掉三个错项再选」于是成了一条免记账的捷径。
 *
 * 现在的口径是：
 * - 本题点错过 → 记账算错（留欠账，进错词池），哪怕最后选对了；
 * - 但过关与时长惩罚不变——排除法不是乱点，不该让孩子只剩 5 分钟。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class QuizGateRecordingTest {

    private val app get() = RuntimeEnvironment.getApplication()

    /**
     * 往「已安装云端包」的位置写一份极简词表。
     *
     * feature 模块自己没有 assets/quiz（那是 app 模块的），Robolectric 里读不到内置词表，
     * 出题端会认为题库不可用而跳过拦截。写一份云端包正好走「云端包优先」那条真实路径，
     * 顺带把这条链路也覆盖了。
     */
    private fun contentPack(): ContentPackRepository {
        val dir = java.io.File(app.filesDir, ContentPackRepository.DIR).apply { mkdirs() }
        java.io.File(dir, "meta.json").writeText("""{"version":1,"file":"five_levels.json"}""")
        java.io.File(dir, "five_levels.json").writeText(
            """
            {
              "version": 1,
              "name": "test",
              "levels": [{"level": 1, "name": "起步"}],
              "words": [
                {"id": "w_apple", "word": "apple", "zh": ["苹果"], "emoji": "🍎", "level": 1},
                {"id": "w_banana", "word": "banana", "zh": ["香蕉"], "emoji": "🍌", "level": 1},
                {"id": "w_orange", "word": "orange", "zh": ["橙子"], "emoji": "🍊", "level": 1},
                {"id": "w_grape", "word": "grape", "zh": ["葡萄"], "emoji": "🍇", "level": 1},
                {"id": "w_lemon", "word": "lemon", "zh": ["柠檬"], "emoji": "🍋", "level": 1},
                {"id": "w_peach", "word": "peach", "zh": ["桃子"], "emoji": "🍑", "level": 1}
              ]
            }
            """.trimIndent(),
        )
        return ContentPackRepository(
            FakeLicenseRepository(pro = true),
            app,
            CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private fun build(): Pair<QuizGate, FakeStudyRepository> {
        val repo = FakeStudyRepository(
            StudyProgress(
                preferences = StudyPreferences(enabled = true, intervalMinutes = 1),
            ),
        )
        val gate = QuizGate(
            repo,
            QuizBankRepository(app, contentPack()),
            FakeLicenseRepository(pro = true),
            CoroutineScope(Dispatchers.Unconfined),
        )
        return gate to repo
    }

    /** 走满 1 分钟，逼出第一道题。 */
    private fun lockOnce(gate: QuizGate) {
        gate.onTick(60_000L)
        assertNotNull("到点必须拦下来出题", gate.state.value.question)
    }

    @Test
    fun `点错再点对不记为答对`() {
        val (gate, repo) = build()
        lockOnce(gate)

        val question = requireNotNull(gate.state.value.question)
        val wrong = question.options.first { it != question.answer }

        gate.onOptionSelected(wrong)
        assertEquals(1, gate.state.value.attempt)

        gate.onOptionSelected(question.answer)

        val stat = repo.progress.value.wordStats[question.statKey]
        assertNotNull("答完必须留下记录", stat)
        assertEquals("靠排除法选对不算独立答对", 0, stat!!.correct)
        assertEquals("连对计数不能涨", 0, stat.streak)
        assertTrue("必须留欠账，下一轮优先复习", stat.debt)
    }

    @Test
    fun `一次答对仍然正常销账`() {
        val (gate, repo) = build()
        lockOnce(gate)

        val question = requireNotNull(gate.state.value.question)
        gate.onOptionSelected(question.answer)

        val stat = repo.progress.value.wordStats[question.statKey]
        assertNotNull(stat)
        assertEquals(1, stat!!.correct)
        assertEquals(1, stat.streak)
        assertFalse(stat.debt)
    }
}
