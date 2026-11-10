package dev.anilbeesetti.nextplayer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeekThrottlePolicyTest {

    @Test
    fun `pro with throttle enabled uses custom interval`() {
        val preferences = StudyPreferences(seekThrottleEnabled = true, seekIntervalMinutes = 5)
        assertEquals(5 * 60_000L, effectiveSeekIntervalMillis(pro = true, preferences = preferences))
    }

    @Test
    fun `pro with throttle disabled returns null`() {
        val preferences = StudyPreferences(seekThrottleEnabled = false, seekIntervalMinutes = 10)
        assertNull(effectiveSeekIntervalMillis(pro = true, preferences = preferences))
    }

    @Test
    fun `free user forced to default interval even when stored disabled`() {
        val preferences = StudyPreferences(seekThrottleEnabled = false, seekIntervalMinutes = 10)
        assertEquals(
            StudyPreferences.DEFAULT_SEEK_INTERVAL_MINUTES * 60_000L,
            effectiveSeekIntervalMillis(pro = false, preferences = preferences),
        )
    }

    @Test
    fun `free user forced to default interval even when stored custom`() {
        val preferences = StudyPreferences(seekThrottleEnabled = true, seekIntervalMinutes = 30)
        assertEquals(
            StudyPreferences.DEFAULT_SEEK_INTERVAL_MINUTES * 60_000L,
            effectiveSeekIntervalMillis(pro = false, preferences = preferences),
        )
    }
}
