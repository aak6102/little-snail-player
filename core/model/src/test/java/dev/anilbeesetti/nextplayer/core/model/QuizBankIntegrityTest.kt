package dev.anilbeesetti.nextplayer.core.model

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 词表是内容而不是代码，改错了不会编译报错，只会让孩子的题目出错。
 *
 * 这里对**真实的 assets 文件**做校验，而不是对样例数据：
 * - emoji 重复 → 看图选词出现两个正确答案，孩子选对也可能被判错（v1.5.0 前就踩过）
 * - confusables 悬空 → 干扰项回落到全库，题目退化成靠常识就能排除
 * - 词面重复 → 同一个拼写有两个词条，进度串号
 *
 * 目录通过 `quizAssetsDir` 系统属性传入（见 core/model/build.gradle.kts）。
 */
class QuizBankIntegrityTest {

    private val dir = File(
        System.getProperty("quizAssetsDir") ?: "../../app/src/main/assets/quiz",
    )

    private val index: QuizBankIndex by lazy {
        QuizBankParser.parseIndex(File(dir, "index.json").readText())
    }

    private fun bankOf(info: QuizBankInfo): QuizBank =
        QuizBankParser.parse(File(dir, info.file).readText())

    @Test
    fun `清单能解析且每套词表文件都存在`() {
        assertTrue("assets/quiz/index.json 没有列出任何词表", index.banks.isNotEmpty())
        index.banks.forEach { info ->
            assertTrue("清单里的 ${info.file} 不存在", File(dir, info.file).exists())
        }
    }

    @Test
    fun `默认词表存在于清单中`() {
        assertTrue("defaultBankId 为空", index.defaultBankId.isNotBlank())
        assertTrue(
            "默认词表 ${index.defaultBankId} 不在清单里，用户会拿到空题库",
            index.banks.any { it.id == index.defaultBankId },
        )
    }

    @Test
    fun `同一套词表内词面小写唯一`() {
        index.banks.forEach { info ->
            val duplicates = bankOf(info).words
                .groupingBy { it.statKey }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            assertTrue("${info.id} 存在同一拼写的多个词条：$duplicates", duplicates.isEmpty())
        }
    }

    @Test
    fun `同一套词表内 emoji 不重复`() {
        index.banks.forEach { info ->
            val duplicates = bankOf(info).words
                .map { it.emoji }
                .filter { it.isNotBlank() }
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            assertTrue(
                "${info.id} 有重复 emoji（看图选词会出现两个正确答案）：$duplicates",
                duplicates.isEmpty(),
            )
        }
    }

    @Test
    fun `confusables 只引用本词表内真实存在的拼写`() {
        index.banks.forEach { info ->
            val bank = bankOf(info)
            val words = bank.words.map { it.word }.toSet()
            bank.words.forEach { word ->
                word.confusables.forEach { ref ->
                    assertTrue(
                        "${info.id} 的 ${word.id} 引用了不存在的易混词 $ref",
                        ref in words,
                    )
                }
            }
        }
    }

    @Test
    fun `每个词都有释义和非空标签`() {
        index.banks.forEach { info ->
            bankOf(info).words.forEach { word ->
                assertTrue(
                    "${word.id} 缺少释义",
                    word.zh.isNotEmpty() && word.zh.first().isNotBlank(),
                )
                assertTrue("${word.id} 缺少标签", word.tags.isNotEmpty())
            }
        }
    }

    @Test
    fun `题量足够撑起四选一`() {
        index.banks.forEach { info ->
            val words = bankOf(info).words
            assertTrue("${info.id} 词数太少（${words.size}）", words.size >= 50)

            // 干扰项优先取同一语义场；场内不足 4 个词就只能退到全库，题目会变水。
            // func 不计入：那一层的成员是「骨架词」，互为干扰项是对的，
            // 但不该因为键名恰好是 func 就被当成一个普通的语义场去比较规模。
            words.flatMap { it.tags }
                .filter { it != QuizWord.FUNCTION_TAG }
                .groupingBy { it }
                .eachCount()
                .forEach { (tag, count) ->
                    assertTrue("${info.id} 的语义场 $tag 只有 $count 个词", count >= 4)
                }
        }
    }

    @Test
    fun `骨架词占比不超过三成`() {
        index.banks.forEach { info ->
            val bank = bankOf(info)
            val playable = bank.words.count { it.primaryMeaning.isNotBlank() }
            val skeleton = bank.words.count { it.isFunctionWord }
            assertTrue("${info.id} 可出题词太少（$playable）", playable >= 100)
            // 骨架词能出题，但只能考听音。占比过高会让一轮里全是 the / of / and
            assertTrue(
                "${info.id} 骨架词占比过高（$skeleton / ${bank.words.size}）",
                skeleton * 10 <= bank.words.size * 3,
            )
        }
    }

    /** 骨架词也得有释义：听力题的选项下面要挂中文注脚，空着就等于没有确认线索。 */
    @Test
    fun `骨架词都有释义`() {
        index.banks.forEach { info ->
            val blank = bankOf(info).words.filter { it.isFunctionWord && it.primaryMeaning.isBlank() }
            assertTrue("${info.id} 有骨架词没有释义：${blank.map { it.word }}", blank.isEmpty())
        }
    }

    /* ---------------- 五档词表 ---------------- */

    private fun banksWithLevels(): List<Pair<QuizBankInfo, QuizBank>> =
        index.banks.map { it to bankOf(it) }.filter { (_, bank) -> bank.levels.isNotEmpty() }

    @Test
    fun `所有词的档位都在 1 到 5 之间`() {
        banksWithLevels().forEach { (info, bank) ->
            val invalid = bank.words.filter { it.level !in StudyPreferences.MIN_LEVEL..StudyPreferences.MAX_LEVEL }
            assertTrue("${info.id} 有档位越界的词：${invalid.map { it.word }}", invalid.isEmpty())
        }
    }

    @Test
    fun `档位元数据覆盖 1 到 5 全部层级且升序`() {
        banksWithLevels().forEach { (info, bank) ->
            assertEquals(
                "${info.id} 的档位层级不完整",
                StudyPreferences.ALLOWED_LEVELS,
                bank.levels.map { it.level },
            )
        }
    }

    /**
     * 元数据里的四个数字都是给家长看的，写错不会崩，只会让设置页撒谎。
     * 所以从词表内容重新数一遍，而不是信任 JSON 里填的值。
     */
    @Test
    fun `档位元数据与词表内容一致`() {
        banksWithLevels().forEach { (info, bank) ->
            bank.levels.forEach { level ->
                val own = bank.words.count { it.level == level.level }
                val cumulative = bank.words.count { it.level <= level.level }
                val playable = bank.words.count {
                    it.level <= level.level && it.zh.isNotEmpty()
                }
                assertEquals("${level.name} 本档词数", own, level.wordCount)
                assertEquals("${level.name} 累计词数", cumulative, level.cumulativeCount)
                assertEquals("${level.name} 可出题词数", playable, level.quizWordCount)
            }
        }
    }

    /**
     * 自适应最低能降到起步档，所以**每一档都得出得了题**。
     * 一旦满档不足 4 个词，出题端会返回 null，上层把它当成「题库异常」
     * 直接放行——锁就白开了。
     */
    @Test
    fun `每一档累计都能撑起四选一`() {
        banksWithLevels().forEach { (info, bank) ->
            bank.levels.forEach { level ->
                val playable = bank.words.count {
                    it.level <= level.level && it.zh.isNotEmpty()
                }
                assertTrue(
                    "${info.id} 的 ${level.name} 档只有 $playable 个可出题词，降到这里会放行",
                    playable >= 4,
                )
            }
        }
    }

    @Test
    fun `同一拼写不会跨档重复出现`() {
        banksWithLevels().forEach { (info, bank) ->
            // 一个词只属于最低出现的那个档：跨档重复会让「档位=上限」的过滤失去意义
            val duplicates = bank.words.groupingBy { it.statKey }.eachCount().filterValues { it > 1 }
            assertTrue("${info.id} 有跨档重复的词：${duplicates.keys}", duplicates.isEmpty())
        }
    }
}
