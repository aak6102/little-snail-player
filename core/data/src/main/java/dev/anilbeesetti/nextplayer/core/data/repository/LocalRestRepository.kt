package dev.anilbeesetti.nextplayer.core.data.repository

import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.datastore.datasource.RestProgressDataSource
import dev.anilbeesetti.nextplayer.core.model.RestPreferences
import dev.anilbeesetti.nextplayer.core.model.RestProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single
class LocalRestRepository(
    private val restProgressDataSource: RestProgressDataSource,
    @Named(DiQualifiers.APPLICATION_SCOPE) scope: CoroutineScope,
) : RestRepository {

    override val progress: StateFlow<RestProgress> =
        restProgressDataSource.preferences.stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = RestProgress(),
        )

    override suspend fun updatePreferences(transform: (RestPreferences) -> RestPreferences) {
        restProgressDataSource.update { current ->
            val updated = transform(current.preferences)
            if (!updated.enabled) {
                // 关闭防沉迷时必须同时解除疯狂模式，否则下次进播放器会被一道「不该出现的题」挡住
                current.copy(preferences = updated, crazyActive = false)
            } else {
                current.copy(preferences = updated)
            }
        }
    }

    override suspend fun saveWindowState(
        buckets: ByteArray,
        head: Int,
        watchMs: Long,
        crazyActive: Boolean,
    ) {
        restProgressDataSource.update { current ->
            current.copy(
                windowBytes = buckets,
                head = head,
                watchMsInWindow = watchMs,
                lastTickMs = System.currentTimeMillis(),
                crazyActive = crazyActive,
            )
        }
    }
}

/** 供单元测试与 Compose 预览使用的内存实现。 */
class FakeRestRepository(initial: RestProgress = RestProgress()) : RestRepository {

    private val state = MutableStateFlow(initial)

    override val progress: StateFlow<RestProgress> = state

    fun setProgress(progress: RestProgress) {
        state.value = progress
    }

    override suspend fun updatePreferences(transform: (RestPreferences) -> RestPreferences) {
        state.value = state.value.copy(preferences = transform(state.value.preferences))
    }

    override suspend fun saveWindowState(
        buckets: ByteArray,
        head: Int,
        watchMs: Long,
        crazyActive: Boolean,
    ) {
        state.value = state.value.copy(
            windowBytes = buckets,
            head = head,
            watchMsInWindow = watchMs,
            crazyActive = crazyActive,
        )
    }
}
