package dev.anilbeesetti.nextplayer.feature.player.study

import dev.anilbeesetti.nextplayer.core.data.repository.FakeLicenseRepository
import dev.anilbeesetti.nextplayer.core.data.repository.FakeRestRepository
import dev.anilbeesetti.nextplayer.core.data.repository.FakeStudyRepository
import dev.anilbeesetti.nextplayer.core.data.repository.QuizBankRepository
import dev.anilbeesetti.nextplayer.core.model.RestPreferences
import dev.anilbeesetti.nextplayer.core.model.RestProgress
import dev.anilbeesetti.nextplayer.core.model.SlidingWindow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 防沉迷状态机的回归测试。
 *
 * 直接构造 [RestGuard]（配真实 [QuizGate] + 内存版 [FakeRestRepository]），
 * 不经由 Koin：两套拦截共用答题锁，但这里只关心 RestGuard 的阶段推算与「切疯狂节奏」的动作是否正确。
 *
 * 钉住的几条真实规则：
 * - 关闭或授权失效时不拦，且清空窗口；
 * - 窗口内看视频超过阈值（默认 40 分钟内看满 30 分钟）立即进疯狂、答题锁切 1 分钟 1 词；
 * - 阈值前 30 秒进预警，浮层可见；点「知道了」收起后留在预警区间不再硬弹；
 * - 离开预警区间再回来，浮层重新弹起（避免「知道了一次就永久静音」）；
 * - 杀进程重进后窗口历史保留，直接恢复拦截（防绕过）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RestGuardTest {

    private val app get() = RuntimeEnvironment.getApplication()

    /** 构造一套自包含的防沉迷环境。 */
    private fun build(enabled: Boolean = true): Triple<RestGuard, QuizGate, FakeRestRepository> {
        val repo = FakeRestRepository()
        repo.setProgress(RestProgress(preferences = RestPreferences(enabled = enabled)))
        val license = FakeLicenseRepository(pro = true)
        val quizGate = QuizGate(
            FakeStudyRepository(),
            QuizBankRepository(app),
            license,
            CoroutineScope(Dispatchers.Unconfined),
        )
        val guard = RestGuard(repo, quizGate, license, CoroutineScope(Dispatchers.Unconfined))
        return Triple(guard, quizGate, repo)
    }

    @Test
    fun `未开启防沉迷时 onTick 不进入任何拦截且清空窗口`() {
        val (guard, quizGate) = build(enabled = false)
        repeat(2000) { guard.onTick(true) }
        assertEquals(RestPhase.IDLE, guard.state.value.phase)
        assertFalse(quizGate.crazyMode)
    }

    @Test
    fun `窗口内看满阈值进入疯狂并切答题锁到疯狂节奏`() {
        val (guard, quizGate) = build()
        // 默认 40 分钟窗口、要求休息 10 分钟 → 阈值 = 30 分钟 = 1800 秒
        repeat(1800) { guard.onTick(true) }
        assertEquals(RestPhase.CRAZY, guard.state.value.phase)
        assertTrue("进入疯狂必须把答题锁切到 1 分钟 1 词", quizGate.crazyMode)
    }

    @Test
    fun `差一点到阈值处于预警阶段且浮层可见`() {
        val (guard) = build()
        // 29.5 分钟 = 1770 秒，落在预警带 [1770, 1800)
        repeat(1770) { guard.onTick(true) }
        assertEquals(RestPhase.PRE_WARN, guard.state.value.phase)
        assertTrue(guard.state.value.preWarnVisible)
        // 剩余秒数 = (1800 − 1770) = 30
        assertEquals(30, guard.state.value.preWarnSecondsLeft)
    }

    @Test
    fun `点知道了收起预警且留在预警区间不再硬弹`() {
        val (guard) = build()
        repeat(1770) { guard.onTick(true) }
        assertTrue(guard.state.value.preWarnVisible)

        guard.acknowledgePreWarn()
        assertFalse("点知道了后浮层应立即收起", guard.state.value.preWarnVisible)

        // 再推进几秒仍停留在预警区间，但不应重新弹起
        repeat(5) { guard.onTick(true) }
        assertEquals(RestPhase.PRE_WARN, guard.state.value.phase)
        assertFalse(guard.state.value.preWarnVisible)
    }

    @Test
    fun `离开预警区间后再次进入会重新弹起`() {
        val (guard) = build()
        // 进预警
        repeat(1770) { guard.onTick(true) }
        assertTrue(guard.state.value.preWarnVisible)
        guard.acknowledgePreWarn()
        assertFalse(guard.state.value.preWarnVisible)

        // 离开预警：连续不看。窗口尾部有 630 秒的「没看」占位，
        // 要先把这些 0 滚过去才能开始挤掉真正看过的 1，所以推够 650 次才压回预警带以下。
        repeat(650) { guard.onTick(false) }
        assertEquals(RestPhase.IDLE, guard.state.value.phase)

        // 重新看回预警带，浮层应重新弹起（之前「知道了」已被复位）
        repeat(1770) { guard.onTick(true) }
        assertEquals(RestPhase.PRE_WARN, guard.state.value.phase)
        assertTrue("离开预警再回来，预警浮层应重新弹起", guard.state.value.preWarnVisible)
    }

    @Test
    fun `杀进程重启后窗口历史保留直接进入疯狂`() {
        val repo = FakeRestRepository()
        repo.setProgress(RestProgress(preferences = RestPreferences(enabled = true)))
        // 模拟「杀进程前」已经落盘的窗口：40 分钟窗口里看了满 30 分钟
        val w = SlidingWindow.create(40 * 60)
        repeat(1800) { w.push(true) }
        repo.setProgress(
            RestProgress(
                preferences = RestPreferences(enabled = true),
                windowBytes = w.toBytes(),
                head = w.headValue(),
                watchMsInWindow = w.watchMsInWindow,
                crazyActive = false,
            ),
        )

        // 重启：同一份仓库，新构造的 guard
        val license = FakeLicenseRepository(pro = true)
        val guard = RestGuard(
            repo,
            QuizGate(FakeStudyRepository(), QuizBankRepository(app), license, CoroutineScope(Dispatchers.Unconfined)),
            license,
            CoroutineScope(Dispatchers.Unconfined),
        )
        guard.restore()
        guard.onTick(true) // 再推进一秒，确保越过阈值
        assertEquals(RestPhase.CRAZY, guard.state.value.phase)
    }
}
