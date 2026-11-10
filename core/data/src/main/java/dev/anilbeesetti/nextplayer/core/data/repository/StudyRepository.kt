package dev.anilbeesetti.nextplayer.core.data.repository

import dev.anilbeesetti.nextplayer.core.model.PendingQuizLock
import dev.anilbeesetti.nextplayer.core.model.StudyPreferences
import dev.anilbeesetti.nextplayer.core.model.StudyProgress
import kotlinx.coroutines.flow.StateFlow

/**
 * 学习模式的读取与写入。
 *
 * 进度写入是「答题锁」能否防绕过的前提：待答题状态必须落盘，
 * 否则杀进程重进就能把锁清掉。
 */
interface StudyRepository {

    val progress: StateFlow<StudyProgress>

    val preferences: StudyPreferences
        get() = progress.value.preferences

    suspend fun updatePreferences(transform: (StudyPreferences) -> StudyPreferences)

    suspend fun setPendingLock(lock: PendingQuizLock?)

    /**
     * 记录一次作答结果，同时维护错题池（连续答对 3 次移出）与今日统计。
     */
    suspend fun recordAnswer(statKey: String, correct: Boolean, nowMs: Long)
}
