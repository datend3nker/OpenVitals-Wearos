package tech.mmarca.openvitals.wear.features.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeasureMathTest {

    @Test
    fun settledHeartRate_needsEnoughValidReadings() {
        assertNull(settledHeartRate(List(HeartRateReadingsNeeded - 1) { 70.0 }))
        // Zero means "no signal" and does not count.
        assertNull(settledHeartRate(List(HeartRateReadingsNeeded - 1) { 70.0 } + 0.0))
    }

    @Test
    fun settledHeartRate_isMedianOfLastFive_soOneStrayReadingDoesNotMoveIt() {
        val readings = listOf(90.0, 88.0, 85.0, 72.0, 71.0, 140.0, 70.0, 72.0)
        assertEquals(72.0, settledHeartRate(readings)!!, 0.0)
    }
}
