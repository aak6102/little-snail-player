package dev.anilbeesetti.nextplayer.settings.screens.parent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.model.RestPreferences
import dev.anilbeesetti.nextplayer.core.model.StudyPreferences
import dev.anilbeesetti.nextplayer.core.ui.R
import dev.anilbeesetti.nextplayer.core.ui.components.CancelButton
import dev.anilbeesetti.nextplayer.core.ui.components.ClickablePreferenceItem
import dev.anilbeesetti.nextplayer.core.ui.components.ListSectionTitle
import dev.anilbeesetti.nextplayer.core.ui.components.NextDialog
import dev.anilbeesetti.nextplayer.core.ui.components.NextTopAppBar
import dev.anilbeesetti.nextplayer.core.ui.components.PreferenceSwitch
import dev.anilbeesetti.nextplayer.core.ui.components.RadioTextButton
import dev.anilbeesetti.nextplayer.core.ui.components.rememberTvListFocusRequester
import dev.anilbeesetti.nextplayer.core.ui.components.tvFocusDown
import dev.anilbeesetti.nextplayer.core.ui.components.tvListFocus
import dev.anilbeesetti.nextplayer.core.ui.designsystem.NextIcons
import dev.anilbeesetti.nextplayer.settings.composables.NumberOptionDialog
import dev.anilbeesetti.nextplayer.settings.composables.OptionsDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ParentPreferencesScreen(
    viewModel: ParentPreferencesViewModel,
    /** 打开「修改家长密码」。导航层负责把门禁页切成 [GateMode.ChangePin]。 */
    onChangePin: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ParentPreferencesScreenContent(
        state = state,
        onAction = viewModel::onAction,
        onChangePin = onChangePin,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ParentPreferencesScreenContent(
    state: ParentPreferencesUiState,
    onAction: (ParentPreferencesUiEvent) -> Unit,
    onChangePin: () -> Unit,
) {
    val listFocusRequester = rememberTvListFocusRequester()

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(id = R.string.parent_settings),
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = { onAction(ParentPreferencesUiEvent.NavigateUp) },
                        modifier = Modifier.tvFocusDown(listFocusRequester),
                    ) {
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
                .tvListFocus(listFocusRequester)
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .padding(vertical = 16.dp),
        ) {
            ListSectionTitle(text = stringResource(id = R.string.parent_study_section))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                PreferenceSwitch(
                    title = stringResource(R.string.study_mode),
                    description = studyModeDescription(state),
                    icon = NextIcons.Headset,
                    isChecked = state.studyActive,
                    onClick = { onAction(ParentPreferencesUiEvent.ToggleStudyMode) },
                    isFirstItem = true,
                    isLastItem = false,
                )
                ClickablePreferenceItem(
                    title = stringResource(R.string.study_level),
                    description = levelDescription(state),
                    icon = NextIcons.Style,
                    onClick = { onAction(ParentPreferencesUiEvent.OpenLevelOptions) },
                    isLastItem = !state.studyActive,
                )
                if (state.studyActive) {
                    ClickablePreferenceItem(
                        title = stringResource(R.string.study_interval),
                        description = stringResource(R.string.study_interval_value, state.intervalMinutes),
                        icon = NextIcons.Calendar,
                        onClick = { onAction(ParentPreferencesUiEvent.OpenIntervalOptions) },
                    )
                    ClickablePreferenceItem(
                        title = stringResource(R.string.study_questions),
                        description = stringResource(R.string.study_questions_value, state.questionsPerLock),
                        icon = NextIcons.Selection,
                        onClick = { onAction(ParentPreferencesUiEvent.OpenQuestionsOptions) },
                    )
                    ClickablePreferenceItem(
                        title = stringResource(R.string.study_today_title),
                        description = if (state.todayAnswered > 0) {
                            stringResource(R.string.study_today_stats, state.todayAnswered, state.todayCorrect)
                        } else {
                            stringResource(R.string.study_today_empty)
                        },
                        icon = NextIcons.CheckBox,
                        enabled = false,
                        isLastItem = true,
                    )
                }
            }

            // 防沉迷和学习锁并列：同一页 PIN 门控、默认关。它管「看多久必须真休息」，
            // 和学习锁「看多久必须答题学英语」目标不同，放在相邻分组方便家长对照。
            ListSectionTitle(text = stringResource(id = R.string.parent_rest_section))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                PreferenceSwitch(
                    title = stringResource(R.string.rest_mode),
                    description = restModeDescription(state),
                    icon = NextIcons.HeadsetOff,
                    isChecked = state.restActive,
                    onClick = { onAction(ParentPreferencesUiEvent.ToggleRestMode) },
                    isFirstItem = true,
                    isLastItem = !state.restActive,
                )
                if (state.restActive) {
                    ClickablePreferenceItem(
                        title = stringResource(R.string.rest_window),
                        description = stringResource(R.string.rest_window_value, state.windowMinutes),
                        icon = NextIcons.History,
                        onClick = { onAction(ParentPreferencesUiEvent.OpenWindowOptions) },
                    )
                    ClickablePreferenceItem(
                        title = stringResource(R.string.rest_required_rest),
                        description = stringResource(R.string.rest_required_rest_value, state.requiredRestMinutes),
                        icon = NextIcons.Timer,
                        onClick = { onAction(ParentPreferencesUiEvent.OpenRestOptions) },
                    )
                    ClickablePreferenceItem(
                        title = stringResource(R.string.rest_crazy_interval),
                        description = stringResource(R.string.rest_crazy_interval_value, state.crazyIntervalMinutes),
                        icon = NextIcons.Speed,
                        onClick = { onAction(ParentPreferencesUiEvent.OpenCrazyIntervalOptions) },
                    )
                    ClickablePreferenceItem(
                        title = stringResource(R.string.rest_pre_warn),
                        description = stringResource(R.string.rest_pre_warn_value, state.preWarnSeconds),
                        icon = NextIcons.Info,
                        onClick = { onAction(ParentPreferencesUiEvent.OpenPreWarnOptions) },
                        isLastItem = true,
                    )
                }
            }

            // 报告入口不放在上面那个分组里：学习模式关掉之后家长仍然可能想回看历史，
            // 挂在开关分支下就会跟着一起消失。
            ListSectionTitle(text = stringResource(id = R.string.parent_data_section))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                ClickablePreferenceItem(
                    title = stringResource(R.string.study_report),
                    description = stringResource(R.string.study_report_entry_description),
                    icon = NextIcons.DashBoard,
                    onClick = { onAction(ParentPreferencesUiEvent.OpenReport) },
                    isFirstItem = true,
                    isLastItem = true,
                )
            }

            // 授权区块紧跟学习数据：家长查完孩子学得怎么样，下一步自然就是「要不要继续解锁」
            ListSectionTitle(text = stringResource(id = R.string.parent_license_section))
            ParentLicenseSection(state = state, onAction = onAction)

            ListSectionTitle(text = stringResource(id = R.string.parent_seek_section))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                if (state.license.pro) {
                    PreferenceSwitch(
                        title = stringResource(R.string.seek_throttle),
                        description = if (state.seekThrottleEnabled) {
                            seekThrottleDescription(state.seekIntervalMinutes)
                        } else {
                            stringResource(R.string.seek_throttle_description_off)
                        },
                        icon = NextIcons.FastForward,
                        isChecked = state.seekThrottleEnabled,
                        onClick = { onAction(ParentPreferencesUiEvent.ToggleSeekThrottle) },
                        isFirstItem = true,
                        isLastItem = !state.seekThrottleEnabled,
                    )
                    if (state.seekThrottleEnabled) {
                        ClickablePreferenceItem(
                            title = stringResource(R.string.seek_interval),
                            description = seekIntervalText(state.seekIntervalMinutes),
                            icon = NextIcons.Timer,
                            onClick = { onAction(ParentPreferencesUiEvent.OpenSeekIntervalOptions) },
                            isLastItem = true,
                        )
                    }
                } else {
                    // 免费版：快进限制固定常开、固定 2 分钟，不可调也不可关；
                    // 折叠成一条可点项，点它进入 Pro 引导（试用 / 购买）。
                    ClickablePreferenceItem(
                        title = stringResource(R.string.seek_throttle),
                        description = stringResource(R.string.seek_throttle_free_locked),
                        icon = NextIcons.FastForward,
                        onClick = { onAction(ParentPreferencesUiEvent.ToggleSeekThrottle) },
                        isFirstItem = true,
                        isLastItem = true,
                    )
                }
            }

            // 密码放最后：家长进来主要是改学习设置，改密码是低频操作。
            // 之前这一页没有入口，想换密码只能走「忘记密码 → 恢复码」，绕得离谱。
            ListSectionTitle(text = stringResource(R.string.gate_change_section))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                ClickablePreferenceItem(
                    title = stringResource(R.string.gate_change_entry),
                    description = stringResource(R.string.gate_change_entry_desc),
                    icon = NextIcons.Lock,
                    onClick = onChangePin,
                    isFirstItem = true,
                    isLastItem = true,
                )
            }
        }

        state.showDialog?.let { dialog ->
            when (dialog) {
                ParentPreferencesDialog.StartTrial -> {
                    StartTrialDialog(onAction = onAction)
                }

                ParentPreferencesDialog.IntervalOptions -> {
                    NumberOptionDialog(
                        title = stringResource(R.string.study_interval),
                        presets = StudyPreferences.ALLOWED_INTERVALS,
                        current = state.intervalMinutes,
                        min = StudyPreferences.MIN_INTERVAL_MINUTES,
                        max = StudyPreferences.MAX_INTERVAL_MINUTES,
                        unit = stringResource(R.string.parent_number_unit_minutes),
                        label = { minutes -> stringResource(R.string.study_interval_value, minutes) },
                        onConfirm = {
                            onAction(ParentPreferencesUiEvent.SetIntervalMinutes(it))
                        },
                        onDismissClick = { onAction(ParentPreferencesUiEvent.ShowDialog(null)) },
                    )
                }

                ParentPreferencesDialog.QuestionsOptions -> {
                    NumberOptionDialog(
                        title = stringResource(R.string.study_questions),
                        presets = StudyPreferences.ALLOWED_QUESTIONS_PER_LOCK,
                        current = state.questionsPerLock,
                        min = StudyPreferences.MIN_QUESTIONS_PER_LOCK,
                        max = StudyPreferences.MAX_QUESTIONS_PER_LOCK,
                        unit = stringResource(R.string.parent_number_unit_questions),
                        label = { count -> stringResource(R.string.study_questions_value, count) },
                        onConfirm = {
                            onAction(ParentPreferencesUiEvent.SetQuestionsPerLock(it))
                        },
                        onDismissClick = { onAction(ParentPreferencesUiEvent.ShowDialog(null)) },
                    )
                }

                ParentPreferencesDialog.SeekIntervalOptions -> {
                    NumberOptionDialog(
                        title = stringResource(R.string.seek_interval),
                        presets = StudyPreferences.ALLOWED_SEEK_INTERVALS,
                        current = state.seekIntervalMinutes,
                        min = StudyPreferences.MIN_SEEK_INTERVAL_MINUTES,
                        max = StudyPreferences.MAX_SEEK_INTERVAL_MINUTES,
                        unit = stringResource(R.string.parent_number_unit_minutes),
                        label = { minutes -> seekIntervalText(minutes) },
                        onConfirm = {
                            onAction(ParentPreferencesUiEvent.SetSeekIntervalMinutes(it))
                        },
                        onDismissClick = { onAction(ParentPreferencesUiEvent.ShowDialog(null)) },
                    )
                }
                ParentPreferencesDialog.LevelOptions -> {
                    OptionsDialog(
                        text = stringResource(R.string.study_level),
                        onDismissClick = { onAction(ParentPreferencesUiEvent.ShowDialog(null)) },
                    ) {
                        item {
                            Text(
                                text = stringResource(R.string.study_level_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                            )
                        }
                        items(state.levels) { info ->
                            RadioTextButton(
                                // 用标称词数（505 / 1600）而不是累计去重数：
                                // 家长是拿课标和课本对数字的，累计数对不上会被当成词表缺词
                                text = stringResource(
                                    R.string.study_level_option,
                                    info.name,
                                    info.nominalCount,
                                ),
                                selected = info.level == state.activeLevel,
                                onClick = {
                                    onAction(ParentPreferencesUiEvent.SetActiveLevel(info.level))
                                },
                            )
                        }
                    }
                }

                ParentPreferencesDialog.WindowOptions -> {
                    NumberOptionDialog(
                        title = stringResource(R.string.rest_window),
                        presets = RestPreferences.ALLOWED_WINDOWS,
                        current = state.windowMinutes,
                        min = RestPreferences.MIN_WINDOW_MINUTES,
                        max = RestPreferences.MAX_WINDOW_MINUTES,
                        unit = stringResource(R.string.parent_number_unit_minutes),
                        label = { minutes -> stringResource(R.string.rest_window_value, minutes) },
                        onConfirm = {
                            onAction(ParentPreferencesUiEvent.SetWindowMinutes(it))
                        },
                        onDismissClick = { onAction(ParentPreferencesUiEvent.ShowDialog(null)) },
                    )
                }

                ParentPreferencesDialog.RestOptions -> {
                    // 要求休息必须严格小于窗口：上限按「窗口 − 1」动态夹，
                    // 否则选了 30 分钟休息却只有 20 分钟窗口会触发阈值 ≤ 0（看一眼就进疯狂）。
                    NumberOptionDialog(
                        title = stringResource(R.string.rest_required_rest),
                        presets = RestPreferences.ALLOWED_RESTS,
                        current = state.requiredRestMinutes,
                        min = RestPreferences.MIN_REST_MINUTES,
                        max = (state.windowMinutes - 1).coerceAtLeast(RestPreferences.MIN_REST_MINUTES),
                        unit = stringResource(R.string.parent_number_unit_minutes),
                        label = { minutes -> stringResource(R.string.rest_required_rest_value, minutes) },
                        onConfirm = {
                            onAction(ParentPreferencesUiEvent.SetRequiredRestMinutes(it))
                        },
                        onDismissClick = { onAction(ParentPreferencesUiEvent.ShowDialog(null)) },
                    )
                }

                ParentPreferencesDialog.CrazyIntervalOptions -> {
                    NumberOptionDialog(
                        title = stringResource(R.string.rest_crazy_interval),
                        presets = RestPreferences.ALLOWED_CRAZY_INTERVALS,
                        current = state.crazyIntervalMinutes,
                        min = RestPreferences.MIN_CRAZY_INTERVAL_MINUTES,
                        max = RestPreferences.MAX_CRAZY_INTERVAL_MINUTES,
                        unit = stringResource(R.string.parent_number_unit_minutes),
                        label = { minutes -> stringResource(R.string.rest_crazy_interval_value, minutes) },
                        onConfirm = {
                            onAction(ParentPreferencesUiEvent.SetCrazyIntervalMinutes(it))
                        },
                        onDismissClick = { onAction(ParentPreferencesUiEvent.ShowDialog(null)) },
                    )
                }

                ParentPreferencesDialog.PreWarnOptions -> {
                    NumberOptionDialog(
                        title = stringResource(R.string.rest_pre_warn),
                        presets = listOf(15, 30, 45, 60),
                        current = state.preWarnSeconds,
                        min = RestPreferences.MIN_PREWARN_SECONDS,
                        max = RestPreferences.MAX_PREWARN_SECONDS,
                        unit = stringResource(R.string.parent_number_unit_seconds),
                        label = { seconds -> stringResource(R.string.rest_pre_warn_value, seconds) },
                        onConfirm = {
                            onAction(ParentPreferencesUiEvent.SetPreWarnSeconds(it))
                        },
                        onDismissClick = { onAction(ParentPreferencesUiEvent.ShowDialog(null)) },
                    )
                }
            }
        }

        // 购买弹窗独立于 showDialog：它有自己的生命周期（轮询、倒计时），
        // 混进 dialog 枚举里会被「切套餐」这类动作意外关掉
        if (state.purchase.visible) {
            PurchaseDialog(state = state.purchase, onAction = onAction)
        }
    }
}

@Composable
private fun levelDescription(state: ParentPreferencesUiState): String {
    val level = state.activeLevelInfo ?: return stringResource(R.string.study_level_loading)
    // 降级时把「现在实际按哪一档出题」写出来：家长看到孩子一直答错，
    // 若不知道系统已经降档，会以为开关没生效
    val effective = state.effectiveLevelInfo
    return if (state.isDowngraded && effective != null) {
        stringResource(
            R.string.study_level_value_downgraded,
            level.name,
            level.cumulativeCount,
            effective.name,
        )
    } else {
        stringResource(R.string.study_level_value, level.name, level.cumulativeCount)
    }
}

@Composable
private fun studyModeDescription(state: ParentPreferencesUiState): String =
    if (!state.studyActive) {
        stringResource(R.string.study_mode_locked_description)
    } else {
        stringResource(
            R.string.study_mode_description,
            state.intervalMinutes,
            state.questionsPerLock,
        )
    }

@Composable
private fun restModeDescription(state: ParentPreferencesUiState): String =
    if (!state.restActive) {
        stringResource(R.string.rest_mode_locked_description)
    } else {
        stringResource(
            R.string.rest_mode_description,
            state.windowMinutes,
            state.requiredRestMinutes,
            state.crazyIntervalMinutes,
        )
    }

/** 冷却设成 0 分钟表示不限制：显示「每 0 分钟一次」会被当成 bug。 */
@Composable
private fun seekIntervalText(minutes: Int): String =
    if (minutes <= 0) {
        stringResource(R.string.seek_interval_value_unlimited)
    } else {
        stringResource(R.string.seek_interval_value, minutes)
    }

@Composable
private fun seekThrottleDescription(minutes: Int): String =
    if (minutes <= 0) {
        stringResource(R.string.seek_throttle_description_unlimited)
    } else {
        stringResource(R.string.seek_throttle_description_on, minutes)
    }

private fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(millis))
