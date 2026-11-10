package dev.anilbeesetti.nextplayer.core.data.repository

import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.datastore.datasource.StudyProgressDataSource
import dev.anilbeesetti.nextplayer.core.model.PendingQuizLock
import dev.anilbeesetti.nextplayer.core.model.StudyPreferences
import dev.anilbeesetti.nextplayer.core.model.StudyProgress
import dev.anilbeesetti.nextplayer.core.model.migrateStatKeys
import dev.anilbeesetti.nextplayer.core.model.withAnswer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/** minSdk 24，不能用 java.time，日期键统一由 [dev.anilbeesetti.nextplayer.core.model.dayKey] 生成。 */
@Single
class LocalStudyRepository(
    private val studyProgressDataSource: StudyProgressDataSource,
    private val quizBankRepository: QuizBankRepository,
    @Named(DiQualifiers.APPLICATION_SCOPE) scope: CoroutineScope,
) : StudyRepository {

    init {
        // 迁移必须在任何人读出进度之前完成，否则旧的 id 键会被当成陌生词再存一份
        scope.launch { migrateStatKeysIfNeeded() }
    }

    override val progress: StateFlow<StudyProgress> =
        studyProgressDataSource.preferences.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = StudyProgress(),
        )

    override suspend fun updatePreferences(transform: (StudyPreferences) -> StudyPreferences) {
        studyProgressDataSource.update { current ->
            val updated = transform(current.preferences)
            if (!updated.enabled) {
                // 关闭学习模式时必须同时解除锁定，否则下次进播放器会被一道「不该出现的题」挡住
                current.copy(preferences = updated, pendingLock = null)
            } else {
                current.copy(preferences = updated)
            }
        }
    }

    override suspend fun setPendingLock(lock: PendingQuizLock?) {
        studyProgressDataSource.update { it.copy(pendingLock = lock) }
    }

    override suspend fun recordAnswer(statKey: String, correct: Boolean, nowMs: Long) {
        // 记账规则收敛到 core:model 的 withAnswer，Fake 与本实现共用一份，避免两处漂移。
        // 档位上限从当前偏好读：自适应只允许在上限以下降，家长调低上限后偏移量要跟着缩。
        studyProgressDataSource.update { current ->
            current.withAnswer(
                statKey = statKey,
                correct = correct,
                nowMs = nowMs,
                maxLevel = current.preferences.activeLevelClamped,
            )
        }
    }

    /**
     * 触发一次进度主键迁移：把 `wordStats` 的键从词条 id 换成词面小写。
     *
     * 反查用的是**所有**词表的 id 索引：老存档可能来自任何一套词表，
     * 只查当前那套会漏。迁移规则本身在 [migrateStatKeys] 里，那边有单测。
     */
    private suspend fun migrateStatKeysIfNeeded() {
        val snapshot = studyProgressDataSource.preferences.first()
        if (snapshot.preferences.statKeyVersion >= StudyPreferences.STAT_KEY_VERSION_WORD) return

        val wordById = quizBankRepository.wordsByIdAcrossBanks()
        studyProgressDataSource.update { current ->
            current.migrateStatKeys { id -> wordById[id]?.statKey }
        }
    }
}

/** 供单元测试与 Compose 预览使用的内存实现。 */
class FakeStudyRepository(initial: StudyProgress = StudyProgress()) : StudyRepository {

    private val state = MutableStateFlow(initial)

    override val progress: StateFlow<StudyProgress> = state

    fun setProgress(progress: StudyProgress) {
        state.value = progress
    }

    override suspend fun updatePreferences(transform: (StudyPreferences) -> StudyPreferences) {
        state.value = state.value.copy(preferences = transform(state.value.preferences))
    }

    override suspend fun setPendingLock(lock: PendingQuizLock?) {
        state.value = state.value.copy(pendingLock = lock)
    }

    override suspend fun recordAnswer(statKey: String, correct: Boolean, nowMs: Long) {
        state.value = state.value.withAnswer(
            statKey = statKey,
            correct = correct,
            nowMs = nowMs,
            maxLevel = state.value.preferences.activeLevelClamped,
        )
    }
}
