package dev.anilbeesetti.nextplayer.core.data.repository

import android.content.Context
import android.util.Log
import dev.anilbeesetti.nextplayer.core.model.QuizBank
import dev.anilbeesetti.nextplayer.core.model.QuizBankEntry
import dev.anilbeesetti.nextplayer.core.model.QuizBankIndex
import dev.anilbeesetti.nextplayer.core.model.QuizBankInfo
import dev.anilbeesetti.nextplayer.core.model.QuizBankParser
import dev.anilbeesetti.nextplayer.core.model.QuizWord
import org.koin.core.annotation.Single

/**
 * 题库加载器。题库以 assets 形式随包分发：
 * 断网也能出题，无需外部接口与 API Key，也不会出现运行时内容失控。
 *
 * 放在 core:data 而不是 feature:player，是因为家长端的「学习报告」也要
 * 把统计里的词面拿来显示释义；settings 模块拿不到 player 模块的类。
 *
 * 支持多套词表：`quiz/index.json` 是清单，同目录下其余 JSON 文件是各套内容。
 * 这里**不持有当前选择**——当前词表存在 [dev.anilbeesetti.nextplayer.core.model.StudyPreferences]
 * 里，由调用方传进来。否则本类反过来依赖 StudyRepository，
 * 而仓库层又需要题库来做进度主键迁移，会形成循环依赖。
 */
@Single
class QuizBankRepository(private val context: Context) {

    private val lock = Any()

    /** 按词表 id 缓存已解析内容。切换词表是低频操作，全量常驻内存也无压力。 */
    private val bankCache = HashMap<String, QuizBank>()

    /** 按文件名缓存，供跨词表反查复用：同一份文件被 bank() 与 wordsByStatKey() 各读一次是浪费。 */
    private val assetCache = HashMap<String, QuizBank>()

    private var indexCache: QuizBankIndex? = null

    /** 词表清单。读不到或内容为空时回落到内置词表，避免学习模式因一个坏文件而静默失效。 */
    fun index(): QuizBankIndex = synchronized(lock) {
        indexCache ?: readIndex().also { indexCache = it }
    }

    fun defaultBankId(): String = index().defaultBankId.ifBlank { FALLBACK_BANK_ID }

    /** 设置页用：清单信息 + 实际词数。词数从词表文件统计，避免 index 与内容各写一份而脱节。 */
    fun availableBanks(): List<QuizBankEntry> = index().banks.map { info ->
        QuizBankEntry(info = info, wordCount = bank(info.id).words.size)
    }

    /**
     * 取指定词表。id 不存在（例如旧配置指向已删除的词表）时回落到默认词表；
     * 连默认都读不出来才返回空题库——此时出题端会跳过拦截，而不是把孩子锁在答题页。
     */
    fun bank(bankId: String): QuizBank = synchronized(lock) {
        val requested = bankId.ifBlank { defaultBankId() }
        bankCache.getOrPut(requested) { readBank(requested) }
    }

    /**
     * 合并所有词表的「词面小写 → 词条」索引，供学习报告把统计里的键翻回释义。
     *
     * 之所以要跨词表：孩子换过词表后，旧统计里仍留着上一套词表的词，
     * 只查当前词表会让那些行退化成裸 id。
     *
     * @param preferredBankId 同拼写冲突时优先采用哪套词表的释义。
     */
    fun wordsByStatKey(preferredBankId: String): Map<String, QuizWord> = synchronized(lock) {
        val preferred = bank(preferredBankId).words
        // 同 wordsByIdAcrossBanks：换过词表的历史记录也要能翻出释义，
        // 否则报告页里那些词会退化成裸的英文 key。
        val others = bankFileNames()
            .filter { it != bankFileOf(preferredBankId) }
            .flatMap { readAsset(it, it).words }
        (preferred + others).associateBy { it.statKey }
    }

    /** assets/quiz 下除清单外的所有词表文件。 */
    private fun bankFileNames(): List<String> = runCatching {
        context.assets.list(ASSET_DIR)?.toList().orEmpty()
    }.getOrDefault(emptyList()).filter { it.endsWith(".json") && it != INDEX_FILE }

    private fun bankFileOf(bankId: String): String = index().banks
        .firstOrNull { it.id == bankId }?.file.orEmpty()

    /**
     * 所有词表的「词条 id → 词条」索引。
     *
     * 专门给进度主键迁移用：老存档里的 key 是词条 id，而 id 分散在不同词表里，
     * 只看当前词表会漏掉「换过词表的老记录」。
     *
     * 这里**扫描整个 assets/quiz 目录**而不是只读清单：词表会随版本增删，
     * 从清单里移除的词表文件仍留在包内，老存档的 id 还能反查出来；
     * 只查清单会让「从旧版本升级」的用户进度在迁移时静默失效。
     * 迁移是一次性的，多解析几个文件的代价可以忽略。
     */
    fun wordsByIdAcrossBanks(): Map<String, QuizWord> = synchronized(lock) {
        (index().banks.map { it.file } + bankFileNames())
            .distinct()
            .flatMap { file -> readAsset(file, file).words }
            .associateBy { it.id }
    }

    private fun readBank(bankId: String): QuizBank {
        val banks = index().banks
        val info = banks.firstOrNull { it.id == bankId }
            ?: banks.firstOrNull { it.id == defaultBankId() }
            ?: return QuizBank()
        return readAsset(info.file, bankId)
    }

    private fun readAsset(fileName: String, bankId: String): QuizBank = synchronized(lock) {
        // 五档词表有 3076 词、370KB，报告页每次刷新都要跨词表反查一遍；
        // 不缓存的话每答一题就重新解析几百 KB，纯属白烧电
        assetCache.getOrPut(fileName) {
            runCatching {
                context.assets.open("$ASSET_DIR/$fileName").bufferedReader().use { reader ->
                    QuizBankParser.parse(reader.readText())
                }
            }.getOrElse { error ->
                Log.e(TAG, "词表 $bankId 读取失败，退化为空题库", error)
                QuizBank()
            }
        }
    }

    private fun readIndex(): QuizBankIndex {
        val parsed = runCatching {
            context.assets.open("$ASSET_DIR/$INDEX_FILE").bufferedReader().use { reader ->
                QuizBankParser.parseIndex(reader.readText())
            }
        }.getOrElse { error ->
            Log.e(TAG, "词表清单读取失败，使用内置默认", error)
            null
        }
        if (parsed != null && parsed.banks.isNotEmpty()) return parsed
        return QuizBankIndex(
            defaultBankId = FALLBACK_BANK_ID,
            banks = listOf(
                QuizBankInfo(id = FALLBACK_BANK_ID, name = "课标五档", file = FALLBACK_FILE),
            ),
        )
    }

    companion object {
        private const val TAG = "QuizBankRepository"

        const val ASSET_DIR = "quiz"

        const val INDEX_FILE = "index.json"

        /** 与 app/src/main/assets/quiz/index.json 中的 defaultBankId 保持一致。 */
        const val FALLBACK_BANK_ID = "kebiao_five"

        private const val FALLBACK_FILE = "five_levels.json"
    }
}
