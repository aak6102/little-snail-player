package dev.anilbeesetti.nextplayer.feature.player.study

import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.data.repository.LicenseRepository
import dev.anilbeesetti.nextplayer.core.data.repository.RestRepository
import dev.anilbeesetti.nextplayer.core.model.RestPreferences
import dev.anilbeesetti.nextplayer.core.model.RestProgress
import dev.anilbeesetti.nextplayer.core.model.SlidingWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

/**
 * 防沉迷（休息）状态机。
 *
 * 和 [QuizGate] 并列的两套拦截：学习锁按「累计有效播放时长」出题练英语，
 * 这里按「滑动窗口内的看视频时长」强制休息。两者共用 [QuizGate]——
 * 一旦窗口内看视频超过阈值，就把答题锁切到 1 分钟 1 词的「疯狂」节奏，
 * 用既有的答题浮层打断心流；唯一退出办法是真歇够要求的时长，让窗口回落到阈值以下。
 *
 * 计时口径刻意和学习锁不同：学习锁只在「真在播」时累加；
 * 这里[onTick]每秒都推进窗口、[progressing]决定这一秒算不算看——
 * 暂停发呆也算没休息，只有后台/息屏那种「真的离开」才计入休息。
 */
@Single
class RestGuard(
    private val restRepository: RestRepository,
    private val quizGate: QuizGate,
    private val licenseRepository: LicenseRepository,
    @Named(DiQualifiers.APPLICATION_SCOPE) private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(RestUiState())
    val state: StateFlow<RestUiState> = _state.asStateFlow()

    /** 滑动窗口（每秒一个槽）。懒初始化：首次 [onTick] 或 [restore] 时按当前窗口大小建好。 */
    private var window: SlidingWindow? = null

    /** 此刻是否处于疯狂模式（内存镜像，避免每次都写状态判断）。 */
    private var crazyOn: Boolean = false

    /** 预警浮层是否已被孩子「知道了」收起，避免每轮都硬弹。离开预警会复位。 */
    private var preWarnAcknowledged: Boolean = false

    /** 最近一次落盘时间戳，用于把 1 秒一次的推进节流到每 30 秒写一次。 */
    private var lastPersistMs: Long = 0L

    /**
     * 上一次记账的墙钟时间（毫秒），用来发现「App 刚才不在场」。
     *
     * 窗口本身只认 [onTick] 的调用次数，进程一死就停摆；靠这个时间戳在恢复后
     * 把断电/杀进程/系统冻结的那段空档一次性补进窗口（算休息）。
     * 0 表示未知（老版本存档里没有可用时间），此时不补账，保证不会把窗口误清空。
     */
    private var lastTickAtMs: Long = 0L

    /** 上次落盘时的疯狂状态，用于「状态一变就立即落盘」，让重启能立刻恢复拦截。 */
    private var lastPersistedCrazy: Boolean = false

    /**
     * 防沉迷此刻是否该拦人：家长开着 **且** 本地授权有效。
     *
     * 沿用学习锁的判定，避免设置页/授权被绕过——这里是每秒计时都会经过的唯一关口。
     * 判否时只是「不拦」，绝不弹付费墙。
     */
    private fun isEffective(): Boolean =
        licenseRepository.statusNow().pro && restRepository.preferences.enabled

    /**
     * 每秒由 [dev.anilbeesetti.nextplayer.feature.player.service.PlayerService] 调用。
     *
     * [progressing] 是 `isPlaying && STATE_READY`：这一秒算「在看」（true）还是「没看」（false）。
     * 窗口每推进一格，无论是否在看——这样暂停发呆不会帮你凑够休息。
     *
     * 推进的格数按墙钟算：上一次 tick 到现在隔了几秒就推几格，中间的空档记「没看」。
     * App 被关掉/杀掉/被系统冻结时 ticker 停了，重启后靠这个把整段不在场时间补成休息。
     */
    fun onTick(progressing: Boolean) {
        val prefs = restRepository.preferences
        if (!isEffective()) {
            // 关闭或授权失效：立刻解除疯狂、清空窗口、复位状态，别把孩子晾在答题页
            if (crazyOn) { crazyOn = false; quizGate.setCrazyMode(false) }
            window = null
            if (_state.value.phase != RestPhase.IDLE) _state.value = RestUiState()
            return
        }

        val w = getWindow(prefs)
        // 先按墙钟补账，再推这一秒：gap > 1 说明上一次 tick 到现在隔了好几秒，
        // 多出来的部分是「App 不在场」（关机、杀进程、系统冻结），要按休息补进窗口。
        // 少了这一步，看满阈值的窗口会一直冻着，孩子关一晚机回来还是一进去就被拦。
        val now = System.currentTimeMillis()
        val gapSeconds = if (lastTickAtMs > 0L) (now - lastTickAtMs) / 1000L else 0L
        lastTickAtMs = now
        w.advance(gapSeconds, progressing)

        val watchMs = w.watchMsInWindow
        val threshold = prefs.thresholdWatchMs
        val preWarn = prefs.preWarnMs
        val phase = when {
            watchMs >= threshold -> RestPhase.CRAZY
            watchMs >= threshold - preWarn -> RestPhase.PRE_WARN
            else -> RestPhase.IDLE
        }
        applyPhase(phase, watchMs, threshold, prefs)
        persistMaybe(w)
    }

    /**
     * 杀进程/闪退重进时恢复窗口历史，让「已经看了 35 分钟」不会被洗掉。
     * 每次拿到媒体项都调一次即可（方法内部对已建好的窗口是幂等的）。
     */
    fun restore() {
        if (!isEffective()) return
        getWindow(restRepository.preferences)
    }

    /**
     * 孩子点「知道了」暂时收起预警浮层，直到离开预警区间再恢复。
     *
     * 只改内存标记 + UI 可见性，不落盘：这是纯展示层行为，重启后由窗口历史重新推算阶段，
     * 该不该弹预警会自然恢复，无需持久化「已读」状态。
     */
    fun acknowledgePreWarn() {
        preWarnAcknowledged = true
        if (_state.value.phase == RestPhase.PRE_WARN) {
            _state.value = _state.value.copy(preWarnVisible = false)
        }
    }

    private fun getWindow(prefs: RestPreferences): SlidingWindow {
        val existing = window
        if (existing != null && existing.windowSeconds == prefs.windowSeconds) return existing
        val progress = restRepository.progress.value
        val w = if (progress.windowBytes.size == prefs.windowSeconds) {
            SlidingWindow.restore(progress.windowBytes, progress.head, progress.watchMsInWindow)
        } else {
            SlidingWindow.create(prefs.windowSeconds)
        }
        window = w
        // 计数基准跟着恢复的时间戳走：gap 从「上次落盘的 tick」算起，
        // 不能从此刻算起，否则关机那段会被当成 0 秒，窗口照旧冻住。
        lastTickAtMs = progress.lastTickMs
        return w
    }

    private fun applyPhase(phase: RestPhase, watchMs: Long, threshold: Long, prefs: RestPreferences) {
        val prevPhase = _state.value.phase
        // 只有「离开预警区间」才复位「已知道」标记：孩子点过「知道了」后，
        // 只要还待在预警区间内就别再硬弹；一旦回到 IDLE 或进入 CRAZY（即真的离开了预警），
        // 下次再滑回预警区间浮层会重新弹起，避免「知道了一次就永久静音」。
        if (prevPhase == RestPhase.PRE_WARN && phase != RestPhase.PRE_WARN) {
            preWarnAcknowledged = false
        }
        when (phase) {
            RestPhase.CRAZY -> {
                if (!crazyOn) {
                    crazyOn = true
                    quizGate.setCrazyMode(true, prefs.crazyIntervalMinutesClamped)
                }
                if (prevPhase != RestPhase.CRAZY) _state.value = RestUiState(RestPhase.CRAZY)
            }
            RestPhase.PRE_WARN -> {
                if (crazyOn) {
                    crazyOn = false
                    quizGate.setCrazyMode(false)
                }
                val left = ((threshold - watchMs) / 1000).toInt().coerceAtLeast(0)
                val prev = _state.value
                // 已「知道了」就别再硬弹；仍持续追踪剩余秒数，临界点一到立即切疯狂。
                if (prev.phase != RestPhase.PRE_WARN || prev.preWarnSecondsLeft != left) {
                    _state.value = RestUiState(
                        phase = RestPhase.PRE_WARN,
                        preWarnSecondsLeft = left,
                        preWarnVisible = !preWarnAcknowledged,
                    )
                }
            }
            RestPhase.IDLE -> {
                if (crazyOn) {
                    crazyOn = false
                    quizGate.setCrazyMode(false)
                }
                if (prevPhase != RestPhase.IDLE) _state.value = RestUiState(RestPhase.IDLE)
            }
        }
    }

    /** 落盘：疯狂状态一变就立即写，平时每 30 秒写一次，避免每秒都碰 DataStore。 */
    private fun persistMaybe(w: SlidingWindow) {
        val now = System.currentTimeMillis()
        val crazyChanged = crazyOn != lastPersistedCrazy
        val due = now - lastPersistMs >= PERSIST_INTERVAL_MS
        if (!crazyChanged && !due) return
        lastPersistMs = now
        lastPersistedCrazy = crazyOn
        scope.launch {
            restRepository.saveWindowState(
                buckets = w.toBytes(),
                head = w.headValue(),
                watchMs = w.watchMsInWindow,
                crazyActive = crazyOn,
            )
        }
    }

    companion object {
        /** 正常推进每 30 秒落盘一次；状态切换即时落盘。 */
        private const val PERSIST_INTERVAL_MS = 30_000L
    }
}

enum class RestPhase { IDLE, PRE_WARN, CRAZY }

data class RestUiState(
    val phase: RestPhase = RestPhase.IDLE,
    /** 预警阶段剩余秒数（进入疯狂前还有多久）。 */
    val preWarnSecondsLeft: Int = 0,
    /** 预警浮层当前是否可见（被「知道了」收起时为 false）。 */
    val preWarnVisible: Boolean = true,
)
