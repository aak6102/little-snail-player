package dev.anilbeesetti.nextplayer.settings.screens.report

import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.data.repository.QuizBankRepository
import dev.anilbeesetti.nextplayer.core.data.repository.StudyRepository
import dev.anilbeesetti.nextplayer.core.model.StudyProgress
import dev.anilbeesetti.nextplayer.core.model.StudyReport
import dev.anilbeesetti.nextplayer.core.model.StudyReportCalculator
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

/**
 * 学习报告页。
 *
 * 存在的理由不是「看着好看」，而是让家长能用数据回答一个问题：
 * 这道锁到底有没有让孩子多学会几个词。没有这张表，家长只能凭感觉，
 * 也就没有依据决定是调间隔、换词表，还是干脆关掉。
 */
@KoinViewModel
class StudyReportViewModel(
    private val studyRepository: StudyRepository,
    private val quizBankRepository: QuizBankRepository,
    @InjectedParam internal var output: Output,
) : MviViewModel<StudyReportUiState, StudyReportUiEvent>() {

    data class Output(
        val navigateUp: () -> Unit,
    )

    private val stateInternal = MutableStateFlow(StudyReportUiState())
    override val state: StateFlow<StudyReportUiState> = stateInternal.asStateFlow()

    init {
        viewModelScope.launch {
            studyRepository.progress.collect(::publish)
        }
    }

    override fun onAction(action: StudyReportUiEvent) {
        when (action) {
            StudyReportUiEvent.NavigateUp -> output.navigateUp()
            is StudyReportUiEvent.ToggleWord -> toggleWord(action.statKey)
        }
    }

    private fun publish(progress: StudyProgress) {
        val report = StudyReportCalculator.build(
            progress = progress,
            nowMs = System.currentTimeMillis(),
        )
        // 跨词表反查：换过词表后，旧统计里还留着上一套词表的词，只查当前词表会让那些行退化成裸键
        val bank = quizBankRepository.bank(progress.preferences.activeBankId)
        val wordIndex = quizBankRepository.wordsByStatKey(progress.preferences.activeBankId)
        val rows = report.hardestWords.map { entry ->
            val word = wordIndex[entry.statKey]
            WordReportRow(
                statKey = entry.statKey,
                // 题库被换掉后旧统计里可能残留已删除的词，退化成 id 而不是整行消失
                word = word?.word ?: entry.statKey,
                meaning = word?.primaryMeaning.orEmpty(),
                wrong = entry.wrong,
                accuracyPercent = entry.accuracyPercent,
                debt = entry.debt,
            )
        }
        // 档位信息放报告页而不只放设置页：家长看到正确率下滑时，第一反应是「孩子不认真」，
        // 这里能告诉他系统已经把难度降下来了，下滑可能只是题变难了
        val level = progress.preferences.activeLevelClamped
        val effective = progress.adaptive.clampedTo(level).effectiveLevel(level)
        val activeInfo = bank.levels.firstOrNull { it.level == level }
        val effectiveInfo = bank.levels.firstOrNull { it.level == effective }
        stateInternal.update {
            it.copy(
                report = report,
                rows = rows,
                levelName = activeInfo?.name.orEmpty(),
                levelWordCount = activeInfo?.cumulativeCount ?: 0,
                effectiveLevelName = effectiveInfo?.name.orEmpty(),
                downgraded = effective < level,
            )
        }
    }

    /**
     * 释义默认收起：家长看的时候孩子常凑在旁边，直接摊开等于把答案送给他。
     * 点一下才展开，家长照着问、孩子自己想。
     */
    private fun toggleWord(statKey: String) {
        stateInternal.update { current ->
            val expanded = if (statKey in current.expandedWordIds) {
                current.expandedWordIds - statKey
            } else {
                current.expandedWordIds + statKey
            }
            current.copy(expandedWordIds = expanded)
        }
    }
}

data class WordReportRow(
    val statKey: String,
    val word: String,
    val meaning: String,
    val wrong: Int,
    val accuracyPercent: Int,
    val debt: Boolean,
)

data class StudyReportUiState(
    val report: StudyReport = StudyReport(),
    val rows: List<WordReportRow> = emptyList(),
    val expandedWordIds: Set<String> = emptySet(),
    /** 家长设定的档位名，词表未加载时为空。 */
    val levelName: String = "",
    /** 该档累计出题范围（去重后）。 */
    val levelWordCount: Int = 0,
    /** 自适应降级后实际出题的档位名。 */
    val effectiveLevelName: String = "",
    val downgraded: Boolean = false,
)

sealed interface StudyReportUiEvent {
    data object NavigateUp : StudyReportUiEvent
    data class ToggleWord(val statKey: String) : StudyReportUiEvent
}
