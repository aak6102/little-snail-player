package dev.anilbeesetti.nextplayer.core.model

/**
 * 每秒一个槽的滑动窗口，记录「这一秒是否在看视频」。
 *
 * 直接挂在播放器的 progressing 信号上（[dev.anilbeesetti.nextplayer.feature.player.service.PlayerService]
 * 里 `isPlaying && STATE_READY`）：看就写 1，暂停/缓冲/后台都写 0。窗口长度等于
 * [RestPreferences.windowSeconds]，用环形缓冲避免每帧整体平移。
 *
 * 纯逻辑、无 Android 依赖，方便单测钉住「40 分钟窗口内休息不足就触发」这条规则。
 *
 * @param buckets 长度必须 == [windowSeconds]，每字节 0（没看）/1（看了）。
 * @param head 下一个写入位置，也就是最旧样本所在下标。
 * @param watchMs 窗口内累计看视频毫秒，恒等于 buckets 中 1 的个数 × 1000。
 */
class SlidingWindow private constructor(
    private val buckets: ByteArray,
    private var head: Int,
    private var watchMs: Long,
    val windowSeconds: Int,
) {

    init {
        require(buckets.size == windowSeconds) {
            "buckets.size(${buckets.size}) != windowSeconds($windowSeconds)"
        }
        // 容错：watchMs 与桶内容不一致时以桶为准，避免持久化往返把计数带偏
        val realWatchMs = buckets.fold(0L) { acc, b -> acc + if (b.toInt() and 1 != 0) 1000L else 0L }
        if (realWatchMs != watchMs) watchMs = realWatchMs
    }

    /** 窗口内累计看视频毫秒。 */
    val watchMsInWindow: Long get() = watchMs

    /**
     * 推进一秒：写入当前这一秒的观看状态，最旧的一秒随之滚出窗口。
     *
     * 先对「即将被覆盖的最旧槽」做减法（它是 1 就减 1000），再写入新值（是 1 就加 1000），
     * 最后 head 前移。这样任意时刻 [watchMs] 都精确等于窗口内看视频的秒数。
     */
    fun push(watching: Boolean) {
        if (buckets[head].toInt() and 1 != 0) watchMs -= 1000L
        buckets[head] = if (watching) 1 else 0
        if (watching) watchMs += 1000L
        head = (head + 1) % windowSeconds
    }

    /** 持久化形态：桶内容拷贝。 */
    fun toBytes(): ByteArray = buckets.copyOf()

    /** 持久化形态：当前 head。 */
    fun headValue(): Int = head

    companion object {
        /** 全新空窗口。 */
        fun create(windowSeconds: Int): SlidingWindow =
            SlidingWindow(ByteArray(windowSeconds), 0, 0L, windowSeconds)

        /**
         * 从持久化数据恢复。
         *
         * 越界或长度不符时做兜底：head 夹到合法区间，长度不符就退化成空窗口
         * （老存档/窗口大小变更都走这条，旧数据本就无参考意义）。
         */
        fun restore(buckets: ByteArray, head: Int, watchMs: Long): SlidingWindow {
            if (buckets.isEmpty()) return create(buckets.size.coerceAtLeast(1))
            val safeHead = head.coerceIn(0, buckets.size - 1)
            return SlidingWindow(buckets.copyOf(), safeHead, watchMs, buckets.size)
        }
    }
}
