package tech.mmarca.openvitals.wear

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val hour = 3_600_000L

    private fun sample(
        hours: Double,
        percent: Int,
        charging: Boolean = false,
        heartRate: Boolean = true,
    ) = DiagnosticSample(
        epochMillis = 1_000_000L + (hours * hour).toLong(),
        batteryPercent = percent,
        charging = charging,
        heartRateOn = heartRate,
        stepsOn = true,
        bedtime = BedtimeHold.OFF,
    )

    @Test
    fun aSampleRoundTripsThroughItsLine() {
        val original = sample(0.0, 80, charging = true, heartRate = false)

        assertEquals(original, diagnosticSampleOf(original.toLine()))
    }

    @Test
    fun aLineThatDoesNotParseIsLeftOut() {
        assertNull(diagnosticSampleOf("1;50;0"))
        assertNull(diagnosticSampleOf("x;50;0;1;1;OFF"))
        assertNull(diagnosticSampleOf("1;150;0;1;1;OFF"))
    }

    @Test
    fun drainIsTheLossPerHourOverTheNewestUnpluggedRun() {
        val samples = listOf(
            sample(0.0, 40, charging = true),
            sample(1.0, 90),
            sample(3.0, 80),
            sample(4.0, 75),
        )

        val summary = summarizeDiagnostics(samples)

        assertEquals(5.0, summary.drainPercentPerHour!!, 1e-9) // 90 -> 75 over 3 h
    }

    @Test
    fun aRunShorterThanHalfAnHourGivesNoDrain() {
        val summary = summarizeDiagnostics(listOf(sample(0.0, 80), sample(0.2, 79)))

        assertNull(summary.drainPercentPerHour)
    }

    @Test
    fun aLastReadingOnTheChargerGivesNoDrainAndNoCrash() {
        val summary = summarizeDiagnostics(listOf(sample(0.0, 60), sample(1.0, 80, charging = true)))

        assertNull(summary.drainPercentPerHour)
        assertEquals(2, summary.samples)
    }

    @Test
    fun heartRateShareIsTheFractionOfReadingsWithItOn() {
        val samples = listOf(sample(0.0, 80, heartRate = true), sample(1.0, 79, heartRate = false))

        assertEquals(0.5, summarizeDiagnostics(samples).heartRateShare!!, 1e-9)
    }

    @Test
    fun theLogKeepsOnlyTheNewestSamples() {
        val log = DiagnosticLog(File(folder.root, "log.csv"), maxSamples = 3)

        for (i in 0 until 5) log.append(sample(i.toDouble(), 90 - i))

        assertEquals(listOf(2.0, 3.0, 4.0), log.samples().map { (it.epochMillis - 1_000_000L).toDouble() / hour })
        assertTrue(File(folder.root, "log.csv").readText().lines().count { it.isNotBlank() } == 3)
    }
}
