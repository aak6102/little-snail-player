package dev.anilbeesetti.nextplayer.settings.screens.parent.quota

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.anilbeesetti.nextplayer.core.model.DailyQuotaPreferences
import dev.anilbeesetti.nextplayer.core.model.StudyRewardRule
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

/**
 * 每日观看额度的家长设置页。
 *
 * 页面上所有数字都只做「配置」，实际扣减发生在播放器的每秒 ticker 里。
 * 这里能看到今日数字，是因为额度状态与配置存在同一份 `quota.json` 中——
 * 家长改完设置立刻能对着今日数据验证，不必等孩子下次打开播放器。
 */
@Composable
fun DailyQuotaScreen(viewModel: DailyQuotaViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DailyQuotaScreenContent(state = state, onAction = viewModel::onAction)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DailyQuotaScreenContent(
    state: DailyQuotaUiState,
    onAction: (DailyQuotaUiEvent) -> Unit,
) {
    val listFocusRequester = rememberTvListFocusRequester()

    // 从系统「使用情况访问权限」页返回时，这里要重新查一次授权状态，
    // 否则家长明明授权了，回来还是「未开启」。
    LaunchedEffect(Unit) { onAction(DailyQuotaUiEvent.RefreshUsageAccess) }

    Scaffold(
        topBar = {
            NextTopAppBar(
                title = stringResource(id = R.string.quota_settings_title),
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = { onAction(DailyQuotaUiEvent.NavigateUp) },
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
            ListSectionTitle(text = stringResource(id = R.string.quota_mode))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                PreferenceSwitch(
                    title = stringResource(id = R.string.quota_mode),
                    description = if (state.enabled) {
                        stringResource(
                            id = R.string.quota_mode_desc_on,
                            state.totalMinutes,
                            state.remainingMinutes,
                        )
                    } else {
                        stringResource(id = R.string.quota_mode_desc_off)
                    },
                    icon = NextIcons.Timer,
                    isChecked = state.enabled,
                    onClick = { onAction(DailyQuotaUiEvent.ToggleEnabled) },
                    isFirstItem = true,
                    isLastItem = !state.enabled,
                )

                if (state.enabled) {
                    ClickablePreferenceItem(
                        title = stringResource(id = R.string.quota_base_weekday),
                        description = stringResource(
                            id = R.string.quota_base_value,
                            state.weekdayBaseMinutes,
                        ),
                        icon = NextIcons.Calendar,
                        onClick = {
                            onAction(DailyQuotaUiEvent.ShowDialog(DailyQuotaDialog.BaseWeekday))
                        },
                    )
                    ClickablePreferenceItem(
                        title = stringResource(id = R.string.quota_base_weekend),
                        description = stringResource(
                            id = R.string.quota_base_value,
                            state.weekendBaseMinutes,
                        ),
                        icon = NextIcons.Calendar,
                        onClick = {
                            onAction(DailyQuotaUiEvent.ShowDialog(DailyQuotaDialog.BaseWeekend))
                        },
                    )
                    ClickablePreferenceItem(
                        title = stringResource(id = R.string.quota_max_reward),
                        description = stringResource(
                            id = R.string.quota_max_reward_value,
                            state.maxRewardMinutes,
                        ),
                        icon = NextIcons.Speed,
                        onClick = { onAction(DailyQuotaUiEvent.ShowDialog(DailyQuotaDialog.MaxReward)) },
                    )
                    ClickablePreferenceItem(
                        title = stringResource(id = R.string.quota_notice),
                        description = if (state.noticeIntervalMinutes <= 0) {
                            stringResource(id = R.string.quota_notice_off)
                        } else {
                            stringResource(
                                id = R.string.quota_notice_value,
                                state.noticeIntervalMinutes,
                            )
                        },
                        icon = NextIcons.Info,
                        onClick = {
                            onAction(DailyQuotaUiEvent.ShowDialog(DailyQuotaDialog.NoticeInterval))
                        },
                    )
                    ClickablePreferenceItem(
                        title = stringResource(id = R.string.quota_today),
                        description = stringResource(
                            id = R.string.quota_today_value,
                            state.watchedMinutes,
                            state.remainingMinutes,
                        ),
                        icon = NextIcons.DashBoard,
                        enabled = false,
                        isLastItem = true,
                    )
                }
            }

            // 奖励方案放在额度之下：家长先想清楚「每天给多久」，才有「额外能赚多久」的概念。
            ListSectionTitle(text = stringResource(id = R.string.quota_reward_section))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                if (state.rules.isEmpty()) {
                    ClickablePreferenceItem(
                        title = stringResource(id = R.string.quota_reward_empty),
                        description = null,
                        icon = NextIcons.Headset,
                        enabled = false,
                        isFirstItem = true,
                        isLastItem = true,
                    )
                } else {
                    state.rules.forEachIndexed { index, rule ->
                        val granted = state.grantedFor(rule.id)
                        val used = state.usedFor(rule.id)
                        // 一行里同时给三件事：规则本身、今天系统算到多少、今天是否已到账。
                        // 少任何一个，「学满了不给时长」这类反馈都无法在设置页自查。
                        val parts = buildList {
                            add(stringResourcedRule(rule))
                            if (used != null) {
                                add(stringResource(id = R.string.quota_rule_used, used))
                            }
                            if (granted > 0) {
                                add(stringResource(id = R.string.quota_rule_granted, granted))
                            }
                        }
                        ClickablePreferenceItem(
                            title = rule.appLabel.ifBlank { rule.packageName },
                            description = parts.joinToString(" · "),
                            icon = NextIcons.Headset,
                            onClick = {
                                onAction(
                                    DailyQuotaUiEvent.ShowDialog(
                                        DailyQuotaDialog.EditRule(rule.id),
                                    ),
                                )
                            },
                            isFirstItem = index == 0,
                            isLastItem = index == state.rules.lastIndex,
                        )
                    }
                }

                ClickablePreferenceItem(
                    title = stringResource(id = R.string.quota_add_rule),
                    description = stringResource(id = R.string.quota_add_rule_desc),
                    icon = NextIcons.Selection,
                    onClick = { onAction(DailyQuotaUiEvent.ShowDialog(DailyQuotaDialog.PickApp)) },
                    isFirstItem = state.rules.isEmpty(),
                    isLastItem = true,
                )
            }

            // 授权状态单独一张卡：没开权限时「配了规则但不涨」是最难自己诊断的故障，
            // 必须在配置页正面写出来，并给出直达系统设置的入口。
            ListSectionTitle(text = stringResource(id = R.string.quota_permission_ok))
            Column(
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                ClickablePreferenceItem(
                    title = if (state.hasUsageAccess) {
                        stringResource(id = R.string.quota_permission_ok)
                    } else {
                        stringResource(id = R.string.quota_permission_missing)
                    },
                    description = if (state.hasUsageAccess) {
                        stringResource(id = R.string.quota_permission_ok_desc)
                    } else {
                        stringResource(id = R.string.quota_permission_missing_desc)
                    },
                    icon = NextIcons.Lock,
                    onClick = { onAction(DailyQuotaUiEvent.OpenUsageAccess) },
                    isFirstItem = true,
                    isLastItem = true,
                )
            }
        }
    }

    state.dialog?.let { dialog ->
        when (dialog) {
            DailyQuotaDialog.BaseWeekday -> NumberOptionDialog(
                title = stringResource(id = R.string.quota_base_weekday),
                presets = DailyQuotaPreferences.ALLOWED_BASE_MINUTES,
                current = state.weekdayBaseMinutes,
                min = DailyQuotaPreferences.MIN_BASE_MINUTES,
                max = DailyQuotaPreferences.MAX_BASE_MINUTES,
                unit = stringResource(id = R.string.parent_number_unit_minutes),
                label = { stringResource(id = R.string.quota_base_value, it) },
                onConfirm = {
                    onAction(DailyQuotaUiEvent.SetWeekdayBase(it))
                    onAction(DailyQuotaUiEvent.ShowDialog(null))
                },
                onDismissClick = { onAction(DailyQuotaUiEvent.ShowDialog(null)) },
            )

            DailyQuotaDialog.BaseWeekend -> NumberOptionDialog(
                title = stringResource(id = R.string.quota_base_weekend),
                presets = DailyQuotaPreferences.ALLOWED_BASE_MINUTES,
                current = state.weekendBaseMinutes,
                min = DailyQuotaPreferences.MIN_BASE_MINUTES,
                max = DailyQuotaPreferences.MAX_BASE_MINUTES,
                unit = stringResource(id = R.string.parent_number_unit_minutes),
                label = { stringResource(id = R.string.quota_base_value, it) },
                onConfirm = {
                    onAction(DailyQuotaUiEvent.SetWeekendBase(it))
                    onAction(DailyQuotaUiEvent.ShowDialog(null))
                },
                onDismissClick = { onAction(DailyQuotaUiEvent.ShowDialog(null)) },
            )

            DailyQuotaDialog.MaxReward -> NumberOptionDialog(
                title = stringResource(id = R.string.quota_max_reward),
                presets = listOf(30, 60, 90, 120, 180, 300),
                current = state.maxRewardMinutes,
                min = DailyQuotaPreferences.MIN_MAX_REWARD_MINUTES,
                max = DailyQuotaPreferences.MAX_MAX_REWARD_MINUTES,
                unit = stringResource(id = R.string.parent_number_unit_minutes),
                label = { stringResource(id = R.string.quota_max_reward_value, it) },
                onConfirm = {
                    onAction(DailyQuotaUiEvent.SetMaxReward(it))
                    onAction(DailyQuotaUiEvent.ShowDialog(null))
                },
                onDismissClick = { onAction(DailyQuotaUiEvent.ShowDialog(null)) },
            )

            DailyQuotaDialog.NoticeInterval -> OptionsDialog(
                text = stringResource(id = R.string.quota_notice),
                onDismissClick = { onAction(DailyQuotaUiEvent.ShowDialog(null)) },
            ) {
                items(DailyQuotaPreferences.ALLOWED_NOTICE_INTERVALS) { minutes ->
                    RadioTextButton(
                        text = if (minutes <= 0) {
                            stringResource(id = R.string.quota_notice_off)
                        } else {
                            stringResource(id = R.string.quota_notice_value, minutes)
                        },
                        selected = state.noticeIntervalMinutes == minutes,
                        onClick = {
                            onAction(DailyQuotaUiEvent.SetNoticeInterval(minutes))
                            onAction(DailyQuotaUiEvent.ShowDialog(null))
                        },
                    )
                }
            }

            DailyQuotaDialog.PickApp -> OptionsDialog(
                text = stringResource(id = R.string.quota_pick_app),
                onDismissClick = { onAction(DailyQuotaUiEvent.ShowDialog(null)) },
            ) {
                if (state.apps.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(id = R.string.quota_pick_app_empty),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    items(state.apps) { app ->
                        RadioTextButton(
                            text = app.label,
                            selected = false,
                            onClick = { onAction(DailyQuotaUiEvent.PickApp(app)) },
                        )
                    }
                }
            }

            is DailyQuotaDialog.Required -> NumberOptionDialog(
                title = stringResource(id = R.string.quota_required_title),
                presets = StudyRewardRule.ALLOWED_REQUIRED_MINUTES,
                current = StudyRewardRule.DEFAULT_REQUIRED_MINUTES,
                min = StudyRewardRule.MIN_REQUIRED_MINUTES,
                max = StudyRewardRule.MAX_REQUIRED_MINUTES,
                unit = stringResource(id = R.string.parent_number_unit_minutes),
                label = { stringResource(id = R.string.quota_required_value, it) },
                onConfirm = { minutes ->
                    onAction(
                        DailyQuotaUiEvent.SetRequired(
                            ruleId = dialog.ruleId,
                            packageName = dialog.packageName,
                            label = dialog.label,
                            minutes = minutes,
                            currentRewardMinutes = dialog.rewardMinutes,
                        ),
                    )
                },
                onDismissClick = { onAction(DailyQuotaUiEvent.ShowDialog(null)) },
            )

            is DailyQuotaDialog.Reward -> NumberOptionDialog(
                title = stringResource(id = R.string.quota_reward_title),
                presets = StudyRewardRule.ALLOWED_REWARD_MINUTES,
                current = dialog.rewardMinutes,
                min = StudyRewardRule.MIN_REWARD_MINUTES,
                max = StudyRewardRule.MAX_REWARD_MINUTES,
                unit = stringResource(id = R.string.parent_number_unit_minutes),
                label = { stringResource(id = R.string.quota_reward_value, it) },
                onConfirm = { minutes ->
                    onAction(
                        DailyQuotaUiEvent.SetReward(
                            ruleId = dialog.ruleId,
                            packageName = dialog.packageName,
                            label = dialog.label,
                            requiredMinutes = dialog.requiredMinutes,
                            minutes = minutes,
                        ),
                    )
                },
                onDismissClick = { onAction(DailyQuotaUiEvent.ShowDialog(null)) },
            )

            is DailyQuotaDialog.EditRule -> {
                val rule = state.rules.firstOrNull { it.id == dialog.ruleId }
                if (rule == null) {
                    onAction(DailyQuotaUiEvent.ShowDialog(null))
                } else {
                    NextDialog(
                        onDismissRequest = { onAction(DailyQuotaUiEvent.ShowDialog(null)) },
                        title = {
                            Text(text = rule.appLabel.ifBlank { rule.packageName })
                        },
                        content = {
                            Text(
                                text = stringResourcedRule(rule),
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    onAction(
                                        DailyQuotaUiEvent.ShowDialog(
                                            DailyQuotaDialog.Required(
                                                ruleId = rule.id,
                                                packageName = rule.packageName,
                                                label = rule.appLabel,
                                                rewardMinutes = rule.rewardMinutesClamped,
                                            ),
                                        ),
                                    )
                                },
                            ) {
                                Text(stringResource(id = R.string.quota_rule_edit_required))
                            }
                        },
                        dismissButton = {
                            Column {
                                TextButton(
                                    onClick = {
                                        onAction(
                                            DailyQuotaUiEvent.ShowDialog(
                                                DailyQuotaDialog.Reward(
                                                    ruleId = rule.id,
                                                    packageName = rule.packageName,
                                                    label = rule.appLabel,
                                                    requiredMinutes = rule.requiredMinutesClamped,
                                                    rewardMinutes = rule.rewardMinutesClamped,
                                                ),
                                            ),
                                        )
                                    },
                                ) {
                                    Text(stringResource(id = R.string.quota_rule_edit_reward))
                                }
                                TextButton(
                                    onClick = {
                                        onAction(DailyQuotaUiEvent.DeleteRule(rule.id))
                                    },
                                ) {
                                    Text(stringResource(id = R.string.quota_rule_delete))
                                }
                                CancelButton(
                                    onClick = { onAction(DailyQuotaUiEvent.ShowDialog(null)) },
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 方案行的一行摘要：「学满 30 分钟 → +15 分钟」。 */
@Composable
private fun stringResourcedRule(rule: StudyRewardRule): String = stringResource(
    id = R.string.quota_rule_value,
    rule.requiredMinutesClamped,
    rule.rewardMinutesClamped,
)
