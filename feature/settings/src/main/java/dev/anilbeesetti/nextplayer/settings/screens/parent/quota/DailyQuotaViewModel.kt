package dev.anilbeesetti.nextplayer.settings.screens.parent.quota

import androidx.lifecycle.viewModelScope
import dev.anilbeesetti.nextplayer.core.data.repository.DailyQuotaRepository
import dev.anilbeesetti.nextplayer.core.data.repository.DailyQuotaUsageReader
import dev.anilbeesetti.nextplayer.core.data.repository.InstalledApp
import dev.anilbeesetti.nextplayer.core.model.DailyQuotaPreferences
import dev.anilbeesetti.nextplayer.core.model.DailyQuotaProgress
import dev.anilbeesetti.nextplayer.core.model.StudyRewardRule
import dev.anilbeesetti.nextplayer.core.ui.base.MviViewModel
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

/**
 * 每日观看额度的家长设置页。
 *
 * 这一页只做两件事：配「基本时长 + 奖励方案」，以及让家长看得见今天的实际数字。
 * 它挂在家长设置路由之下，因此进门已经过了 PIN——这里不再重复校验。
 *
 * 一个刻意的取舍：**所有配置项都在开关打开后才显示**。额度是个低频功能，
 * 关着的时候把五个数字摊在页面上只会让家长以为这东西一直在生效。
 */
@KoinViewModel
class DailyQuotaViewModel(
    private val quotaRepository: DailyQuotaRepository,
    private val usageReader: DailyQuotaUsageReader,
    @InjectedParam internal var output: Output,
) : MviViewModel<DailyQuotaUiState, DailyQuotaUiEvent>() {

    data class Output(
        val navigateUp: () -> Unit,
        /** 跳到系统「使用情况访问权限」页面。App 无法自己弹授权框，只能这么引导。 */
        val openUsageAccessSettings: () -> Unit,
    )

    private val stateInternal = MutableStateFlow(DailyQuotaUiState())
    override val state: StateFlow<DailyQuotaUiState> = stateInternal.asStateFlow()

    init {
        viewModelScope.launch {
            quotaRepository.progress.collectLatest { progress ->
                publish(progress)
            }
        }
        refreshUsageAccess()
        loadApps()
    }

    override fun onAction(action: DailyQuotaUiEvent) {
        when (action) {
            DailyQuotaUiEvent.NavigateUp -> output.navigateUp()
            DailyQuotaUiEvent.ToggleEnabled -> toggleEnabled()
            DailyQuotaUiEvent.OpenUsageAccess -> output.openUsageAccessSettings()
            DailyQuotaUiEvent.RefreshUsageAccess -> {
                refreshUsageAccess()
                loadApps()
            }

            is DailyQuotaUiEvent.ShowDialog -> {
                stateInternal.update { it.copy(dialog = action.dialog) }
            }

            is DailyQuotaUiEvent.SetBaseMinutes -> updatePreferences {
                it.copy(baseMinutes = action.minutes)
            }

            is DailyQuotaUiEvent.SetMaxReward -> updatePreferences {
                it.copy(maxRewardMinutes = action.minutes)
            }

            is DailyQuotaUiEvent.SetNoticeInterval -> updatePreferences {
                it.copy(noticeIntervalMinutes = action.minutes)
            }

            is DailyQuotaUiEvent.PickApp -> {
                stateInternal.update {
                    it.copy(
                        dialog = DailyQuotaDialog.Required(
                            ruleId = null,
                            packageName = action.app.packageName,
                            label = action.app.label,
                            rewardMinutes = StudyRewardRule.DEFAULT_REWARD_MINUTES,
                        ),
                    )
                }
            }

            is DailyQuotaUiEvent.SetRequired -> confirmRequired(action)
            is DailyQuotaUiEvent.SetReward -> confirmReward(action)
            is DailyQuotaUiEvent.DeleteRule -> deleteRule(action.ruleId)
        }
    }

    private fun publish(progress: DailyQuotaProgress) {
        val prefs = progress.preferences
        stateInternal.update { current ->
            current.copy(
                enabled = prefs.enabled,
                baseMinutes = prefs.baseMinutesClamped,
                maxRewardMinutes = prefs.maxRewardMinutesClamped,
                noticeIntervalMinutes = prefs.noticeIntervalMinutesClamped,
                rules = prefs.rewardRules,
                watchedMinutes = progress.watchedMinutes,
                remainingMinutes = progress.remainingMinutes,
                totalMinutes = (progress.totalMs / 60_000L).toInt(),
                grantedMinutes = progress.grantedMinutes,
                grantedByRule = progress.granted,
                isExhausted = progress.isExhausted,
            )
        }
    }

    private fun refreshUsageAccess() {
        val granted = usageReader.hasUsageAccess()
        stateInternal.update { it.copy(hasUsageAccess = granted) }
    }

    private fun loadApps() {
        viewModelScope.launch {
            val apps = withContext(Dispatchers.IO) { usageReader.launchableApps() }
            stateInternal.update { it.copy(apps = apps, appsLoaded = true) }
        }
    }

    private fun toggleEnabled() {
        val next = !state.value.enabled
        updatePreferences { it.copy(enabled = next) }
    }

    private fun updatePreferences(transform: (DailyQuotaPreferences) -> DailyQuotaPreferences) {
        viewModelScope.launch {
            quotaRepository.updatePreferences(transform)
        }
    }

    /**
     * 达标时长确认。
     *
     * 新建方案时这里只收集达标分钟，确认后再进第二步问奖励分钟——
     * 一次弹窗只问一件事，家长不容易漏填（早先把两个数字塞进同一页，
     * 结果所有人都只改了第一个）。
     */
    private fun confirmRequired(action: DailyQuotaUiEvent.SetRequired) {
        stateInternal.update {
            it.copy(
                dialog = DailyQuotaDialog.Reward(
                    ruleId = action.ruleId,
                    packageName = action.packageName,
                    label = action.label,
                    requiredMinutes = action.minutes,
                    rewardMinutes = action.currentRewardMinutes,
                ),
            )
        }
    }

    private fun confirmReward(action: DailyQuotaUiEvent.SetReward) {
        val ruleId = action.ruleId
        val newRule = StudyRewardRule(
            id = ruleId ?: UUID.randomUUID().toString(),
            packageName = action.packageName,
            appLabel = action.label,
            requiredMinutes = action.requiredMinutes,
            rewardMinutes = action.minutes,
        )
        updatePreferences { prefs ->
            val rules = if (ruleId == null) {
                // 新规则插到最前面：刚配完的那条家长还要回头确认一眼，落到列表底部会找不到。
                listOf(newRule) + prefs.rewardRules
            } else {
                prefs.rewardRules.map { if (it.id == ruleId) newRule else it }
            }
            prefs.copy(rewardRules = rules)
        }
        closeDialog()
    }

    private fun deleteRule(ruleId: String) {
        updatePreferences { prefs ->
            prefs.copy(rewardRules = prefs.rewardRules.filterNot { it.id == ruleId })
        }
        closeDialog()
    }

    private fun closeDialog() {
        stateInternal.update { it.copy(dialog = null) }
    }
}

sealed interface DailyQuotaUiEvent {
    data object NavigateUp : DailyQuotaUiEvent
    data object ToggleEnabled : DailyQuotaUiEvent
    data object OpenUsageAccess : DailyQuotaUiEvent
    data object RefreshUsageAccess : DailyQuotaUiEvent
    data class ShowDialog(val dialog: DailyQuotaDialog?) : DailyQuotaUiEvent
    data class SetBaseMinutes(val minutes: Int) : DailyQuotaUiEvent
    data class SetMaxReward(val minutes: Int) : DailyQuotaUiEvent
    data class SetNoticeInterval(val minutes: Int) : DailyQuotaUiEvent
    data class PickApp(val app: InstalledApp) : DailyQuotaUiEvent

    data class SetRequired(
        val ruleId: String?,
        val packageName: String,
        val label: String,
        val minutes: Int,
        val currentRewardMinutes: Int,
    ) : DailyQuotaUiEvent

    data class SetReward(
        val ruleId: String?,
        val packageName: String,
        val label: String,
        val requiredMinutes: Int,
        val minutes: Int,
    ) : DailyQuotaUiEvent

    data class DeleteRule(val ruleId: String) : DailyQuotaUiEvent
}

sealed interface DailyQuotaDialog {
    data object BaseMinutes : DailyQuotaDialog
    data object MaxReward : DailyQuotaDialog
    data object NoticeInterval : DailyQuotaDialog
    data object PickApp : DailyQuotaDialog

    data class Required(
        val ruleId: String?,
        val packageName: String,
        val label: String,
        val rewardMinutes: Int,
    ) : DailyQuotaDialog

    data class Reward(
        val ruleId: String?,
        val packageName: String,
        val label: String,
        val requiredMinutes: Int,
        val rewardMinutes: Int,
    ) : DailyQuotaDialog

    data class EditRule(val ruleId: String) : DailyQuotaDialog
}

data class DailyQuotaUiState(
    val enabled: Boolean = false,
    val baseMinutes: Int = DailyQuotaPreferences.DEFAULT_BASE_MINUTES,
    val maxRewardMinutes: Int = DailyQuotaPreferences.DEFAULT_MAX_REWARD_MINUTES,
    val noticeIntervalMinutes: Int = DailyQuotaPreferences.DEFAULT_NOTICE_INTERVAL_MINUTES,
    val rules: List<StudyRewardRule> = emptyList(),
    val watchedMinutes: Int = 0,
    val remainingMinutes: Int = 0,
    val totalMinutes: Int = 0,
    val grantedMinutes: Int = 0,
    val grantedByRule: Map<String, Int> = emptyMap(),
    val isExhausted: Boolean = false,
    val hasUsageAccess: Boolean = false,
    val apps: List<InstalledApp> = emptyList(),
    val appsLoaded: Boolean = false,
    val dialog: DailyQuotaDialog? = null,
) {
    /** 已发放奖励对应的规则名，用于在方案行上标「今日已到账」。 */
    fun grantedFor(ruleId: String): Int = grantedByRule[ruleId] ?: 0
}
