package tech.mmarca.openvitals.domain.insights

import org.junit.Assert.assertEquals
import org.junit.Test

class StepEstimatesTest {

    @Test
    fun `distance is steps times the step length, kept inside the bounds`() {
        assertEquals(7000.0, StepEstimates.distanceMetres(10_000.0, 0.7), 1e-9)
        // A length no one has is clamped like the phone's setting.
        assertEquals(15_000.0, StepEstimates.distanceMetres(10_000.0, 9.0), 1e-9)
        assertEquals(7000.0, StepEstimates.distanceMetres(10_000.0, Double.NaN), 1e-9)
    }

    @Test
    fun `energy adds the minutes above rest and is zero without a weight`() {
        val minutes = listOf(100, 100, 0, 20)
        val perMinute = WalkingEnergy.activeKcalPerMinute(100, 70.0)

        assertEquals(2 * perMinute, StepEstimates.activeKcal(minutes, 70.0), 1e-9)
        assertEquals(0.0, StepEstimates.activeKcal(minutes, null), 0.0)
        assertEquals(0.0, StepEstimates.activeKcal(minutes, 0.0), 0.0)
    }
}
