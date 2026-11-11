package dev.anilbeesetti.nextplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滑动窗口的数学必须钉死，它是防沉迷「看满就拦」的唯一判据。
 *
 * 这里锁住三件事：任一时刻 [SlidingWindow.watchMsInWindow] 必须精确等于窗口内看视频的秒数；
 * 环形覆盖不能算错（最旧的样本滚出、最新的滚入，计数随之 ±1000）；
 * 以及持久化往返（toBytes / restore）不能把计数带偏——否则杀进程重进就能把「已看 35 分钟」洗掉。
 */
class SlidingWindowTest {

    @Test
    fun `空窗口的看视频时长为 0`() {
        val w = SlidingWindow.create(10)
        assertEquals(0L, w.watchMsInWindow)
        assertEquals(10, w.windowSeconds)
    }

    @Test
    fun `连续看满整窗后时长为窗口秒数`() {
        val w = SlidingWindow.create(10)
        repeat(10) { w.push(true) }
        assertEquals(10_000L, w.watchMsInWindow)
    }

    @Test
    fun `不看能把已看时长逐步清零`() {
        val w = SlidingWindow.create(10)
        repeat(10) { w.push(true) }
        assertEquals(10_000L, w.watchMsInWindow)
        repeat(10) { w.push(false) }
        assertEquals(0L, w.watchMsInWindow)
    }

    @Test
    fun `环形覆盖：最旧的一秒滚出、最新的一秒滚入`() {
        val w = SlidingWindow.create(3)
        repeat(3) { w.push(true) }
        assertEquals(3_000L, w.watchMsInWindow)
        // 再推一个 true：最早那秒（true）被挤出，净变化 0
        w.push(true)
        assertEquals(3_000L, w.watchMsInWindow)
        // 推一个 false：挤出的是一秒 true，计数 −1000
        w.push(false)
        assertEquals(2_000L, w.watchMsInWindow)
        w.push(false)
        assertEquals(1_000L, w.watchMsInWindow)
        w.push(false)
        assertEquals(0L, w.watchMsInWindow)
    }

    @Test
    fun `反复横跳不会让计数越界或变负`() {
        val w = SlidingWindow.create(5)
        repeat(100) {
            w.push(it % 2 == 0)
            assertTrue("watchMs 不应为负", w.watchMsInWindow >= 0L)
            assertTrue("watchMs 不应超过整窗", w.watchMsInWindow <= 5_000L)
        }
    }

    @Test
    fun `持久化往返保留看视频时长`() {
        val w = SlidingWindow.create(5)
        repeat(5) { w.push(true) }
        val restored = SlidingWindow.restore(w.toBytes(), w.headValue(), w.watchMsInWindow)
        assertEquals(5_000L, restored.watchMsInWindow)
        assertEquals(5, restored.windowSeconds)
        // 恢复后继续推进，计数继续累加（证明 head 也恢复对了）
        restored.push(true)
        assertEquals(5_000L, restored.watchMsInWindow)
    }

    @Test
    fun `恢复空桶不会抛异常且退化为合法窗口`() {
        val restored = SlidingWindow.restore(ByteArray(0), 0, 0L)
        assertTrue("空桶至少要有一个槽", restored.windowSeconds >= 1)
        assertEquals(0L, restored.watchMsInWindow)
    }

    @Test
    fun `恢复时越界的 head 被夹回合法区间`() {
        val buckets = ByteArray(4) { 1 }
        // head 远超长度：应被 coerceIn 到 [0, size-1]，而不是越界读桶
        val restored = SlidingWindow.restore(buckets, 999, 4_000L)
        assertEquals(4, restored.windowSeconds)
        // watchMs 以桶内容为准（4 个 1 → 4000），不采信传入的越界 head
        assertEquals(4_000L, restored.watchMsInWindow)
    }

    @Test
    fun `补账：长空档按休息把整窗刷掉`() {
        val w = SlidingWindow.create(10)
        repeat(10) { w.push(true) }
        assertEquals(10_000L, w.watchMsInWindow)

        // 关机 8 小时：只需推满一整窗，结果等价于整窗作废
        w.advance(8 * 60 * 60L, watching = false)
        assertEquals(0L, w.watchMsInWindow)
    }

    @Test
    fun `补账：空档逐秒抵掉已看时长，最后一秒记真实状态`() {
        val w = SlidingWindow.create(10)
        repeat(10) { w.push(true) }

        // 隔了 4 秒才回来：前 3 秒记休息，最后 1 秒按「正在看」
        w.advance(4L, watching = true)
        assertEquals(7_000L, w.watchMsInWindow)
    }

    @Test
    fun `补账：gap 小于 1 秒等同正常推一格`() {
        val w = SlidingWindow.create(5)
        // 0 与负数（时钟回拨）都应退化成「推进 1 秒」，不能把窗口推乱
        w.advance(0L, watching = true)
        w.advance(-3L, watching = true)
        assertEquals(2_000L, w.watchMsInWindow)
    }
}
