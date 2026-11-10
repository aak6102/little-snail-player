package dev.anilbeesetti.nextplayer.feature.player.study

import android.util.Log
import androidx.media3.common.Player
import dev.anilbeesetti.nextplayer.core.common.di.DiQualifiers
import dev.anilbeesetti.nextplayer.core.data.repository.LicenseRepository
import dev.anilbeesetti.nextplayer.core.data.repository.QuizBankRepository
import dev.anilbeesetti.nextplayer.core.data.repository.StudyRepository
import dev.anilbeesetti.nextplayer.core.model.AdaptiveLevel
import dev.anilbeesetti.nextplayer.core.model.PendingQuizLock
import dev.anilbeesetti.nextplayer.core.model.QuizQuestion
import dev.anilbeesetti.nextplayer.core.model.afterAnswer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import kotlin.random.Random

/**
 * 答题锁状态机。
 *
 * 只做三件事：累计有效播放时长、到点拦住播放、判卷并解锁。
 * 计时口径是「真的在播」——暂停、缓冲、后台都不计时，
 * 因此反复拖进度条或挂着不看都刷不到时长。
 *
 * 之所以是单例而不是放在 Service 内部：播放器 UI 和 Service 在同一个进程，
 * 两边都要读写同一份状态，共享一个状态机比自己造一套 IPC 简单得多。
 */
@Single
class QuizGate(
    private val studyRepository: StudyRepository,
    private val bankSource: QuizBankRepository,
    private val licenseRepository: LicenseRepository,
    @Named(DiQualifiers.APPLICATION_SCOPE) private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(QuizUiState())
    val state: StateFlow<QuizUiState> = _state.asStateFlow()

    private var player: Player? = null
    private var elapsedMs: Long = 0L

    /**
     * 疯狂模式（防沉迷）：由 [RestGuard] 打开。
     *
     * 打开后答题锁切到「每 [crazyIntervalMillis] 毫秒一轮」的紧凑节奏，且答完不进结果页、
     * 直接续播 [crazyIntervalMillis] 毫秒再拦。对 [QuizOverlay] 也可见，用于把标题换成「疯狂答题」。
     *
     * 一轮的**题量**和**难度**不在这里定，而是完全跟随学习答题设置
     * （[dev.anilbeesetti.nextplayer.core.model.StudyPreferences.questionsPerLock] 与难度档位）：
     * 防沉迷只负责「多久拦一次」这一件事，出题口径和学习锁保持一致，避免两套题量/难度互相打架。
     */
    var crazyMode: Boolean = false
        private set
    private var crazyIntervalMillis: Long = 0L

    /**
     * 本轮解锁后需要累计多久才再次拦截。
     * 0 表示跟随偏好设置（全对的情况），非 0 表示上一轮有错、按错题数打了折。
     */
    private var targetMs: Long = 0L

    private var question: QuizQuestion? = null
    private var hasRestoredLock = false
    private val recentWordIds = ArrayDeque<String>()

    /** 本次拦截一共要答几题。 */
    private var sessionTotal: Int = 1

    /** 本次拦截已经完成几题（答对，或错误路径放行）。 */
    private var sessionAnswered: Int = 0

    /** 本次拦截是否出现过错误。这是「浮动解锁」的唯一判据。 */
    private var sessionHadWrong: Boolean = false

    /** 本次拦截一次答对的题数，用于结果页展示。 */
    private var sessionCorrectCount: Int = 0

    /**
     * 本次拦截开始时的降档数。
     *
     * 结果页的「这几题偏难」只在**这一轮真的又降了一档**时出现：
     * 孩子可能已经降了一档在学，每次都提示等于每次都在提醒他「你不行」。
     */
    private var sessionStartOffset: Int = 0

    /** 本次拦截已经出过的词。同一词不在一次拦截里重复出现——那是短时刷题，不是记忆。 */
    private val sessionWordIds = mutableSetOf<String>()

    /**
     * 本轮各题位的职责，在 [lock] 时一次定好。
     *
     * 不在出题时按序号现算：那样「第 2 题固定新词、第 3 题固定还账」的结构
     * 会被孩子摸出来。定好之后再把后两位打乱，结构还在，规律没了。
     */
    private var sessionSlots: List<QuestionSlot> = emptyList()

    /**
     * 自适应难度的内存视图。
     *
     * 不直接读 `progress.value`：`recordAnswer` 是异步落盘的，答完一题立刻出下一题时，
     * StateFlow 往往还停在上一题的状态——读它会让「第三题连错就降级」推迟到下一次拦截，
     * 孩子在该变简单的那一题上仍然撞到难词。
     *
     * 为 null 表示还没被本轮写过，此时读磁盘上的持久化状态。
     */
    private var adaptiveView: AdaptiveLevel? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // 通知栏、蓝牙耳机、MediaSession 都能把播放重新拉起来，
            // 所以「锁定期间不允许播放」必须在播放状态变化处兜住，而不只是在 UI 上拦按钮。
            if (isPlaying) enforceLock()
        }
    }

    fun attach(player: Player) {
        this.player = player
        player.addListener(playerListener)
    }

    fun detach() {
        player?.removeListener(playerListener)
        player = null
    }

    /**
     * 恢复上次未完成的锁：被杀进程 / 闪退打断在答题页时，重进播放仍要作答。
     *
     * 允许被反复调用（每次拿到媒体项都会调一次）：DataStore 首帧可能还没读出来，
     * 读到默认值（未开启）时直接返回，下次媒体切换再试，避免因为一次时序竞争让锁失效。
     */
    fun restorePendingLock() {
        if (_state.value.visible || hasRestoredLock) return
        val progress = studyRepository.progress.value
        // 偏好尚未从磁盘读出时 enabled 为默认 false，此时不能下结论，等下次调用。
        if (!isLockEffective()) return
        hasRestoredLock = true
        if (progress.pendingLock != null) lock()
    }

    /**
     * 学习模式此刻是否该拦人：家长开着 **且** 本地授权有效。
     *
     * 授权判定放在这里而不是只放在设置页，是因为设置页能被绕过（改本地 JSON、装旧版），
     * 而这里是每 1 秒计时都会经过的唯一关口。只堵这一处，成本最低、覆盖最全。
     *
     * 反过来也要克制：判否时只是「不拦」，绝不弹窗、绝不提示付费——
     * 播放界面弹付费墙是最招差评的做法，购买入口只出现在家长设置页。
     */
    private fun isLockEffective(): Boolean =
        licenseRepository.statusNow().pro &&
            studyRepository.preferences.isActiveAt(System.currentTimeMillis())

    /**
     * 由 [RestGuard] 调用：打开/关闭防沉迷「疯狂模式」。
     *
     * 打开后答题锁切到 [intervalMinutes] 分钟一轮的紧凑节奏；一轮的题量与难度
     * 完全跟随学习答题设置（见类上方说明），这里只决定「多久拦一次」。
     * 关闭时仅复位节奏参数，不触及当前进行中的一轮——那一轮按原有逻辑收尾。
     */
    fun setCrazyMode(enabled: Boolean, intervalMinutes: Int = 1) {
        crazyMode = enabled
        crazyIntervalMillis = (intervalMinutes.coerceAtLeast(1) * 60_000L)
    }

    /** 每 1 秒由 PlayerService 调用；[deltaMs] 为 0 表示这一刻不算有效播放。 */
    fun onTick(deltaMs: Long) {
        if (!isLockEffective()) {
            // 家长在答题途中关掉了功能（或授权到期）：立刻放行，别把孩子晾在答题页
            if (_state.value.visible) unlock() else elapsedMs = 0L
            return
        }
        if (deltaMs <= 0L) return
        if (_state.value.visible) return
        elapsedMs += deltaMs
        if (elapsedMs >= effectiveTargetMs()) {
            lock()
        }
    }

    /**
     * 本次的计时目标。
     *
     * 全对后回到家长设的间隔，有错则按错题数打折（60% / 40%，最低 1 分钟）——
     * 这是唯一「立刻疼、但不用吵架」的约束：乱点照样能过，只是没几分钟又要被拦一次。
     * [targetMs] 为 0 时直接读偏好，这样家长中途改间隔能立刻生效。
     * 疯狂模式（防沉迷）优先：一旦打开，计时目标改为 [crazyIntervalMillis]。
     */
    private fun effectiveTargetMs(): Long =
        if (crazyMode && crazyIntervalMillis > 0L) crazyIntervalMillis
        else if (targetMs > 0L) targetMs else studyRepository.preferences.intervalMillis

    fun onOptionSelected(option: String) {
        val current = question ?: return
        val uiState = _state.value
        if (!uiState.visible || uiState.summary != null) return
        // 已排除的选项不接受重复点击，否则孩子乱点就能把错误次数刷到上限
        if (option in uiState.eliminated) return

        if (uiState.revealed) {
            // 揭示答案之后只剩一种合法操作：
            // - 欠账词：必须点中正确答案才算过关（但按「照着抄」记为错，欠账不销）；
            // - 普通词：走「我记住了」按钮，选项一律不可点。
            if (!uiState.mustPickAnswer) return
            // UI 只让答案可点，这里再校验一次：状态机不能把「点哪个都算过」写死，
            // 否则将来排除逻辑一改，欠账词就变成了任意点击放行。
            if (option != current.answer) return
            completeCurrentQuestion(correct = false)
            return
        }

        if (current.isCorrect(option)) {
            completeCurrentQuestion(correct = true)
            return
        }

        // 提示策略：把选错的选项划掉（排除法），而不是给答案文本。
        // 排除法既真实降低了难度，又绝不会提前泄露答案——
        // 之前用「首字母 / 中文释义」提示，对选义题等于直接把正确答案念出来。
        _state.value = uiState.afterWrongPick(option, MAX_WRONG_ATTEMPTS, debtWord = current.debt)
    }

    /** 普通词第 3 次答错后点「我记住了」：记为错题，但放行到下一题。 */
    fun onRevealConfirmed() {
        val uiState = _state.value
        // 欠账词没有这条捷径：看见答案也得自己点一下，避免「我记住了」变成万能按钮
        if (!uiState.revealed || uiState.mustPickAnswer) return
        completeCurrentQuestion(correct = false)
    }

    /** 结果页点「继续观看」：按本轮表现决定这次能看多久。 */
    fun onSummaryConfirmed() {
        val summary = _state.value.summary ?: return
        // 全对 → 0（跟随偏好，家长改间隔立刻生效）；有错 → 用结果页已经算好的分钟数。
        // 这里不重新算一遍：重算要再读一次偏好，万一两步之间家长改了间隔，
        // 就会出现「结果页说 6 分钟、实际只给 4 分钟」的对不上的情况。
        targetMs = if (summary.allCorrect) 0L else summary.nextMinutes * 60_000L
        unlock()
    }

    /**
     * 完成当前这一题：记账，然后要么进下一题，要么整轮结束。
     *
     * 「答对」和「错误路径放行」走同一条推进逻辑，
     * 否则两条路径很容易只改一条，导致答对后卡在第 1 题不动。
     */
    private fun completeCurrentQuestion(correct: Boolean) {
        // 先推进内存里的自适应计数，再记账：下一题的档位要立刻反映这次对错
        val maxLevel = studyRepository.preferences.activeLevelClamped
        adaptiveView = currentAdaptive(maxLevel).afterAnswer(correct, maxLevel)
        if (!correct) sessionHadWrong = true
        if (correct) sessionCorrectCount += 1
        recordAnswer(correct)
        sessionAnswered += 1
        if (sessionAnswered >= sessionTotal) {
            // 疯狂模式：答完本轮立刻续播，不当作「学习轮次」给结果页——
            // 惩罚靠的是「每 N 分钟必须答一轮」本身，而不是浮动解锁那套反馈
            if (crazyMode) unlock() else showSummary()
            return
        }
        if (!showNextQuestion()) {
            // 题库异常：宁可放过，也不能把孩子卡在答题页
            showSummary()
        }
    }

    private fun lock() {
        // 疯狂模式与学习锁共用同一套题量：完全跟随学习答题的「每次题量」设置，
        // 不再各自硬编码。题量只是从 1 变成 study 的 N，其余逻辑（无结果页、答完即续播）不变。
        sessionTotal = studyRepository.preferences.questionsPerLockClamped
        sessionAnswered = 0
        sessionHadWrong = false
        sessionCorrectCount = 0
        sessionSlots = planSlots(sessionTotal)
        sessionWordIds.clear()
        sessionStartOffset = currentAdaptive(studyRepository.preferences.activeLevelClamped).offset

        if (!showNextQuestion()) {
            Log.w(TAG, "No question available, skipping this lock")
            elapsedMs = 0L
            return
        }
        player?.pause()

        val mediaId = player?.currentMediaItem?.mediaId.orEmpty()
        scope.launch {
            studyRepository.setPendingLock(
                PendingQuizLock(mediaId = mediaId, createdAtMs = System.currentTimeMillis()),
            )
        }
    }

    /** 出下一题；题库不可用（返回 false）时由调用方决定放行。 */
    private fun showNextQuestion(): Boolean {
        val index = sessionAnswered
        // 每次出题都重新取一遍：家长在设置里换词表后，下一次拦截就该用新词表，不必重启播放器
        val prefs = studyRepository.preferences
        val bank = bankSource.bank(prefs.activeBankId)
        val maxLevel = prefs.activeLevelClamped
        val question = QuizGenerator.nextQuestion(
            bank = bank,
            progress = studyRepository.progress.value,
            nowMs = System.currentTimeMillis(),
            // 本次已出过的词一并排除，让同一词的重现发生在「下一次」而不是「这一次」
            recentWordIds = recentWordIds.toList() + sessionWordIds,
            // 这一题该干什么：热身建信心、学新词、还是还账
            slot = sessionSlots.getOrElse(index) { QuestionSlot.SMART },
            // 降级后的档位就是这一题的实际上限
            maxLevel = currentAdaptive(maxLevel).effectiveLevel(maxLevel),
        ) ?: return false

        this.question = question
        sessionWordIds += question.statKey
        _state.value = QuizUiState(
            visible = true,
            question = question,
            index = index + 1,
            total = sessionTotal,
        )
        return true
    }

    /**
     * 一轮答完先给结果页，而不是直接续播。
     *
     * 这一页是浮动解锁的「可见后果」：全对 → 看满设定时长；有错 → 只有 5 分钟。
     * 如果答完立刻续播，孩子根本不知道乱点的代价是什么，机制就白做了。
     */
    private fun showSummary() {
        // 最后一题的词也要进「最近出过」列表，否则下一轮可能又是它
        question?.let { rememberRecentWord(it.statKey) }
        question = null

        val allCorrect = !sessionHadWrong
        val maxLevel = studyRepository.preferences.activeLevelClamped
        val prefs = studyRepository.preferences
        // 错题数 = 本轮题量 − 一次答对的题数：两条错误路径（点错、错满三次放行）
        // 都记在 sessionCorrectCount 之外，所以这个减法天然把它们算进去。
        val wrongCount = sessionTotal - sessionCorrectCount
        _state.value = QuizUiState(
            visible = true,
            summary = QuizSummary(
                allCorrect = allCorrect,
                nextMinutes = if (allCorrect) {
                    prefs.intervalMinutes
                } else {
                    prefs.unlockMinutesFor(wrongCount)
                },
                correctCount = sessionCorrectCount,
                total = sessionTotal,
                wrongCount = wrongCount,
                // 只告诉孩子「先练简单的」，不显示档位数字：
                // 说了具体档位，孩子会去算「怎样才会被降档」，那就变成钻规则而不是学词
                downgraded = currentAdaptive(maxLevel).offset > sessionStartOffset,
            ),
        )
        // 题已经答完，这次解锁资格是赚到的：立刻清掉落盘的锁。
        // 否则孩子在结果页被杀进程，回来还得把这一轮重做一遍。
        scope.launch { studyRepository.setPendingLock(null) }
    }

    private fun unlock() {
        question?.let { rememberRecentWord(it.statKey) }
        question = null
        elapsedMs = 0L
        sessionAnswered = 0
        sessionTotal = 1
        sessionHadWrong = false
        sessionCorrectCount = 0
        sessionWordIds.clear()
        _state.value = QuizUiState()
        scope.launch { studyRepository.setPendingLock(null) }
        player?.play()
    }

    private fun rememberRecentWord(statKey: String) {
        recentWordIds.addLast(statKey)
        while (recentWordIds.size > RECENT_WORDS_TO_AVOID) recentWordIds.removeFirst()
    }

    /**
     * 当前自适应状态。
     *
     * 每次都做一次 [AdaptiveLevel.clampedTo]：家长把上限从高中调到小学后，
     * 磁盘里可能还留着 offset=3，不收就会一直停在起步档。
     */
    private fun currentAdaptive(maxLevel: Int): AdaptiveLevel =
        (adaptiveView ?: studyRepository.progress.value.adaptive).clampedTo(maxLevel)

    private fun enforceLock() {
        if (_state.value.visible) player?.pause()
    }

    private fun recordAnswer(correct: Boolean) {
        val statKey = question?.statKey ?: return
        scope.launch {
            studyRepository.recordAnswer(statKey, correct, System.currentTimeMillis())
        }
    }

    companion object {
        private const val TAG = "QuizGate"

        /** 第 3 次答错改为直接揭示答案。 */
        const val MAX_WRONG_ATTEMPTS = 3

        private const val RECENT_WORDS_TO_AVOID = 3
    }
}

data class QuizUiState(
    val visible: Boolean = false,
    val question: QuizQuestion? = null,
    /** 当前是本次拦截的第几题（1 起）。 */
    val index: Int = 1,
    /** 本次拦截一共几题。 */
    val total: Int = 1,
    /** 最近一次选错的选项。 */
    val wrongSelection: String? = null,
    /** 已被排除的错误选项：划掉、不可再点。答错越多剩得越少，但绝不会提前暴露答案。 */
    val eliminated: Set<String> = emptySet(),
    /** 已答错次数，决定提示强度。 */
    val attempt: Int = 0,
    /** 已揭示答案，等待孩子确认。 */
    val revealed: Boolean = false,
    /** 欠账词揭示答案后仍必须自己点中正确答案（不提供「我记住了」这条捷径）。 */
    val mustPickAnswer: Boolean = false,
    /** 本轮结束后的结果页；非空时 [question] 为 null。 */
    val summary: QuizSummary? = null,
)

/** 一轮答题的结果，直接告诉孩子「这次能看多久」。 */
data class QuizSummary(
    val allCorrect: Boolean,
    /** 本次解锁的分钟数。 */
    val nextMinutes: Int,
    /** 一次答对的题数。 */
    val correctCount: Int,
    val total: Int,
    /** 本轮没一次答对的题数，决定 [nextMinutes] 打几折。 */
    val wrongCount: Int = 0,
    /** 当前处于降档状态：结果页给一句鼓励，而不是报档位。 */
    val downgraded: Boolean = false,
)

/**
 * 答错一次后的状态迁移。
 *
 * 单独抽成纯函数，是为了让「错两次不能揭示答案」这条规则能被单元测试直接钉住：
 * 只有第 [maxAttempts] 次答错才允许 [QuizUiState.revealed] 变 true。
 * 之前实现在 UI 层另写了一套「第 2 次就显示正确答案」的提示分支，
 * 状态机本身没揭示、界面却把答案念了出来——同一件事有两个真相，必然出岔子。
 *
 * [debtWord] 为 true 时（欠账词），揭示答案不等于可以走：
 * 还要求点中正确答案，让过关动作里至少有一次真实的判断。
 */
internal fun QuizUiState.afterWrongPick(
    option: String,
    maxAttempts: Int,
    debtWord: Boolean = false,
): QuizUiState {
    val attempts = attempt + 1
    val revealed = attempts >= maxAttempts
    return copy(
        attempt = attempts,
        wrongSelection = option,
        eliminated = eliminated + option,
        revealed = revealed,
        mustPickAnswer = revealed && debtWord,
    )
}

/**
 * 按题量把题位排好。
 *
 * 原来只写死了 1 / 2 / 3 三种（2 题 = 热身 + 还账，3 题 = 热身 + 新词 + 还账），
 * 题量可以自定义到 10 之后，4 题以上会落到 `getOrElse { SMART }`，
 * 退化成「热身 + 新词 + 一堆随机」——新词只占五分之一，与家长「想多学新词」的预期相反。
 *
 * 现在的比例：新词约三分之一，其余给还账与复习；2 题不设新词位，
 * 因为只剩一个位置时让给还账更值。首位固定热身（建立信心），其余打乱。
 *
 * 抽成顶层函数是为了能被单测直接钉住槽位配比，不必构造整个 [QuizGate]。
 */
internal fun planSlots(
    total: Int,
    random: Random = Random.Default,
): List<QuestionSlot> {
    if (total <= 1) return listOf(QuestionSlot.SMART)
    // (total + 1) / 3 是「向下取整的三分之一」：3 → 1，6 → 2，10 → 3。
    // 封顶 total - 2：至少留一个位置给还账，否则一轮全是新词，欠账永远还不上。
    val newWordSlots = if (total < 3) 0 else ((total + 1) / 3).coerceAtMost(total - 2)
    val reviewSlots = total - 1 - newWordSlots
    val tail = buildList {
        repeat(newWordSlots) { add(QuestionSlot.NEW_WORD) }
        repeat(reviewSlots) { add(QuestionSlot.SMART) }
    }
    return listOf(QuestionSlot.WARM_UP) + tail.shuffled(random)
}
