package tech.mmarca.openvitals.wearlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeartRateIntervalTest {

    @Test
    fun `every interval round-trips through its wire value`() {
        for (interval in HeartRateInterval.entries) {
            assertEquals(interval, HeartRateInterval.fromWire(interval.wire))
        }
    }

    @Test
    fun `the wire values and spacings are the ones the protocol documents`() {
        assertEquals("0", HeartRateInterval.OFF.wire)
        assertEquals("10", HeartRateInterval.EVERY_10_SECONDS.wire)
        assertEquals("60", HeartRateInterval.EVERY_MINUTE.wire)
        assertEquals(0, HeartRateInterval.OFF.seconds)
        assertEquals(10, HeartRateInterval.EVERY_10_SECONDS.seconds)
        assertEquals(60, HeartRateInterval.EVERY_MINUTE.seconds)
    }

    @Test
    fun `an unknown value is null, not a default`() {
        assertNull(HeartRateInterval.fromWire("5"))
        assertNull(HeartRateInterval.fromWire(""))
        assertNull(HeartRateInterval.fromWire("10 "))
    }

    @Test
    fun `the new preference keys are plain tokens the protocol accepts`() {
        val keys = listOf(
            WearLinkProtocol.Preferences.HEART_RATE,
            WearLinkProtocol.Preferences.STEPS,
            WearLinkProtocol.Preferences.ASCENT,
            WearLinkProtocol.Preferences.HEART_RATE_DAY,
            WearLinkProtocol.Preferences.HEART_RATE_NIGHT,
        )
        for (key in keys) {
            val line = WearLinkProtocol.formatRequest(
                WearLinkProtocol.Request.SetPreference(key, HeartRateInterval.EVERY_MINUTE.wire),
            )
            assertEquals(WearLinkProtocol.Request.SetPreference(key, "60"), WearLinkProtocol.parseRequest(line))
        }
    }
}
