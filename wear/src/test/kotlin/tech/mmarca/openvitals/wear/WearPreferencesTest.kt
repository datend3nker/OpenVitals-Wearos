package tech.mmarca.openvitals.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openvitals.wearlink.HeartRateInterval
import tech.mmarca.openvitals.wearlink.WearLinkProtocol

class WearPreferencesTest {

    private val prefs = FakeSharedPreferences()
    private val state = WearLinkState
    private val preferences = WearPreferences(prefs, state)

    @Test
    fun `a watch that never heard from the phone records everything at the default rate`() {
        assertTrue(preferences.sleepRecording)
        assertTrue(preferences.heartRate)
        assertTrue(preferences.steps)
        assertTrue(preferences.ascent)
        assertEquals(HeartRateInterval.EVERY_10_SECONDS, preferences.heartRateDay)
        assertEquals(HeartRateInterval.EVERY_10_SECONDS, preferences.heartRateNight)
    }

    @Test
    fun `each switch takes on and off and keeps the value across a restart`() {
        val keys = listOf(
            WearLinkProtocol.Preferences.SLEEP_RECORDING,
            WearLinkProtocol.Preferences.HEART_RATE,
            WearLinkProtocol.Preferences.STEPS,
            WearLinkProtocol.Preferences.ASCENT,
        )
        for (key in keys) {
            assertTrue(preferences.applyPreference(key, WearLinkProtocol.Preferences.OFF))
        }
        assertFalse(preferences.sleepRecording)
        assertFalse(preferences.heartRate)
        assertFalse(preferences.steps)
        assertFalse(preferences.ascent)

        val afterRestart = WearPreferences(prefs, WearLinkState)
        assertFalse(afterRestart.heartRate)
        assertFalse(afterRestart.steps)
        assertFalse(afterRestart.ascent)
        assertFalse(afterRestart.sleepRecording)
    }

    @Test
    fun `the sleep switch keeps the file key an earlier build wrote`() {
        prefs.edit().putBoolean("sleep_recording", false).apply()

        assertFalse(preferences.sleepRecording)
        assertTrue(preferences.applyPreference(WearLinkProtocol.Preferences.SLEEP_RECORDING, WearLinkProtocol.Preferences.ON))
        assertTrue(preferences.sleepRecording)
    }

    @Test
    fun `an interval is stored as its wire value and read back`() {
        assertTrue(preferences.applyPreference(WearLinkProtocol.Preferences.HEART_RATE_DAY, "60"))
        assertTrue(preferences.applyPreference(WearLinkProtocol.Preferences.HEART_RATE_NIGHT, "0"))

        assertEquals(HeartRateInterval.EVERY_MINUTE, preferences.heartRateDay)
        assertEquals(HeartRateInterval.OFF, preferences.heartRateNight)
    }

    @Test
    fun `an unknown key or value is refused and changes nothing`() {
        assertFalse(preferences.applyPreference("unknown_key", "1"))
        assertFalse(preferences.applyPreference(WearLinkProtocol.Preferences.HEART_RATE, "maybe"))
        assertFalse(preferences.applyPreference(WearLinkProtocol.Preferences.HEART_RATE_DAY, "5"))
        assertFalse(preferences.applyPreference(WearLinkProtocol.Preferences.HEART_RATE_NIGHT, "10 "))

        assertTrue(preferences.heartRate)
        assertEquals(HeartRateInterval.EVERY_10_SECONDS, preferences.heartRateDay)
        assertNull(prefs.getString(WearLinkProtocol.Preferences.HEART_RATE_DAY, null))
    }

    @Test
    fun `onChange fires only when a stored value really changes`() {
        var calls = 0
        preferences.onChange = { calls++ }

        preferences.applyPreference(WearLinkProtocol.Preferences.STEPS, WearLinkProtocol.Preferences.ON)
        assertEquals(0, calls)

        preferences.applyPreference(WearLinkProtocol.Preferences.STEPS, WearLinkProtocol.Preferences.OFF)
        preferences.applyPreference(WearLinkProtocol.Preferences.STEPS, WearLinkProtocol.Preferences.OFF)
        assertEquals(1, calls)

        preferences.applyPreference(WearLinkProtocol.Preferences.HEART_RATE_DAY, "60")
        assertEquals(2, calls)
    }

    @Test
    fun `the sleep switch is published to the status screen`() {
        preferences.applyPreference(WearLinkProtocol.Preferences.SLEEP_RECORDING, WearLinkProtocol.Preferences.OFF)
        assertFalse(state.state.value.sleepRecording)
        preferences.applyPreference(WearLinkProtocol.Preferences.SLEEP_RECORDING, WearLinkProtocol.Preferences.ON)
        assertTrue(state.state.value.sleepRecording)
    }
}
