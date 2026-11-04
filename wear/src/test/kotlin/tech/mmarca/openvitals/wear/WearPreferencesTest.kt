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
    fun `the weight and step length from the phone are kept, and a value out of range is refused`() {
        assertNull(preferences.weightKg)
        assertEquals(0.7, preferences.strideMeters, 0.0)

        assertTrue(preferences.applyPreference(WearLinkProtocol.Preferences.BODY_WEIGHT, "705"))
        assertTrue(preferences.applyPreference(WearLinkProtocol.Preferences.STRIDE_CM, "82"))
        assertEquals(70.5, preferences.weightKg!!, 0.0)
        assertEquals(0.82, preferences.strideMeters, 0.0)
        assertEquals(70.5, WearPreferences(prefs, WearLinkState).weightKg!!, 0.0)

        assertFalse(preferences.applyPreference(WearLinkProtocol.Preferences.BODY_WEIGHT, "10"))
        assertFalse(preferences.applyPreference(WearLinkProtocol.Preferences.BODY_WEIGHT, "heavy"))
        assertFalse(preferences.applyPreference(WearLinkProtocol.Preferences.STRIDE_CM, "5"))
        assertFalse(preferences.applyPreference(WearLinkProtocol.Preferences.STRIDE_CM, "400"))
        assertEquals(70.5, preferences.weightKg!!, 0.0)
        assertEquals(0.82, preferences.strideMeters, 0.0)
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
        assertTrue(preferences.applyPreference(WearLinkProtocol.Preferences.HEART_RATE_DAY, "600"))
        assertTrue(preferences.applyPreference(WearLinkProtocol.Preferences.HEART_RATE_NIGHT, "0"))

        assertEquals(HeartRateInterval.POWER_SAVE, preferences.heartRateDay)
        assertEquals(HeartRateInterval.OFF, preferences.heartRateNight)
    }

    @Test
    fun `a removed wire value is refused like any other unknown value`() {
        // "60" was EVERY_MINUTE before it was removed; an old peer may still send it.
        assertFalse(preferences.applyPreference(WearLinkProtocol.Preferences.HEART_RATE_DAY, "60"))
        assertEquals(HeartRateInterval.EVERY_10_SECONDS, preferences.heartRateDay)
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

        preferences.applyPreference(WearLinkProtocol.Preferences.HEART_RATE_DAY, "600")
        assertEquals(2, calls)
    }

    @Test
    fun `the sleep switch is published to the status screen`() {
        preferences.applyPreference(WearLinkProtocol.Preferences.SLEEP_RECORDING, WearLinkProtocol.Preferences.OFF)
        assertFalse(state.state.value.sleepRecording)
        preferences.applyPreference(WearLinkProtocol.Preferences.SLEEP_RECORDING, WearLinkProtocol.Preferences.ON)
        assertTrue(state.state.value.sleepRecording)
    }

    @Test
    fun `local setters update flags intervals and unit system on watch`() {
        preferences.setFlag(WearLinkProtocol.Preferences.STEPS, false)
        assertFalse(preferences.steps)

        preferences.setInterval(WearLinkProtocol.Preferences.HEART_RATE_DAY, HeartRateInterval.POWER_SAVE)
        assertEquals(HeartRateInterval.POWER_SAVE, preferences.heartRateDay)

        assertEquals(UnitSystem.METRIC, preferences.unitSystem)
        preferences.unitSystem = UnitSystem.IMPERIAL
        assertEquals(UnitSystem.IMPERIAL, preferences.unitSystem)
    }
}
