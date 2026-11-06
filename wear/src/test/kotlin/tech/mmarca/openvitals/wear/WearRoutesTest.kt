package tech.mmarca.openvitals.wear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openvitals.wear.features.measure.Measurement
import tech.mmarca.openvitals.wear.health.WearCapabilities

class WearRoutesTest {

    @Test
    fun tilesMayOpenLoggingAndMeasuring_butNothingElse() {
        assertTrue(WearRoutes.isDeepLinkable(WearRoutes.QUICK_LOG))
        assertTrue(WearRoutes.isDeepLinkable(WearRoutes.measurement(Measurement.HEART_RATE)))
        assertFalse(WearRoutes.isDeepLinkable(WearRoutes.RECORDING))
        assertFalse(WearRoutes.isDeepLinkable("measure/ECG"))
    }

    @Test
    fun availableMetrics_offerPhoneAndLoggedMetrics_andOnlyMeasuredSensorOnes() {
        val available = WearCapabilities(probed = true, sensorMetrics = setOf(DashboardMetric.STEPS)).availableMetrics
        assertTrue(DashboardMetric.STEPS in available)
        assertTrue(DashboardMetric.SLEEP in available)
        assertTrue(DashboardMetric.HYDRATION in available)
        assertFalse(DashboardMetric.HEART_RATE in available)
        assertFalse(DashboardMetric.BLOOD_OXYGEN in available)
    }
}
