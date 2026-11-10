package dev.anilbeesetti.nextplayer.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学习模式开关的判定。
 *
 * 关闭现在立即生效（门禁换成 4 位 PIN 之后，不再需要 24 小时缓冲），
 * 所以用例只剩「开就是开、关就是关」；另外钉住旧存档的兼容性。
 */
class StudyPreferencesTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `新装默认值保持原版手感`() {
        val preferences = StudyPreferences()
        assertFalse(preferences.enabled)
        // 快进节流默认开启且仍是 2 分钟，从 v1.3.0 升级上来不应有感知差异
        assertTrue(preferences.seekThrottleEnabled)
        assertEquals(2, preferences.seekIntervalMinutes)
        assertEquals(120_000L, preferences.seekIntervalMillis)
    }

    @Test
    fun `未开启学习模式时任何时刻都不生效`() {
        val preferences = StudyPreferences(enabled = false)
        assertFalse(preferences.isActiveAt(now))
    }

    @Test
    fun `开启后任何时刻都生效`() {
        val preferences = StudyPreferences(enabled = true)
        assertTrue(preferences.isActiveAt(now))
        // 关闭是立即生效的，不再有「排定时刻」这一层
        assertTrue(preferences.isActiveAt(now + 7 * 24 * 60 * 60 * 1000L))
    }

    @Test
    fun `快进间隔做越界保护`() {
        // 0 表示不限制，与关掉节流开关同义；负值一起兜到 0
        assertEquals(0L, StudyPreferences(seekIntervalMinutes = 0).seekIntervalMillis)
        assertEquals(0L, StudyPreferences(seekIntervalMinutes = -5).seekIntervalMillis)
        assertEquals(60 * 60_000L, StudyPreferences(seekIntervalMinutes = 99).seekIntervalMillis)
    }

    /**
     * 自定义值的边界。
     *
     * 间隔下限从 5 分钟放宽到 1 分钟之后，折扣不再靠「间隔不许设太短」来保，
     * 而是按比例算、下限跟着原时长走（见 unlockMinutesFor 的用例）。
     */
    @Test
    fun `自定义间隔与题量都做越界保护`() {
        assertEquals(1, StudyPreferences(intervalMinutes = 0).intervalMinutesClamped)
        assertEquals(1 * 60_000L, StudyPreferences(intervalMinutes = 0).intervalMillis)
        assertEquals(1, StudyPreferences(intervalMinutes = 1).intervalMinutesClamped)
        assertEquals(120, StudyPreferences(intervalMinutes = 9_999).intervalMinutesClamped)

        assertEquals(1, StudyPreferences(questionsPerLock = 0).questionsPerLockClamped)
        assertEquals(10, StudyPreferences(questionsPerLock = 50).questionsPerLockClamped)

        // 预设值与自定义值共存：区间内原样保留
        assertEquals(37, StudyPreferences(intervalMinutes = 37).intervalMinutesClamped)
    }

    @Test
    fun `旧版配置文件缺少新字段时回落到默认值`() {
        // autoDisableAtMillis 已经从模型里删掉，旧存档里多出来的这个字段
        // 必须被 ignoreUnknownKeys 吃掉，否则升级后连偏好都读不出来。
        val lenient = Json { ignoreUnknownKeys = true }
        val decoded = lenient.decodeFromString<StudyPreferences>(
            """{"enabled":true,"intervalMinutes":10,"questionsPerLock":2,"autoDisableAtMillis":1700000000000}""",
        )
        assertTrue(decoded.enabled)
        assertTrue(decoded.seekThrottleEnabled)
        assertEquals(2, decoded.seekIntervalMinutes)
        assertTrue(decoded.isActiveAt(now))
    }
}
