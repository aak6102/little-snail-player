package dev.anilbeesetti.nextplayer.core.data.repository

import dev.anilbeesetti.nextplayer.core.model.RestPreferences
import dev.anilbeesetti.nextplayer.core.model.RestProgress
import kotlinx.coroutines.flow.StateFlow

/**
 * 防沉迷（休息）状态的读取与写入。
 *
 * 窗口逐秒历史必须落盘，否则杀进程/闪退重进就能把「已经看了 35 分钟」洗掉，
 * 防沉迷形同虚设——和家长锁的 pendingLock 同理。
 */
interface RestRepository {

    val progress: StateFlow<RestProgress>

    val preferences: RestPreferences
        get() = progress.value.preferences

    suspend fun updatePreferences(transform: (RestPreferences) -> RestPreferences)

    /**
     * 持久化滑动窗口与疯狂状态。
     *
     * [buckets]/[head]/[watchMs] 是 [dev.anilbeesetti.nextplayer.core.model.SlidingWindow]
     * 的序列化形态；[crazyActive] 让杀进程前的疯狂模式重进即恢复。
     */
    suspend fun saveWindowState(
        buckets: ByteArray,
        head: Int,
        watchMs: Long,
        crazyActive: Boolean,
    )
}
