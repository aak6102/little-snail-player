package dev.anilbeesetti.nextplayer.feature.player.study

import dev.anilbeesetti.nextplayer.core.model.QuizBank
import dev.anilbeesetti.nextplayer.core.model.QuizQuestion
import dev.anilbeesetti.nextplayer.core.model.QuizQuestionType
import dev.anilbeesetti.nextplayer.core.model.QuizWord
import dev.anilbeesetti.nextplayer.core.model.StudyPreferences
import dev.anilbeesetti.nextplayer.core.model.StudyProgress
import kotlin.random.Random

/**
 * 一轮拦截里各个题位的职责。
 *
 * 为什么不用「全池加权随机」：一轮只出 1–3 题，权重差十倍落到 3 道题上，
 * 也不过是 0.3 题和 0.9 题的差别，孩子感受到的仍然是随机。更麻烦的是欠账词
 * 是硬优先级——攒到十几个之后每一题都在还账，新词一个也出不来。
 * 改成按槽位配题之后，一轮里必然同时包含还账、学新、复习，与池子多大无关。
 */
enum class QuestionSlot {
    /** 有欠账先还账，否则按权重随机。 */
    SMART,

    /** 只出没学过的词；没有新词时退回 [SMART]。 */
    NEW_WORD,

    /** 热身：只从已答过的词里挑，连对越多越优先，用来建立信心。 */
    WARM_UP,
}

/**
 * 出题引擎。
 *
 * 三个设计点决定这功能「是否真能提分」：
 * 1. 每轮按槽位配题，保证还账、学新、复习各有位置，而不是全池加权随机；
 * 2. 干扰项严格分层（易混词 > 同语义场 > 全库），避免孩子靠常识排除；
 * 3. 骨架词只出听音题——它的中文释义放进选项就等于把答案标出来。
 */
object QuizGenerator {

    /** 连续答对多少次算掌握，移出错题池。 */
    const val MASTERY_STREAK = 3

    private const val REVIEW_WEIGHT = 100
    private const val SECOND_REVIEW_WEIGHT = 60
    private const val NEW_WORD_WEIGHT = 60
    private const val STALE_WEIGHT = 40
    private const val MASTERED_WEIGHT = 10

    private const val STALE_INTERVAL_MS = 7L * 24 * 60 * 60 * 1000

    private const val OPTION_COUNT = 4

    /** 欠账池上限：超过这个数就优先还最久没碰的，防止几个错词把每道题都占满。 */
    private const val DEBT_POOL_LIMIT = 10

    fun nextQuestion(
        bank: QuizBank,
        progress: StudyProgress,
        nowMs: Long,
        recentWordIds: List<String> = emptyList(),
        slot: QuestionSlot = QuestionSlot.SMART,
        maxLevel: Int = StudyPreferences.MAX_LEVEL,
        random: Random = Random.Default,
    ): QuizQuestion? {
        // 骨架词不再整类排除：它照样进池子，只是出题时只能考听音（见 buildQuestion）。
        //
        // 档位过滤是**上界**：选初中时小学词照样出，只是不出高中词。
        // 降级期间高档欠账词也随之落选——刚把孩子考倒的那个难词先放一放，
        // 换个更简单的题建立信心，比立刻再考一遍更有效；欠账本身不会丢，
        // 升回原档后仍会被优先抽中。
        val words = bank.words.filter {
            it.word.isNotBlank() &&
                it.primaryMeaning.isNotBlank() &&
                it.level <= maxLevel
        }
        if (words.size < OPTION_COUNT) return null

        val candidates = words.filterNot { it.statKey in recentWordIds }.ifEmpty { words }
        val chosen = pickForSlot(candidates, progress, nowMs, slot, random) ?: return null
        return buildQuestion(chosen, words, progress, random)
    }

    private fun pickForSlot(
        words: List<QuizWord>,
        progress: StudyProgress,
        nowMs: Long,
        slot: QuestionSlot,
        random: Random,
    ): QuizWord? = when (slot) {
        QuestionSlot.WARM_UP ->
            pickWarmUp(words, progress, random) ?: pickScored(words, progress, nowMs, random)

        QuestionSlot.NEW_WORD ->
            pickNewWord(words, progress, random) ?: pickScored(words, progress, nowMs, random)

        QuestionSlot.SMART ->
            pickDebt(words, progress, random) ?: pickScored(words, progress, nowMs, random)
    }

    /**
     * 还账：只从答错过的词里挑。
     *
     * 欠账多到超过 [DEBT_POOL_LIMIT] 时只取最久没碰的那批：否则一道题反复错的词
     * 会永远占着坑，孩子卡在原地，后面的词一个也轮不到。
     */
    private fun pickDebt(words: List<QuizWord>, progress: StudyProgress, random: Random): QuizWord? {
        val debt = words.filter { progress.wordStats[it.statKey]?.debt == true }
        if (debt.isEmpty()) return null
        val pool = if (debt.size <= DEBT_POOL_LIMIT) {
            debt
        } else {
            debt.sortedBy { progress.wordStats[it.statKey]?.lastSeenAtMs ?: 0L }.take(DEBT_POOL_LIMIT)
        }
        return pool[random.nextInt(pool.size)]
    }

    /**
     * 只挑没学过的词。
     *
     * 判据用 `seen == 0` 而不是「查不到记录」：存档里可能有迁移残留，
     * 而「答过一次」才真正说明见过这个词。
     */
    private fun pickNewWord(words: List<QuizWord>, progress: StudyProgress, random: Random): QuizWord? {
        val fresh = words.filter { (progress.wordStats[it.statKey]?.seen ?: 0) == 0 }
        if (fresh.isEmpty()) return null
        return fresh[random.nextInt(fresh.size)]
    }

    /**
     * 热身题：只从「已经答对过」的词里挑，且连对越多权重越高。
     *
     * 一次答多题时第一题专门用来建立信心——孩子先秒答一道熟词，
     * 才有耐心面对后面的新词。若一个熟词都没有，返回 null 交给上层回退。
     */
    private fun pickWarmUp(words: List<QuizWord>, progress: StudyProgress, random: Random): QuizWord? {
        val weights = words.map { word ->
            val stat = progress.wordStats[word.statKey]
            // 欠账词不能当热身题：它恰恰是上次没答对的那个，需要认真答而不是找信心
            if (stat == null || stat.seen <= 0 || stat.debt) 0 else 10 + stat.streak * 20
        }
        return pickWeightedBy(words, weights, random)
    }

    /** 按权重挑一个，用于「没有更具体的槽位要求」时。 */
    private fun pickScored(
        words: List<QuizWord>,
        progress: StudyProgress,
        nowMs: Long,
        random: Random,
    ): QuizWord? = pickWeightedBy(words, words.map { weightOf(it, progress, nowMs) }, random)

    private fun pickWeightedBy(words: List<QuizWord>, weights: List<Int>, random: Random): QuizWord? {
        val total = weights.sum()
        if (total <= 0) return null
        var target = random.nextInt(total)
        words.forEachIndexed { index, word ->
            target -= weights[index]
            if (target < 0) return word
        }
        return words.lastOrNull()
    }

    /**
     * 权重：答对一次几乎不降温，第二次开始让位。
     *
     * 原来「连对不足三次一律 100」，等于前两次答对完全没有反馈——同一个词会
     * 紧挨着出现三次，然后断崖式掉到十分之一。现在第二次降到 60，让位给别的词，
     * 但又不至于直接滑到「已掌握」。
     */
    private fun weightOf(word: QuizWord, progress: StudyProgress, nowMs: Long): Int {
        val stat = progress.wordStats[word.statKey] ?: return NEW_WORD_WEIGHT
        return when {
            // streak 为 0 是「答错过、还没连对过」，与 streak 1 同为最高优先级
            stat.streak <= 1 -> REVIEW_WEIGHT
            stat.streak == 2 -> SECOND_REVIEW_WEIGHT
            nowMs - stat.lastSeenAtMs > STALE_INTERVAL_MS -> STALE_WEIGHT
            else -> MASTERED_WEIGHT
        }
    }

    /** 由指定词条生成题目。设为 internal 是为了让测试可以绕过加权选题、直接验证题型与干扰项规则。 */
    internal fun buildQuestion(
        word: QuizWord,
        pool: List<QuizWord>,
        progress: StudyProgress,
        random: Random,
    ): QuizQuestion? {
        val types = buildList {
            add(QuizQuestionType.LISTEN_PICK_WORD)
            // 骨架词只能走听音：它的中文是「（定冠词）」这类语法说明，
            // 放进「看词选义」等于把答案标出来；看图题同理，孩子看到
            // 「这个词没有图」就能排除。只有听音题考的是音形对应，才算真考题。
            if (!word.isFunctionWord) {
                // 多义词（如 cook = 烹饪／厨师）不进「看词选义」：
                // 只取首义会让学过另一个义项的孩子觉得「没有一个选项对得上」，
                // 而把义项拼成一条又等于泄题——唯一带分号的那条一眼就是答案。
                // 它们仍会出现在听力／看图题里，答完同样能看到全部义项。
                if (word.zh.size <= 1) add(QuizQuestionType.WORD_TO_ZH)
                if (word.emoji.isNotBlank()) add(QuizQuestionType.EMOJI_PICK_WORD)
            }
        }
        // 同一个词换着题型考：听 → 读 → 图，避免只会认一个维度
        val seen = progress.wordStats[word.statKey]?.seen ?: 0
        val type = types[seen % types.size]

        val answer = if (type == QuizQuestionType.WORD_TO_ZH) word.primaryMeaning else word.word
        val distractors = distractorsFor(word, pool, type, random) ?: return null
        val options = (distractors + answer).shuffled(random)

        // 听力题给每个选项补中文：孩子常卡在「音听懂了但四个拼写都陌生」，
        // 有一行中文就能靠「我听过这个词」确认，而不是瞎点。
        val captions = if (type == QuizQuestionType.LISTEN_PICK_WORD) {
            options.mapNotNull { option ->
                val meaning = if (option == word.word) {
                    word.primaryMeaning
                } else {
                    pool.firstOrNull { it.word == option }?.primaryMeaning.orEmpty()
                }
                meaning.takeIf { it.isNotBlank() }?.let { option to it }
            }.toMap()
        } else {
            emptyMap()
        }

        return QuizQuestion(
            statKey = word.statKey,
            type = type,
            prompt = when (type) {
                QuizQuestionType.WORD_TO_ZH -> word.word
                QuizQuestionType.EMOJI_PICK_WORD -> word.emoji
                QuizQuestionType.LISTEN_PICK_WORD -> ""
            },
            answer = answer,
            options = options,
            optionCaptions = captions,
            word = word.word,
            // 用全义项而不是首义：cook 只显示「烹饪」的话，学过「厨师」的孩子会以为答错了
            meaning = word.fullMeaning,
            // 但题干（无朗读时的替身）只能用首义：多义项拼成一条会让人以为要选两样
            primaryMeaning = word.primaryMeaning,
            emoji = word.emoji,
            debt = progress.wordStats[word.statKey]?.debt == true,
        )
    }

    /**
     * 干扰项严格分层：confusables > 同 tag > 全库。
     *
     * 必须是「用完一层再降级」，不能做成加权混抽：一旦混入无关词，
     * 孩子就能靠常识排除，题目立刻退化成送分题。
     */
    private fun distractorsFor(
        word: QuizWord,
        pool: List<QuizWord>,
        type: QuizQuestionType,
        random: Random,
    ): List<String>? {
        val answerText = if (type == QuizQuestionType.WORD_TO_ZH) word.primaryMeaning else word.word

        fun textOf(candidate: QuizWord): String =
            if (type == QuizQuestionType.WORD_TO_ZH) candidate.primaryMeaning else candidate.word

        // 看词选义 / 看图选词不能拿骨架词当干扰项：前者的选项是中文，
        // 「（定冠词）」带括号一眼就能认出来；后者的选项是单词，而 the / of
        // 这类词根本没有对应的图，孩子靠「这个词没有图」就能排除。
        // 听音题不受影响——拼写本来就是正常选项，且 the / this / that 同首音
        // 正是有意义的辨析。
        val usable = if (type == QuizQuestionType.LISTEN_PICK_WORD) {
            pool
        } else {
            pool.filterNot { it.isFunctionWord }
        }

        val tiers = listOf(
            usable.filter { it.word in word.confusables },
            usable.filter { candidate -> candidate.tags.any { tag -> tag in word.tags } },
            usable,
        )

        val picked = mutableListOf<String>()
        for (tier in tiers) {
            if (picked.size >= OPTION_COUNT - 1) break
            val candidates = tier
                .asSequence()
                .filter { it.id != word.id }
                // 同义变体不能当干扰项：词表里同时收了 recognise / recognize、
                // yoghurt / yogurt、toward / towards，听力题里两个拼写都「算对」，
                // 孩子听对了音却可能因为选了另一个变体被判错。释义相同即排除。
                .filter { candidate -> candidate.primaryMeaning != word.primaryMeaning }
                .map { textOf(it) }
                .filter { it.isNotBlank() && it != answerText && it !in picked }
                .distinct()
                .toList()
                .shuffled(random)
            for (candidate in candidates) {
                if (picked.size >= OPTION_COUNT - 1) break
                picked += candidate
            }
        }
        return picked.takeIf { it.size == OPTION_COUNT - 1 }
    }
}
