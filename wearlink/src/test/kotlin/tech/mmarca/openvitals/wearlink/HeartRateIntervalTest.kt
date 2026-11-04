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
        assertEquals("600", HeartRateInterval.POWER_SAVE.wire)
        assertEquals(0, HeartRateInterval.OFF.seconds)
        assertEquals(10, HeartRateInterval.EVERY_10_SECONDS.seconds)
        assertEquals(600, HeartRateInterval.POWER_SAVE.seconds)
    }

    @Test
    fun `an unknown value is null, not a default`() {
        assertNull(HeartRateInterval.fromWire("5"))
        assertNull(HeartRateInterval.fromWire(""))
        assertNull(HeartRateInterval.fromWire("10 "))
    }

    @Test
    fun `a peer still sending the removed every-minute value is unknown, not a crash`() {
        // "60" was EVERY_MINUTE before it was removed. An old phone or an old
        // watch build may still send it; the other side must not recognise it
        // as something else.
        assertNull(HeartRateInterval.fromWire("60"))
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
                WearLinkProtocol.Request.SetPreference(key, HeartRateInterval.POWER_SAVE.wire),
            )
            assertEquals(WearLinkProtocol.Request.SetPreference(key, "600"), WearLinkProtocol.parseRequest(line))
        }
    }
}
