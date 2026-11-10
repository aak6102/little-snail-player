package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.Serializable

/**
 * 防沉迷的全部持久化状态，存放在独立的 rest.json 中（与 study.json 互不影响，
 * 家长重置播放器设置时不会连休息策略一起清掉）。
 *
 * 窗口的逐秒历史用 [windowBytes] 存：长度 == 当前窗口秒数，每字节 0/1。
 * [head] 与 [watchMsInWindow] 是为了快速恢复，避免每次启动重算。
 */
@Serializable
data class RestProgress(
    val preferences: RestPreferences = RestPreferences(),
    /** 滑动窗口桶，长度 == [RestPreferences.windowSeconds]。 */
    val windowBytes: ByteArray = ByteArray(0),
    /** 环形缓冲下一个写入位置（最旧样本下标）。 */
    val head: Int = 0,
    /** 窗口内累计看视频毫秒，恒等于桶中 1 的个数 × 1000。 */
    val watchMsInWindow: Long = 0L,
    /** 上次写入时间戳，用于重启时判断窗口是否过期（过期则整窗作废）。 */
    val lastTickMs: Long = 0L,
    /** 杀进程前是否处于疯狂模式，重进时据此立即恢复拦截。 */
    val crazyActive: Boolean = false,
) {
    /** ByteArray 的相等比较需手动实现，否则 DataStore 更新判断会失真。 */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RestProgress) return false
        return preferences == other.preferences &&
            head == other.head &&
            watchMsInWindow == other.watchMsInWindow &&
            lastTickMs == other.lastTickMs &&
            crazyActive == other.crazyActive &&
            windowBytes.contentEquals(other.windowBytes)
    }

    override fun hashCode(): Int {
        var result = preferences.hashCode()
        result = 31 * result + windowBytes.contentHashCode()
        result = 31 * result + head
        result = 31 * result + watchMsInWindow.hashCode()
        result = 31 * result + lastTickMs.hashCode()
        result = 31 * result + crazyActive.hashCode()
        return result
    }
}
