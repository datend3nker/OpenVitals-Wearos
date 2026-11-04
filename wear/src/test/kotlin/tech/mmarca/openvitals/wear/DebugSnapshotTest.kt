package tech.mmarca.openvitals.wear

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.mmarca.openvitals.wearlink.WearLinkProtocol
import tech.mmarca.openvitals.wearlink.WearLinkWorkout
import tech.mmarca.openvitals.wearlink.WearLinkWorkoutLines

class DebugSnapshotTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val now = 16_666_667L * 60_000L
    private val hour = 3_600_000L

    private fun minute(at: Long, bpm: Int?) = WearLinkProtocol.SleepMinute(
        epochMillis = at,
        kind = WearLinkProtocol.MinuteKind.RAW,
        offsetSeconds = 3600,
        flags = 0,
        sampleCount = 300,
        movement10 = 25,
        bpm = bpm,
        heartRateSd10 = bpm?.let { 12 },
        heartRateSamples = if (bpm == null) 0 else 6,
        meanMilliG = intArrayOf(10, -20, 990),
        sdMilliG = intArrayOf(1, 2, 3),
        zAngleMin = 3,
        zAngleMax = 9,
        zAngleDelta10 = 60,
    )

    private val minutes = listOf(minute(now - 60 * hour, 60), minute(now - 2 * hour, 71), minute(now - hour, null))
    private val samples = listOf(WearLinkProtocol.HeartRateSample(now - 10_000, 66), WearLinkProtocol.HeartRateSample(now, 67))
    private val steps = listOf(WearLinkProtocol.StepMinute(now - 60_000, 12))
    private val ascent = listOf(WearLinkProtocol.AscentMinute(now - 60_000, 15))
    private val hrv = listOf(WearLinkProtocol.HrvWindow(now - hour, 421, 280))
    private val workout = WearLinkWorkout(now - 3 * hour, now - 2 * hour, 79, 1200, 900, null)
    private val days = listOf(DayValue(20_000L, 812))

    private val source = object : SnapshotSource {
        @Suppress("UNCHECKED_CAST")
        override fun <T> since(metric: WearMetric<T>, fromEpochMillis: Long, limit: Int): List<T> {
            val all: List<Any> = when (metric) {
                WearMetrics.SLEEP_MINUTES -> minutes
                WearMetrics.HEART_RATE -> samples
                WearMetrics.STEPS -> steps
                WearMetrics.ASCENT -> ascent
                WearMetrics.HRV -> hrv
                WearMetrics.WORKOUTS -> listOf(workout)
                WearMetrics.DAY_WEIGHT -> days
                else -> emptyList()
            }
            return all.filter { metric.timeOf(it as T) > fromEpochMillis }.take(limit) as List<T>
        }
    }

    private fun write(): File {
        val out = File(folder.root, "debug")
        DebugSnapshot(out, source) { now }.write()
        return out
    }

    @Test
    fun `the minutes are the protocol lines of the last 48 hours and parse again`() {
        val lines = File(write(), "minutes.csv").readLines()

        assertEquals(2, lines.size)
        assertEquals(WearLinkProtocol.formatSleepMinute(minutes[1]), lines[0])
        assertEquals(minutes.drop(1), lines.map { WearLinkProtocol.parseSleepMinute(it) })
    }

    @Test
    fun `every series is written in its wire format`() {
        val out = write()

        assertEquals(samples, File(out, "heartrate.csv").readLines().map { WearLinkProtocol.parseSample(it) })
        assertEquals(steps, File(out, "steps.csv").readLines().map { WearLinkProtocol.parseStepMinute(it) })
        assertEquals(ascent, File(out, "ascent.csv").readLines().map { WearLinkProtocol.parseAscentMinute(it) })
        assertEquals(hrv, File(out, "hrv.csv").readLines().map { WearLinkProtocol.parseHrvWindow(it) })
        assertEquals(listOf(workout), File(out, "workouts.csv").readLines().map { WearLinkWorkoutLines.parseHeader(it) })
    }

    @Test
    fun `day values carry their metric key and an empty series gives an empty file`() {
        val out = write()

        assertEquals(listOf("dw 20000 812"), File(out, "dayvalues.csv").readLines())
        val empty = DebugSnapshot(File(folder.root, "empty"), object : SnapshotSource {
            override fun <T> since(metric: WearMetric<T>, fromEpochMillis: Long, limit: Int): List<T> = emptyList()
        }) { now }
        empty.write()
        assertEquals("", File(folder.root, "empty/minutes.csv").readText())
    }

    @Test
    fun `a second write replaces the files and leaves no temporary file`() {
        val out = write()
        write()

        assertTrue(out.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        assertEquals(2, File(out, "minutes.csv").readLines().size)
    }
}
