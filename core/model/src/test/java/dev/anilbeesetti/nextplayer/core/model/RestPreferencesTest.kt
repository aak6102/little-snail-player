package dev.anilbeesetti.nextplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 防沉迷偏好的边界与触发阈值。
 *
 * 核心是把「窗口 − 要求休息」换算成触发疯狂答题的看视频毫秒数（[RestPreferences.thresholdWatchMs]），
 * 以及任何自定义值都不能越过越界保护，导致阈值算成 ≤ 0（看一眼就触发）或休息压过窗口。
 */
class RestPreferencesTest {

    @Test
    fun `新装默认值符合方案`() {
        val p = RestPreferences()
        assertEquals(false, p.enabled)
        assertEquals(40, p.windowMinutes)
        assertEquals(10, p.requiredRestMinutes)
        assertEquals(1, p.crazyIntervalMinutes)
        assertEquals(30, p.preWarnSeconds)
    }

    @Test
    fun `触发阈值等于窗口减去要求休息`() {
        val p = RestPreferences(windowMinutes = 40, requiredRestMinutes = 10)
        // (40 − 10) 分钟 = 30 分钟 = 1_800_000 毫秒
        assertEquals(1_800_000L, p.thresholdWatchMs)
    }

    @Test
    fun `窗口做越界保护`() {
        assertEquals(2, RestPreferences(windowMinutes = 0).windowMinutesClamped)
        assertEquals(5, RestPreferences(windowMinutes = 5).windowMinutesClamped)
        assertEquals(120, RestPreferences(windowMinutes = 9_999).windowMinutesClamped)
        assertEquals(37, RestPreferences(windowMinutes = 37).windowMinutesClamped)
    }

    @Test
    fun `要求休息严格小于窗口且做越界保护`() {
        // 窗口 40 → 上限 39；窗口 20 → 上限 19（不能 ≥ 窗口，否则阈值 ≤ 0）
        assertEquals(39, RestPreferences(windowMinutes = 40, requiredRestMinutes = 99).requiredRestMinutesClamped)
        assertEquals(19, RestPreferences(windowMinutes = 20, requiredRestMinutes = 99).requiredRestMinutesClamped)
        assertEquals(1, RestPreferences(windowMinutes = 40, requiredRestMinutes = 0).requiredRestMinutesClamped)
        assertEquals(10, RestPreferences(windowMinutes = 40, requiredRestMinutes = 10).requiredRestMinutesClamped)
    }

    @Test
    fun `疯狂节奏做越界保护`() {
        assertEquals(1, RestPreferences(crazyIntervalMinutes = 0).crazyIntervalMinutesClamped)
        assertEquals(5, RestPreferences(crazyIntervalMinutes = 99).crazyIntervalMinutesClamped)
        assertEquals(1, RestPreferences(crazyIntervalMinutes = 1).crazyIntervalMinutesClamped)
        assertEquals(60_000L, RestPreferences(crazyIntervalMinutes = 1).crazyIntervalMillis)
    }

    @Test
    fun `预警提前量做越界保护`() {
        assertEquals(15, RestPreferences(preWarnSeconds = 0).preWarnSecondsClamped)
        assertEquals(15, RestPreferences(preWarnSeconds = 1).preWarnSecondsClamped)
        assertEquals(60, RestPreferences(preWarnSeconds = 999).preWarnSecondsClamped)
        assertEquals(30_000L, RestPreferences(preWarnSeconds = 30).preWarnMs)
    }

    /**
     * 阈值绝不能算成 ≤ 0：要求休息被夹到「窗口 − 1」之后，
     * 最坏情况还剩 1 分钟的余量，否则「看一眼就进疯狂」会让防沉迷失去意义。
     */
    @Test
    fun `所有合法窗口与休息组合阈值都为正`() {
        for (window in RestPreferences.ALLOWED_WINDOWS) {
            for (rest in RestPreferences.ALLOWED_RESTS) {
                val prefs = RestPreferences(windowMinutes = window, requiredRestMinutes = rest)
                assertTrue(
                    "窗口 $window / 休息 $rest 的阈值应 ≥ 1 分钟",
                    prefs.thresholdWatchMs >= 60_000L,
                )
            }
        }
    }
}
