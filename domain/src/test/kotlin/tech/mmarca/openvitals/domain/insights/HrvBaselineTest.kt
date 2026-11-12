package tech.mmarca.openvitals.domain.insights

import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HrvBaselineTest {

    @Test
    fun `a week of nights gives the mean and spread of the log values`() {
        val nights = listOf(40.0, 45.0, 50.0, 55.0, 60.0, 50.0, 50.0)
        val baseline = requireNotNull(HrvBaseline.of(nights))

        val logs = nights.map { ln(it) }
        val mean = logs.average()
        assertEquals(mean, baseline.lnRmssdMean, 1e-12)
        val sd = kotlin.math.sqrt(logs.sumOf { (it - mean) * (it - mean) } / (logs.size - 1))
        assertEquals(sd, baseline.lnRmssdSd, 1e-12)
    }

    @Test
    fun `fewer than seven usable nights give no baseline`() {
        assertNull(HrvBaseline.of(listOf(40.0, 45.0, 50.0, 55.0, 60.0, 50.0, 0.0)))
    }

    @Test
    fun `nights without spread give no baseline`() {
        assertNull(HrvBaseline.of(List(10) { 50.0 }))
    }

    @Test
    fun `a value one smallest worthwhile change below the mean reads minus one`() {
        val baseline = HrvBaseline(lnRmssdMean = ln(50.0), lnRmssdSd = 0.2)
        // The smallest worthwhile change is half the spread, 0.1 on the log scale.
        val oneChangeBelow = 50.0 * kotlin.math.exp(-0.1)

        assertEquals(-1.0, baseline.worthwhileChangesFromMean(oneChangeBelow)!!, 1e-12)
        assertNull(baseline.worthwhileChangesFromMean(0.0))
    }
}
