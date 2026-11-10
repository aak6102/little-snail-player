package dev.anilbeesetti.nextplayer.feature.player.service

import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlin.math.ceil

/**
 * 快进节流播放器。
 *
 * 在完全保留 Next Player 原生能力的前提下，对「跳转(seek)」增加最小时间间隔限制。
 * 间隔与开关都由家长在家长设置页配置（[seekIntervalProvider]），运行中改设置立即生效。
 *
 * 设计要点：
 * - 仅覆写 [handleSeek]，其余所有播放能力（播放/暂停/倍速/字幕/解码器/通知栏/蓝牙控件）
 *   全部透传 [ForwardingSimpleBasePlayer] 的默认实现，与原生行为一致。
 * - 视频自然播放至结尾后从头循环（positionMs == 0）永远放行，否则单集循环会被卡住。
 * - 冷却状态保存在 **companion object 静态字段** 中：跨 [ThrottledSeekPlayer] 实例重建、
 *   跨 [PlayerService] 重建、跨「退出视频再进入」均持续生效，杜绝通过退出生效重置冷却的漏洞。
 * - 冷却期内（含退出再进入同一视频、切换新视频）的跳转请求一律被丢弃（播放位置不变化，
 *   UI 进度条自动回弹），并通过 [onSeekThrottled] 回调告知用户还需等待多久。
 * - 家长关掉节流时 [seekIntervalProvider] 返回 null，此时退回原生行为，不做任何拦截。
 */
@UnstableApi
class ThrottledSeekPlayer(
    player: Player,
    /**
     * 当前生效的快进冷却时长（毫秒）；返回 null 表示家长关掉了节流。
     *
     * 传函数而不是传值，是为了让家长在播放途中改设置能立刻生效——
     * 若在构造时定死，改完设置得重启播放器才管用，等于把开关做成了装饰。
     */
    private val seekIntervalProvider: () -> Long?,
) : ForwardingSimpleBasePlayer(player) {

    /**
     * 冷却期内被拦截的跳转会通过此回调通知上层（例如弹 Toast 提示剩余等待时间）。
     * 参数为「还需等待的毫秒数」。
     */
    var onSeekThrottled: ((remainingMillis: Long) -> Unit)? = null

    /** 暴露底层真实 ExoPlayer，供 PlayerService 内部需要 ExoPlayer 类型的能力使用 */
    val realPlayer: ExoPlayer
        get() = player as ExoPlayer

    /** 上次提示的时间戳，用于给提示去抖，避免拖动进度条时连续刷屏 */
    @Volatile
    private var lastNotifyAtMs = 0L

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        // 循环播放（positionMs == 0）一律放行（Media3 内部循环/起点定位依赖此调用）
        if (positionMs == 0L) {
            return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }
        val intervalMs = seekIntervalProvider() ?: return super.handleSeek(
            mediaItemIndex,
            positionMs,
            seekCommand,
        )
        if (intervalMs <= 0L) {
            return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        }

        val now = System.currentTimeMillis()
        // 冷却期内：吞掉本次跳转，播放位置保持不变，并提示剩余等待时间。
        // 注意：此处不区分「是否新视频/是否重新进入」，冷却状态为全局静态，退出再进入也不会重置。
        if (hasSeekedOnce && now - lastSeekAtMs < intervalMs) {
            val remainingMillis = intervalMs - (now - lastSeekAtMs)
            if (now - lastNotifyAtMs > SEEK_THROTTLE_NOTIFY_INTERVAL_MS) {
                lastNotifyAtMs = now
                onSeekThrottled?.invoke(remainingMillis)
            }
            return Futures.immediateVoidFuture()
        }
        hasSeekedOnce = true
        lastSeekAtMs = now
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    /**
     * 不经过冷却限制的「程序化跳转」（用于恢复断点进度）。
     * 直接对底层 ExoPlayer 执行 seek，绕开 [handleSeek]，因此既不会被冷却拦截，
     * 也不会刷新冷却时间戳 [lastSeekAtMs]，确保「恢复进度」与「用户手动快进」互不干扰。
     */
    fun seekToWithoutThrottle(positionMs: Long) {
        realPlayer.seekTo(positionMs)
    }

    companion object {
        /**
         * 上次成功快进的时间戳（全局静态）。
         * 静态字段在 ThrottledSeekPlayer / PlayerService 重建后依然保留，
         * 因此「退出视频再进入」无法重置冷却。
         */
        @Volatile
        private var lastSeekAtMs = 0L

        /**
         * 本次进程内是否成功快进过。
         *
         * 用显式标志而不是把 [lastSeekAtMs] 初始化成负值：间隔改成可配置后，
         * 「负的初始值」得跟着配置走，一旦家长把间隔调到 10 分钟就会出现
         * 启动后头 10 分钟都拦不住的反向漏洞。标志位与配置完全解耦。
         */
        @Volatile
        private var hasSeekedOnce = false
    }
}

/** 冷却提示的去抖间隔（毫秒）：两次提示之间至少间隔 1.5 秒 */
const val SEEK_THROTTLE_NOTIFY_INTERVAL_MS: Long = 1_500L

/** 把毫秒转为向上取整的秒数（用于提示文案） */
fun seekMillisToSeconds(millis: Long): Int = ceil(millis / 1000.0).toInt()
