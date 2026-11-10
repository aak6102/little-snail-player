package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 题库中的一个词条。
 *
 * 题库只存「词」，题目在运行时由 [QuizQuestionType] 模板生成，
 * 因此新增词条即可等比扩充题量，无需维护上千条题目记录。
 */
@Serializable
data class QuizWord(
    val id: String,
    val word: String,
    val pos: String = "",
    /** 释义，取第一个作为正确答案，其余参与干扰项池。 */
    val zh: List<String> = emptyList(),
    /** 可选 emoji，有值才可用于看图选词。 */
    val emoji: String = "",
    val tags: List<String> = emptyList(),
    /**
     * 易混词，**必须引用题库内真实存在的词**。
     * 优先用作干扰项，避免出现「fone / fourty」这类生造拼写误导孩子。
     */
    val confusables: List<String> = emptyList(),
    /**
     * 难度档位：1 起步 / 2 进阶 / 3 小学 / 4 初中 / 5 高中。
     *
     * 家长选的档位是**出题上限**，不是「只考这一档」：选初中时，起步到初中的
     * 词都在池子里。所以这个字段只用来过滤上界，不做等值匹配。
     *
     * 默认 [DEFAULT_LEVEL] 是为了兼容旧词表：它们整份都没有 level 字段，
     * 而那正是当初唯一的档位（小学），升级后老用户看到的行为保持不变。
     */
    val level: Int = DEFAULT_LEVEL,
) {
    val primaryMeaning: String get() = zh.firstOrNull().orEmpty()

    /** 全部义项拼成一条，用于答题后的反馈：多义词只显示首义，会让人以为答错了。 */
    val fullMeaning: String get() = zh.filter { it.isNotBlank() }.joinToString("；")

    /**
     * 骨架词：冠词、be 动词、助动词、纯语法介词与连词、否定词（词表里以 [FUNCTION_TAG] 标记）。
     *
     * 这类词**不排除出题，但只出「听音选词」**。原因是它的中文只能是语法说明——
     * 「the」的释义是「（定冠词）」，放进四选一的选项里，带括号的那一条一眼就是答案；
     * 看图题同理，孩子看到「这个词没有图」就能排除。而听音题考的是 /ðə/ 能不能对到
     * the 而不是 this / that / they，那是真本事，正好是入门阶段最该练的一环。
     *
     * 有实义的高频虚词**不**打这个标记：in / on / under / between 是方位考点，
     * although / unless / however 是阅读理解的关节词，它们照常出三种题型。
     */
    val isFunctionWord: Boolean get() = tags.contains(FUNCTION_TAG)

    companion object {
        /** 词表中标记功能词的语义场名。 */
        const val FUNCTION_TAG = "func"

        /** 旧词表没有 level 字段时按小学档（3）处理。 */
        const val DEFAULT_LEVEL = 3
    }

    /**
     * 进度记录的键：词面小写。
     *
     * 用词面而不是 [id] 作主键，是因为 id 是「人编的」——内置词表叫 `num_thirteen`，
     * 换一套教材词表可能叫 `pep4a_u1_doctor`，切换词表就等于把所有错题记录清空。
     * 词面变的是「同一个单词」，换词表后学过的照样算学过。
     *
     * 用 [Locale.ROOT] 而不是默认 locale：这是数据键不是展示文本，
     * 土耳其语环境下 `"I".lowercase()` 会得到无点的 `ı`，把同一个词拆成两条记录。
     */
    val statKey: String get() = word.lowercase(java.util.Locale.ROOT)
}

@Serializable
data class QuizBank(
    val version: Int = 1,
    val name: String = "",
    val description: String = "",
    /**
     * 档位元数据。设置页据此渲染 5 个难度选项，不必去数词表里有多少个 level。
     *
     * 缺省为空表示这是单档词表（旧格式），此时设置页退回「整份词表」的展示方式。
     */
    val levels: List<QuizLevelInfo> = emptyList(),
    val words: List<QuizWord> = emptyList(),
    /**
     * 是否为「内置基础包」：只含起步 / 进阶两档的词，其余档位靠云端内容包补齐。
     *
     * [levels] 仍然保留全五档 —— 那是产品目录，不是内容清单。家长设置页要看到
     * 「起步 / 进阶 / 小学 / 初中 / 高中」五个选项，而不是「怎么只剩两档」。
     *
     * 消费端只需要用它决定一件事：要不要显示「更新词库」提示。
     * **绝不能**用它改变出题规则：档位过滤本来就按 [QuizWord.level] 走，
     * 缺哪一档的词，池子里自然就没有哪一档。
     */
    val baseOnly: Boolean = false,
)

/** 词表自带的档位说明，对应 `five_levels.json` 的 levels 数组。 */
@Serializable
data class QuizLevelInfo(
    /** 1 起步 … 5 高中。 */
    val level: Int = 1,
    val name: String = "",
    val description: String = "",
    /** 课标标称词数（小学 505、高中 3000），家长对得上教材。 */
    val nominalCount: Int = 0,
    /** 本档新增词数：去掉低档已收录的部分。 */
    val wordCount: Int = 0,
    /** 选到这一档时实际能出的词数（1..本档累计去重）。 */
    val cumulativeCount: Int = 0,
    /** 累计词数里剔掉功能词后的可出题词数。 */
    val quizWordCount: Int = 0,
)

/** 词表清单中的一项，对应 assets/quiz/index.json。 */
@Serializable
data class QuizBankInfo(
    val id: String,
    val name: String = "",
    val description: String = "",
    /** assets/quiz/ 下的文件名。 */
    val file: String = "",
)

/** `index.json` 的整体结构。 */
@Serializable
data class QuizBankIndex(
    val version: Int = 1,
    /** 未指定或指定了不存在的词表时回落到这里。 */
    val defaultBankId: String = "",
    val banks: List<QuizBankInfo> = emptyList(),
)

/** 供设置页展示的词表条目：清单信息 + 实际词数（词数从词表文件统计，避免 index 与内容脱节）。 */
data class QuizBankEntry(
    val info: QuizBankInfo,
    val wordCount: Int,
)

/** 题型。全部为 4 选 1，纯点击，不需要弹出键盘。 */
enum class QuizQuestionType {
    /** 听音选词：TTS 朗读单词，选正确拼写。 */
    LISTEN_PICK_WORD,

    /** 看词选义：给英文单词，选中文释义。 */
    WORD_TO_ZH,

    /** 看图选词：给 emoji，选英文单词。 */
    EMOJI_PICK_WORD,
}

data class QuizQuestion(
    val statKey: String,
    val type: QuizQuestionType,
    /** 题干：看词选义时是单词，看图选词时是 emoji，听音选词时为空（靠朗读）。 */
    val prompt: String,
    val answer: String,
    val options: List<String>,
    /**
     * 选项的中文注脚（英文 → 中文），仅听力题使用。
     *
     * 纯听音时孩子常卡在「音听懂了，但拼写对不上」，四个拼写看起来都陌生；
     * 补一行中文让他能用「我听过这个词」来确认，而不是瞎选。
     */
    val optionCaptions: Map<String, String> = emptyMap(),
    /** 作答后展示的完整词条信息。 */
    val word: String,
    val meaning: String,
    val emoji: String,
    /**
     * 是否为「欠账词」：上次没独立答对，本次必须独立答对才能销账。
     * 欠账词不提供「我记住了」这条放行捷径，而是要求点中正确答案。
     */
    val debt: Boolean = false,
) {
    fun isCorrect(option: String): Boolean = option == answer
}

/** 家长可查看的今日学习情况。 */
@Serializable
data class DailyStudyStat(
    /** yyyy-MM-dd，换天自动清零。 */
    val date: String = "",
    val answered: Int = 0,
    val correct: Int = 0,
)

@Serializable
data class WordStat(
    val seen: Int = 0,
    val correct: Int = 0,
    /** 连续答对次数，达到 3 次移出错题池。 */
    val streak: Int = 0,
    val lastSeenAtMs: Long = 0L,
    /**
     * 欠账：上一次没有独立答对（含「错满三次看到答案后放行」）。
     *
     * 欠账词会在下一次拦截被**强制优先**抽中，而且不再提供
     * 「我记住了，继续看」这条捷径——必须点中正确答案才放行。
     * 这是堵住「随便点三下也能过关」的关键：错误不再被一次性消费掉。
     *
     * 默认 false，旧 study.json 反序列化不受影响。
     */
    val debt: Boolean = false,
)

/** 尚未完成答题的锁定状态。落盘保存，用于「杀进程重进仍然锁定」。 */
@Serializable
data class PendingQuizLock(
    val mediaId: String = "",
    val createdAtMs: Long = 0L,
)

/** 学习模式的全部持久化状态，存放在独立的 study.json 中。 */
@Serializable
data class StudyProgress(
    val preferences: StudyPreferences = StudyPreferences(),
    val pendingLock: PendingQuizLock? = null,
    val wordStats: Map<String, WordStat> = emptyMap(),
    /** 今天。与 [dailyHistory] 互不重叠：这里只放「今天」这一格。 */
    val dailyStats: DailyStudyStat = DailyStudyStat(),
    /**
     * 按日归档的历史记录，key 为 yyyy-MM-dd，只存**往日**（不含今天），由
     * [withAnswer] 在换天时自动把前一天的 [dailyStats] 收进来。
     *
     * 为什么不能只留 [dailyStats]：它换天即清零，导致「近 7 天正确率」永远
     * 只有一个数据点，家长装上一周也看不到趋势。归档是统计页的数据地基。
     *
     * 默认空表，旧 study.json 反序列化不受影响（昨天以前的数据本就没存，取了也取不到）。
     */
    val dailyHistory: Map<String, DailyStudyStat> = emptyMap(),
    /**
     * 自适应难度的当前状态：降了几档、连错几次、连对几次。
     *
     * 落在进度里而不是偏好里：它是答题产生的**结果**，不是家长配置。
     * 混进偏好会让「重置家长设置」把降级状态一起清掉，
     * 也会让设置页误以为自己能直接改这两个计数。
     */
    val adaptive: AdaptiveLevel = AdaptiveLevel(),
)

object QuizBankParser {

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    fun parse(text: String): QuizBank = jsonFormat.decodeFromString(QuizBank.serializer(), text)

    fun parseIndex(text: String): QuizBankIndex =
        jsonFormat.decodeFromString(QuizBankIndex.serializer(), text)
}

/** 日期键的格式。yyyy-MM-dd 的字典序与时间序一致，可以直接用字符串比较筛选区间。 */
const val DAY_KEY_PATTERN = "yyyy-MM-dd"

/** minSdk 24 用不了 java.time，统一用 SimpleDateFormat 生成「今天是哪天」的键。 */
fun dayKey(nowMs: Long): String =
    java.text.SimpleDateFormat(DAY_KEY_PATTERN, java.util.Locale.US).format(java.util.Date(nowMs))
