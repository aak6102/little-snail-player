package dev.anilbeesetti.nextplayer.settings.screens.report

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.model.DailyStudyStat
import dev.anilbeesetti.nextplayer.core.model.StudyReport
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.ListSectionTitle
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons

/** 柱状图柱区的最大高度。整体高度留出日期标签的位置。 */
private val BAR_AREA: Dp = 72.dp

@Composable
fun StudyReportScreen(viewModel: StudyReportViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StudyReportScreenContent(state = state, onAction = viewModel::onAction)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StudyReportScreenContent(
    state: StudyReportUiState,
    onAction: (StudyReportUiEvent) -> Unit,
) {
    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(id = R.string.study_report),
                navigationIcon = {
                    FilledTonalIconButton(onClick = { onAction(StudyReportUiEvent.NavigateUp) }) {
                        Icon(
                            imageVector = NextIcons.ArrowBack,
                            contentDescription = stringResource(id = R.string.navigate_up),
                        )
                    }
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(state = rememberScrollState())
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        ) {
            SummaryCard(report = state.report)

            if (state.levelName.isNotBlank()) {
                Text(
                    text = if (state.downgraded) {
                        stringResource(
                            R.string.study_report_level_downgraded,
                            state.levelName,
                            state.levelWordCount,
                            state.effectiveLevelName,
                        )
                    } else {
                        stringResource(
                            R.string.study_report_level,
                            state.levelName,
                            state.levelWordCount,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )
            }

            ListSectionTitle(text = stringResource(id = R.string.study_report_week))
            WeekChart(days = state.report.days)
            Spacer(Modifier.height(4.dp))
            ChartLegend()

            ListSectionTitle(text = stringResource(id = R.string.study_report_words))
            if (state.rows.isEmpty()) {
                EmptyWordsHint()
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.rows.forEach { row ->
                        WordRow(
                            row = row,
                            expanded = row.statKey in state.expandedWordIds,
                            onClick = { onAction(StudyReportUiEvent.ToggleWord(row.statKey)) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(id = R.string.study_report_words_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(report: StudyReport) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            if (report.hasData) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = stringResource(R.string.study_report_accuracy, report.accuracyPercent),
                        style = MaterialTheme.typography.displaySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.study_report_accuracy_label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.study_report_summary,
                        report.totalAnswered,
                        report.totalCorrect,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.study_report_active_days, report.activeDays, report.days.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.study_report_empty),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.study_report_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 近 7 天答题量。
 *
 * 柱高 = 当天答题总数，实心部分 = 答对数量。两个维度画在一根柱子里，
 * 比「只画正确率」更能看出真实情况：柱很高但实心很少，是敷衍；
 * 柱很矮但实心满，是刚开卡还没答几道。
 */
@Composable
private fun WeekChart(days: List<DailyStudyStat>) {
    val maxAnswered = days.maxOfOrNull { it.answered }?.coerceAtLeast(1) ?: 1
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        days.forEach { day ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                val barHeight = if (day.answered == 0) {
                    4.dp
                } else {
                    (BAR_AREA * (day.answered.toFloat() / maxAnswered)).coerceAtLeast(10.dp)
                }
                val correctHeight = if (day.answered == 0) {
                    0.dp
                } else {
                    barHeight * (day.correct.toFloat() / day.answered)
                }
                Box(
                    modifier = Modifier
                        .width(16.dp)
                        .height(barHeight)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(correctHeight)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = dayLabel(day.date),
                    fontSize = 9.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ChartLegend() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .width(10.dp)
                .height(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.study_report_legend_correct),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .width(10.dp)
                .height(10.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = stringResource(R.string.study_report_legend_wrong),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 一行错词。释义默认收起——家长看的时候孩子常凑在旁边，
 * 摊开等于把下次要考的答案直接送出去。
 */
@Composable
private fun WordRow(
    row: WordReportRow,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.word,
                    style = MaterialTheme.typography.titleMedium,
                )
                if (expanded) {
                    if (row.meaning.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = row.meaning,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.study_report_no_meaning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.study_report_tap_for_meaning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.study_report_wrong_times, row.wrong),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row.debt) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            text = stringResource(R.string.study_report_debt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyWordsHint() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(R.string.study_report_words_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        )
    }
}

/** 把 yyyy-MM-dd 裁成 MM/dd，柱子下面放得下。 */
private fun dayLabel(date: String): String =
    if (date.length >= 10) date.substring(5).replace('-', '/') else date
