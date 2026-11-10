package dev.anilbeesetti.nextplayer.feature.player.study

import dev.anilbeesetti.nextplayer.core.model.QuizBank
import dev.anilbeesetti.nextplayer.core.model.QuizQuestionType
import dev.anilbeesetti.nextplayer.core.model.QuizWord
import dev.anilbeesetti.nextplayer.core.model.StudyProgress
import dev.anilbeesetti.nextplayer.core.model.WordStat
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizGeneratorTest {

    private fun word(
        id: String,
        tags: List<String> = listOf("food"),
        confusables: List<String> = emptyList(),
        emoji: String = "",
    ) = QuizWord(
        id = id,
        word = id,
        zh = listOf("释义-$id"),
        tags = tags,
        confusables = confusables,
        emoji = emoji,
    )

    private val bank = QuizBank(
        words = listOf(
            word("apple", confusables = listOf("apply", "ample", "maple"), emoji = "🍎"),
            word("apply"),
            word("ample"),
            word("maple"),
            word("bread", confusables = listOf("beard", "breed", "bead")),
            word("beard"),
            word("breed"),
            word("bead"),
        ),
    )

    @Test
    fun `question always has four distinct options containing the answer`() {
        repeat(50) { seed ->
            val question = QuizGenerator.nextQuestion(
                bank = bank,
                progress = StudyProgress(),
                nowMs = 0L,
                random = Random(seed),
            )
            assertNotNull(question)
            question!!
            assertEquals(4, question.options.size)
            assertEquals(4, question.options.distinct().size)
            assertEquals(1, question.options.count { it == question.answer })
        }
    }

    /** 干扰项优先取 confusables：否则孩子可以靠常识排除，等于没考。 */
    @Test
    fun `distractors come from confusables when the word has three of them`() {
        val apple = bank.words.first { it.word == "apple" }
        repeat(20) { seed ->
            val question = QuizGenerator.buildQuestion(
                word = apple,
                pool = bank.words,
                progress = StudyProgress(),
                random = Random(seed),
            )!!
            val distractors = question.options.filter { it != question.answer }
            assertEquals(3, distractors.size)
            assertTrue(distractors.all { it in apple.confusables })
        }
    }

    /** 同一个词按「听过几次」轮换题型：听 → 读 → 图。 */
    @Test
    fun `question types rotate with how many times the word was seen`() {
        val apple = bank.words.first { it.word == "apple" }
        val types = (0 until 3).map { seen ->
            val progress = StudyProgress(
                wordStats = mapOf("apple" to WordStat(seen = seen, streak = MASTERY_STREAK_FOR_TEST)),
            )
            QuizGenerator.buildQuestion(apple, bank.words, progress, Random(1))!!.type
        }
        assertEquals(
            listOf(
                QuizQuestionType.LISTEN_PICK_WORD,
                QuizQuestionType.WORD_TO_ZH,
                QuizQuestionType.EMOJI_PICK_WORD,
            ),
            types,
        )
    }

    /** 没有 emoji 的词不会出看图题。 */
    @Test
    fun `words without emoji never produce a picture question`() {
        val bread = bank.words.first { it.word == "bread" }
        val types = (0 until 6).map { seen ->
            val progress = StudyProgress(
                wordStats = mapOf("bread" to WordStat(seen = seen, streak = MASTERY_STREAK_FOR_TEST)),
            )
            QuizGenerator.buildQuestion(bread, bank.words, progress, Random(1))!!.type
        }
        assertTrue(types.none { it == QuizQuestionType.EMOJI_PICK_WORD })
    }

    /** 错题池里的词被抽中的次数应当高于已掌握的任意一个词。 */
    @Test
    fun `review words are picked more often than mastered words`() {
        val mastered = bank.words
            .filter { it.id != "bread" }
            .associate { it.id to WordStat(seen = 10, correct = 10, streak = 5, lastSeenAtMs = 0L) }
        val progress = StudyProgress(
            wordStats = mastered + (
                "bread" to WordStat(seen = 2, correct = 0, streak = 0, lastSeenAtMs = 0L)
                ),
        )

        val counts = mutableMapOf<String, Int>()
        repeat(300) { seed ->
            val question = QuizGenerator.nextQuestion(
                bank = bank,
                progress = progress,
                nowMs = 0L,
                random = Random(seed),
            )!!
            counts[question.word] = (counts[question.word] ?: 0) + 1
        }

        val breadCount = counts["bread"] ?: 0
        val maxOther = counts.filterKeys { it != "bread" }.values.maxOrNull() ?: 0
        assertTrue("bread=$breadCount maxOther=$maxOther", breadCount > maxOther)
    }

    @Test
    fun `recent words are avoided`() {
        repeat(20) { seed ->
            val question = QuizGenerator.nextQuestion(
                bank = bank,
                progress = StudyProgress(),
                nowMs = 0L,
                recentWordIds = listOf("bread"),
                random = Random(seed),
            )!!
            assertTrue(question.word != "bread")
        }
    }

    /** 多题时的第一题是热身题：只从已经答过的词里出，先建立信心再进生词。 */
    @Test
    fun `warm up question comes from learned words only`() {
        val progress = StudyProgress(
            wordStats = mapOf(
                "apple" to WordStat(seen = 3, correct = 3, streak = MASTERY_STREAK_FOR_TEST, lastSeenAtMs = 0L),
            ),
        )
        repeat(30) { seed ->
            val question = QuizGenerator.nextQuestion(
                bank = bank,
                progress = progress,
                nowMs = 0L,
                slot = QuestionSlot.WARM_UP,
                random = Random(seed),
            )!!
            assertEquals("apple", question.word)
        }
    }

    /**
     * 热身池为空（刚启用、还没有任何词被答过）时必须回退到普通选题。
     * 如果这里返回 null，上层会当成「题库异常」直接跳过整次拦截——锁就白设了。
     */
    @Test
    fun `warm up falls back when nothing has been learned yet`() {
        repeat(30) { seed ->
            val question = QuizGenerator.nextQuestion(
                bank = bank,
                progress = StudyProgress(),
                nowMs = 0L,
                slot = QuestionSlot.WARM_UP,
                random = Random(seed),
            )
            assertNotNull(question)
        }
    }

    /**
     * 欠账词在 [QuestionSlot.SMART] 里是硬优先，而不是「权重更高、有机会」。
     * 承诺是「上次没答对的词，下次必须答对才算完」，加权兑现不了这个承诺。
     */
    @Test
    fun `debt word always comes first in smart slot`() {
        val progress = StudyProgress(
            wordStats = mapOf(
                "bread" to WordStat(seen = 4, correct = 2, streak = 0, debt = true),
                "apple" to WordStat(seen = 9, correct = 9, streak = 9),
            ),
        )
        repeat(30) { seed ->
            val question = QuizGenerator.nextQuestion(
                bank = bank,
                progress = progress,
                nowMs = 0L,
                random = Random(seed),
            )!!
            assertEquals("bread", question.word)
            assertTrue("欠账词要带标记，UI 才知道不给「我记住了」这条捷径", question.debt)
        }
    }

    /**
     * 热身槽不拿欠账词凑数。
     *
     * 欠账词需要认真答，用来「找信心」是浪费；而且欠账一旦优先，热身槽会被它长期占住，
     * 热身就名存实亡了。还账的事交给 [QuestionSlot.SMART]。
     */
    @Test
    fun `warm up slot skips debt words when learned words exist`() {
        val progress = StudyProgress(
            wordStats = mapOf(
                "bread" to WordStat(seen = 4, correct = 2, streak = 0, debt = true),
                "apple" to WordStat(seen = 9, correct = 9, streak = 9),
            ),
        )
        repeat(30) { seed ->
            val question = QuizGenerator.nextQuestion(
                bank = bank,
                progress = progress,
                nowMs = 0L,
                slot = QuestionSlot.WARM_UP,
                random = Random(seed),
            )!!
            assertEquals("apple", question.word)
        }
    }

    /** 本次已出过的欠账词会被排除，不能在同一轮里反复出同一个词。 */
    @Test
    fun `debt word excluded by recent list falls back to normal selection`() {
        val progress = StudyProgress(
            wordStats = mapOf("bread" to WordStat(seen = 4, correct = 2, debt = true)),
        )
        repeat(20) { seed ->
            val question = QuizGenerator.nextQuestion(
                bank = bank,
                progress = progress,
                nowMs = 0L,
                recentWordIds = listOf("bread"),
                random = Random(seed),
            )!!
            assertTrue(question.word != "bread")
        }
    }

    @Test
    fun `returns null when the bank cannot fill four options`() {
        val tinyBank = QuizBank(words = listOf(word("apple"), word("bread")))
        assertNull(QuizGenerator.nextQuestion(tinyBank, StudyProgress(), nowMs = 0L))
    }

    /**
     * 多义词不进「看词选义」。
     *
     * 只取首义会让学过另一个义项的孩子觉得「没有一个选项对得上」
     * （cook 在课本里是「厨师」，词表首义是「烹饪」）；
     * 而把义项拼成一条又等于泄题——唯一带分号的那条一眼就是答案。
     */
    @Test
    fun `多义词不出看词选义题`() {
        val cook = QuizWord(
            id = "cook",
            word = "cook",
            zh = listOf("烹饪", "厨师"),
            tags = listOf("food"),
            emoji = "👨‍🍳",
        )
        val pool = bank.words + cook
        val types = (0 until 8).map { seen ->
            val progress = StudyProgress(
                wordStats = mapOf("cook" to WordStat(seen = seen, streak = MASTERY_STREAK_FOR_TEST)),
            )
            QuizGenerator.buildQuestion(cook, pool, progress, Random(1))!!.type
        }
        assertTrue("多义词不该出看词选义：$types", types.none { it == QuizQuestionType.WORD_TO_ZH })
    }

    /** 多义词的答题反馈要展示全部义项，否则孩子会以为答错了。 */
    @Test
    fun `多义词的反馈展示全部义项`() {
        val cook = QuizWord(id = "cook", word = "cook", zh = listOf("烹饪", "厨师"), tags = listOf("food"))
        val question = QuizGenerator.buildQuestion(cook, bank.words + cook, StudyProgress(), Random(1))!!
        assertEquals("烹饪；厨师", question.meaning)
    }

    /** 听力题每个选项都要有中文注脚：孩子常卡在「音听懂了但四个拼写都陌生」。 */
    @Test
    fun `听力题给每个选项附中文`() {
        val apple = bank.words.first { it.word == "apple" }
        // seen = 0 落在轮换的第一位，即听力题
        val question = QuizGenerator.buildQuestion(apple, bank.words, StudyProgress(), Random(1))!!
        assertEquals(QuizQuestionType.LISTEN_PICK_WORD, question.type)
        assertEquals(4, question.optionCaptions.size)
        assertTrue(question.options.all { question.optionCaptions[it]?.isNotBlank() == true })
    }

    /** 其他题型不需要中文注脚：看词选义本来就是选中文，看图选词考的是图。 */
    @Test
    fun `非听力题不带中文注脚`() {
        val apple = bank.words.first { it.word == "apple" }
        val progress = StudyProgress(
            wordStats = mapOf("apple" to WordStat(seen = 1, streak = MASTERY_STREAK_FOR_TEST)),
        )
        val question = QuizGenerator.buildQuestion(apple, bank.words, progress, Random(1))!!
        assertEquals(QuizQuestionType.WORD_TO_ZH, question.type)
        assertTrue(question.optionCaptions.isEmpty())
    }

    /**
     * 功能词（the / of / and…）不进题库。
     *
     * 「the」的四个选项是「这个 / 那个 / 的 / 在」，孩子靠语感就能选对，
     * 既练不到拼写也练不到听音，还会挤占真正需要练的实词。
     */
    /**
     * 骨架词只出听音题。
     *
     * 「the」的中文只能是「（定冠词）」，放进四选一的选项里，带括号的那一条一眼就是答案；
     * 看图题同理，孩子看到「这个词没有图」就能排除。只有听音题考的是 /ðə/ 能不能
     * 对到 the 而不是 this / that / they，那才是真本事。
     */
    @Test
    fun `骨架词只出听音题`() {
        val skeleton = QuizWord(
            id = "the",
            word = "the",
            zh = listOf("（定冠词）"),
            tags = listOf(QuizWord.FUNCTION_TAG, "art"),
        )
        val withSkeleton = QuizBank(words = bank.words + skeleton)
        // 题型按 seen 轮换，把各种轮换位置都走一遍，确认没有一个位置能绕过限制
        for (seen in 0..9) {
            val progress = StudyProgress(wordStats = mapOf("the" to WordStat(seen = seen)))
            val question = QuizGenerator.buildQuestion(
                word = skeleton,
                pool = withSkeleton.words,
                progress = progress,
                random = Random(seen),
            )!!
            assertEquals(
                "骨架词第 $seen 次出题时跑出了听音题：${question.type}",
                QuizQuestionType.LISTEN_PICK_WORD,
                question.type,
            )
        }
    }

    /**
     * 非听力题不拿骨架词当干扰项。
     *
     * 看词选义的选项是中文，「（定冠词）」带括号在四个选项里一眼就能认出来；
     * 看图选词的选项是单词，而 the / of 根本没有对应的图，孩子靠「这个词没有图」
     * 就能排除。两种情况下它都会把题目变成送分题。
     */
    @Test
    fun `非听力题的干扰项不混入骨架词`() {
        val skeletons = setOf("the", "a", "and", "of", "is")
        val clean = QuizBank(
            words = listOf(
                word("apple", confusables = emptyList(), emoji = "🍎"),
                word("bread"),
                word("cat", tags = listOf("tag_cat")),
                word("dog", tags = listOf("tag_dog")),
                word("sun", tags = listOf("tag_sun")),
            ) + skeletons.map {
                QuizWord(id = it, word = it, zh = listOf("（虚词）-$it"), tags = listOf(QuizWord.FUNCTION_TAG))
            },
        )
        val apple = clean.words.first { it.word == "apple" }
        // seen = 1 走「看词选义」，seen = 2 走「看图选词」
        for (seen in 1..2) {
            val progress = StudyProgress(wordStats = mapOf("apple" to WordStat(seen = seen)))
            repeat(30) { seed ->
                val question = QuizGenerator.buildQuestion(apple, clean.words, progress, Random(seed))!!
                assertNotEquals(QuizQuestionType.LISTEN_PICK_WORD, question.type)
                assertTrue(
                    "非听力题混入了骨架词：${question.options}",
                    question.options.none { option -> option.lowercase() in skeletons },
                )
            }
        }
    }

    /** 题库全是骨架词时仍能出题：它们只是只能考听音，不是不能考。 */
    @Test
    fun `题库只剩骨架词时仍能出听音题`() {
        val skeletonOnly = QuizBank(
            words = listOf("the", "a", "and", "of", "is").map { text ->
                QuizWord(id = text, word = text, zh = listOf("虚词-$text"), tags = listOf(QuizWord.FUNCTION_TAG))
            },
        )
        val question = QuizGenerator.nextQuestion(skeletonOnly, StudyProgress(), nowMs = 0L, random = Random(0))
        assertNotNull("全骨架词的题库不该被判成「无法出题」", question)
        assertEquals(QuizQuestionType.LISTEN_PICK_WORD, question!!.type)
    }

    /* ---------------- 题位配额 ---------------- */

    /** 新词槽只出没学过的词——这是「每天都有新词进来」的保证。 */
    @Test
    fun `新词槽只出没学过的词`() {
        val progress = StudyProgress(
            wordStats = mapOf(
                "apple" to WordStat(seen = 2, correct = 2, streak = 2),
                "bread" to WordStat(seen = 2, correct = 2, streak = 2),
            ),
        )
        repeat(30) { seed ->
            val question = QuizGenerator.nextQuestion(
                bank = bank,
                progress = progress,
                nowMs = 0L,
                slot = QuestionSlot.NEW_WORD,
                random = Random(seed),
            )!!
            assertTrue("新词槽出了学过的词：${question.word}", question.word != "apple" && question.word != "bread")
        }
    }

    /**
     * 全部词都学过之后，新词槽必须退回加权随机。
     * 返回 null 会被上层当成「题库异常」直接跳过整次拦截，锁就白设了。
     */
    @Test
    fun `没有新词时新词槽回退而不是放弃`() {
        val allSeen = StudyProgress(
            wordStats = bank.words.associate { it.word to WordStat(seen = 1, streak = 1) },
        )
        val question = QuizGenerator.nextQuestion(
            bank = bank,
            progress = allSeen,
            nowMs = 0L,
            slot = QuestionSlot.NEW_WORD,
            random = Random(0),
        )
        assertNotNull(question)
    }

    /* ---------------- 档位上限 ---------------- */

    /** 1..5 每档 4 个词：刚好够四选一，也便于断言答案落在哪一档。 */
    private val leveledBank = QuizBank(
        words = (1..5).flatMap { level ->
            (1..4).map { index ->
                QuizWord(
                    id = "l${level}_$index",
                    word = "w${level}x$index",
                    zh = listOf("释义$level-$index"),
                    tags = listOf("food"),
                    level = level,
                )
            }
        },
    )

    @Test
    fun `档位是上限而不是等值匹配`() {
        repeat(60) { seed ->
            val question = QuizGenerator.nextQuestion(
                bank = leveledBank,
                progress = StudyProgress(),
                nowMs = 0L,
                maxLevel = 3,
                random = Random(seed),
            )!!
            val level = leveledBank.words.first { it.word == question.word }.level
            assertTrue("选 3 档却出了第 $level 档的词", level <= 3)
        }
    }

    @Test
    fun `上限为 1 时只会出最低档的词`() {
        repeat(60) { seed ->
            val question = QuizGenerator.nextQuestion(
                bank = leveledBank,
                progress = StudyProgress(),
                nowMs = 0L,
                maxLevel = 1,
                random = Random(seed),
            )!!
            val level = leveledBank.words.first { it.word == question.word }.level
            assertEquals(1, level)
        }
    }

    /**
     * 降级期间高档欠账词也不出。
     *
     * 刚把孩子考倒的那个难词立刻再考一遍，等于在伤口上撒盐；
     * 欠账不会丢，升回原档后仍会被优先抽中。
     */
    @Test
    fun `降级后高档欠账词暂时封存`() {
        val progress = StudyProgress(
            wordStats = mapOf(
                "w5x1" to WordStat(seen = 1, correct = 0, streak = 0, debt = true),
                "w5x2" to WordStat(seen = 1, correct = 0, streak = 0, debt = true),
                "w5x3" to WordStat(seen = 1, correct = 0, streak = 0, debt = true),
            ),
        )
        repeat(40) { seed ->
            val question = QuizGenerator.nextQuestion(
                bank = leveledBank,
                progress = progress,
                nowMs = 0L,
                maxLevel = 3,
                random = Random(seed),
            )!!
            val level = leveledBank.words.first { it.word == question.word }.level
            assertTrue("降级后仍抽中了第 $level 档的欠账词", level <= 3)
        }
    }

    /** 上限内不足四选一时返回 null，让上层按「题库异常」放行。 */
    @Test
    fun `上限内词数不足时返回 null`() {
        val thin = QuizBank(
            words = (1..3).map {
                QuizWord(id = "only$it", word = "only$it", zh = listOf("释义$it"), tags = listOf("food"), level = 1)
            } + QuizWord(id = "high", word = "high", zh = listOf("高"), tags = listOf("food"), level = 5),
        )
        assertNull(
            QuizGenerator.nextQuestion(thin, StudyProgress(), nowMs = 0L, maxLevel = 3),
        )
    }

    /**
     * 与答案同义的那个变体不能出现在选项里。
     *
     * 注意不是「两个变体永不同框」：考的是 remember 时，recognise 和 recognize
     * 同时当干扰项完全合法——它们都不等于「记得」。约束只在**答案那一侧**。
     */
    @Test
    fun `同义变体不会成为答案的干扰项`() {
        val variants = QuizBank(
            words = listOf(
                QuizWord(id = "v1", word = "recognise", zh = listOf("认出"), tags = listOf("verb_think"), level = 5),
                QuizWord(id = "v2", word = "recognize", zh = listOf("认出"), tags = listOf("verb_think"), level = 5),
                QuizWord(id = "v3", word = "remember", zh = listOf("记得"), tags = listOf("verb_think"), level = 5),
                QuizWord(id = "v4", word = "forget", zh = listOf("忘记"), tags = listOf("verb_think"), level = 5),
                QuizWord(id = "v5", word = "remind", zh = listOf("提醒"), tags = listOf("verb_think"), level = 5),
            ),
        )
        val meaningOfAnswer = mutableMapOf<String, String>()
        variants.words.forEach { meaningOfAnswer[it.word] = it.primaryMeaning }

        repeat(80) { seed ->
            val question = QuizGenerator.nextQuestion(
                bank = variants,
                progress = StudyProgress(),
                nowMs = 0L,
                random = Random(seed),
            )!!
            val answerMeaning = meaningOfAnswer[question.answer] ?: return@repeat
            val conflict = question.options.filter { option ->
                option != question.answer && meaningOfAnswer[option] == answerMeaning
            }
            assertTrue(
                "答案 ${question.answer} 的选项里混进了同义变体 $conflict",
                conflict.isEmpty(),
            )
        }
    }

    /**
     * 多义词只取首义当题干，全部义项留给答题后的反馈。
     *
     * 设备念不出英文时听力题会降级成「看中文选英文」，题干就是 [QuizQuestion.primaryMeaning]。
     * 若这里填的是拼好的全义项，孩子会看到「烹饪；厨师」摆在上面，以为要选两样东西。
     */
    @Test
    fun `题干只取首义，反馈仍给全部义项`() {
        val cook = QuizWord(id = "cook", word = "cook", zh = listOf("烹饪", "厨师"), tags = listOf("food"))
        val question = QuizGenerator.buildQuestion(
            word = cook,
            pool = listOf(cook) + bank.words,
            progress = StudyProgress(),
            random = Random(7),
        )
        assertNotNull(question)
        assertEquals("烹饪", question!!.primaryMeaning)
        assertEquals("烹饪；厨师", question.meaning)
    }

    private companion object {
        /** 已掌握，用于让题型轮转不受「错题优先」干扰。 */
        const val MASTERY_STREAK_FOR_TEST = QuizGenerator.MASTERY_STREAK
    }
}
