package tech.mmarca.openvitals.wear.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openvitals.wear.MetricTile

class CapabilityProbeTest {

    @Test
    fun `a full watch offers every metric a recorder feeds`() {
        val capabilities = capabilitiesOf(WatchSensors(heartRate = true, stepCounter = true, barometer = true))
        assertTrue(capabilities.probed)
        assertEquals(
            setOf(
                MetricTile.STEPS,
                MetricTile.DISTANCE,
                MetricTile.ACTIVE_CALORIES,
                MetricTile.HEART_RATE,
                MetricTile.FLOORS,
                MetricTile.ELEVATION,
            ),
            capabilities.sensorMetrics,
        )
    }

    @Test
    fun `a bare watch offers no sensor metric, but phone and logged ones`() {
        val capabilities = capabilitiesOf(WatchSensors(heartRate = false, stepCounter = false, barometer = false))
        assertTrue(capabilities.sensorMetrics.isEmpty())
        assertTrue(MetricTile.SLEEP in capabilities.availableMetrics)
        assertTrue(MetricTile.HYDRATION in capabilities.availableMetrics)
        assertTrue(MetricTile.STEPS !in capabilities.availableMetrics)
    }
}
