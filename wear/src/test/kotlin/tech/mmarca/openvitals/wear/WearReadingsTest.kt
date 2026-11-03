package tech.mmarca.openvitals.wear

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import tech.mmarca.openvitals.wearlink.WearLinkProtocol
import tech.mmarca.openvitals.wearlink.WearLinkProtocol.AscentMinute
import tech.mmarca.openvitals.wearlink.WearLinkProtocol.HeartRateSample
import tech.mmarca.openvitals.wearlink.WearLinkProtocol.StepMinute

class WearReadingsTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 2)
    private val now = Instant.parse("2026-11-02T10:30:00Z")

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        date.atTime(hour, minute).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun day(
        date: LocalDate,
        heartRate: List<HeartRateSample> = emptyList(),
        steps: List<StepMinute> = emptyList(),
        ascent: List<AscentMinute> = emptyList(),
    ) = WearReadings.day(date, zone, heartRate, steps, ascent)

    /** Seven days, today last; [today] carries the rows, the earlier ones are empty unless given. */
    private fun week(todayReadings: DayReadings, earlier: Map<Int, DayReadings> = emptyMap()): List<DayReadings> =
        (6 downTo 1).map { back -> earlier[back] ?: day(today.minusDays(back.toLong())) } + todayReadings

    @Test
    fun stepsAddUpPerHourAndPerDay() {
        val readings = week(
            day(today, steps = listOf(StepMinute(at(today, 8, 0), 40), StepMinute(at(today, 8, 1), 60), StepMinute(at(today, 10, 5), 25))),
            earlier = mapOf(1 to day(today.minusDays(1), steps = listOf(StepMinute(at(today.minusDays(1), 12), 500)))),
        )

        val steps = WearReadings.metrics(readings, now, zone).getValue(MetricTile.STEPS)

        assertEquals(125.0, steps.current!!, 0.0)
        assertEquals(40.0 + 60.0, steps.today[8], 0.0)
        assertEquals(25.0, steps.today[10], 0.0)
        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 0.0, 500.0, 125.0), steps.week)
    }

    @Test
    fun todayStopsAtTheCurrentHour() {
        val readings = week(day(today, steps = listOf(StepMinute(at(today, 3), 10))))

        val steps = WearReadings.metrics(readings, now, zone).getValue(MetricTile.STEPS)

        assertEquals(11, steps.today.size)
    }

    @Test
    fun aMetricWithNoRowInTheWeekIsLeftOut() {
        val metrics = WearReadings.metrics(week(day(today)), now, zone)

        assertTrue(metrics.isEmpty())
    }

    @Test
    fun aQuietTodayStillShowsZeroWhenAnEarlierDayHadSteps() {
        val readings = week(
            day(today),
            earlier = mapOf(2 to day(today.minusDays(2), steps = listOf(StepMinute(at(today.minusDays(2), 9), 300)))),
        )

        assertEquals(0.0, WearReadings.metrics(readings, now, zone).getValue(MetricTile.STEPS).current!!, 0.0)
    }

    @Test
    fun heightCountsOnlyWithStepsAroundIt() {
        val climbed = AscentMinute(at(today, 9, 10), 60) // 6 m
        val walkedNothing = week(day(today, ascent = listOf(climbed)))
        val walked = week(
            day(today, ascent = listOf(climbed), steps = listOf(StepMinute(at(today, 9, 10), 20), StepMinute(at(today, 9, 9), 20))),
        )

        val without = WearReadings.metrics(walkedNothing, now, zone).getValue(MetricTile.ELEVATION)
        val with = WearReadings.metrics(walked, now, zone).getValue(MetricTile.ELEVATION)

        assertEquals(0.0, without.current!!, 0.0)
        assertEquals(6.0, with.current!!, 1e-9) // 40 steps carry 12 m, the 6 m climbed all count
    }

    @Test
    fun floorsAreCreditedMetresOverThreeMetresPerFloor() {
        val readings = week(
            day(today, ascent = listOf(AscentMinute(at(today, 9, 10), 90)), steps = listOf(StepMinute(at(today, 9, 10), 60))),
        )

        val floors = WearReadings.metrics(readings, now, zone).getValue(MetricTile.FLOORS)

        assertEquals(3.0, floors.current!!, 1e-9) // 9 m climbed, 60 steps carry 18 m
    }

    @Test
    fun heartRateShowsTheLatestSampleAndAFiveMinuteLine() {
        val samples = listOf(
            HeartRateSample(at(today, 9, 0), 60),
            HeartRateSample(at(today, 9, 2), 70),
            HeartRateSample(at(today, 9, 7), 90),
        )

        val heartRate = WearReadings.metrics(week(day(today, heartRate = samples)), now, zone).getValue(MetricTile.HEART_RATE)

        assertEquals(90.0, heartRate.current!!, 0.0)
        assertEquals(listOf(65.0, 90.0), heartRate.today)
    }

    @Test
    fun heartRateWeekRunsFromTheFirstDayWithSamplesAndKeepsGapsAsZero() {
        val readings = week(
            day(today, heartRate = listOf(HeartRateSample(at(today, 8), 80))),
            earlier = mapOf(3 to day(today.minusDays(3), heartRate = listOf(HeartRateSample(at(today.minusDays(3), 8), 60), HeartRateSample(at(today.minusDays(3), 9), 70)))),
        )

        val heartRate = WearReadings.metrics(readings, now, zone).getValue(MetricTile.HEART_RATE)

        // Four bars: three days ago, two empty days, today. The chart labels the last four days.
        assertEquals(listOf(65.0, 0.0, 0.0, 80.0), heartRate.week)
    }

    @Test
    fun heartRateKeepsTheLastSampleWhenTodayHasNone() {
        val readings = week(
            day(today),
            earlier = mapOf(1 to day(today.minusDays(1), heartRate = listOf(HeartRateSample(at(today.minusDays(1), 23, 50), 58)))),
        )

        val heartRate = WearReadings.metrics(readings, now, zone).getValue(MetricTile.HEART_RATE)

        assertEquals(58.0, heartRate.current!!, 0.0)
        assertTrue(heartRate.today.isEmpty())
    }

    @Test
    fun noDaysGiveNoTiles() {
        assertTrue(WearReadings.metrics(emptyList(), now, zone).isEmpty())
        assertNull(WearReadings.metrics(week(day(today)), now, zone)[MetricTile.HEART_RATE])
        assertFalse(WearReadings.metrics(week(day(today)), now, zone).containsKey(MetricTile.STEPS))
    }

    /** A worn minute as the watch stores it: [movement] in tenths, a pulse, still or moving. */
    private fun minute(epochMillis: Long, movement10: Int, bpm: Int) = WearLinkProtocol.SleepMinute(
        epochMillis = epochMillis,
        kind = WearLinkProtocol.MinuteKind.RAW,
        offsetSeconds = 0,
        flags = WearLinkProtocol.FLAG_HR_RECORDING,
        sampleCount = 375,
        movement10 = movement10,
        bpm = bpm,
        heartRateSd10 = 20,
        heartRateSamples = 6,
        meanMilliG = intArrayOf(0, 0, 1000),
        sdMilliG = IntArray(3) { if (movement10 > 0) 60 else 20 },
        zAngleMin = 80,
        zAngleMax = 82,
        zAngleDelta10 = if (movement10 > 0) 60 else 4,
    )

    /** 22:30 to 08:00: half an hour restless, eight hours still with a night's pulse, an hour restless, so the night has ended. */
    private fun nightRows(): List<WearLinkProtocol.SleepMinute> {
        val start = today.minusDays(1).atTime(22, 30).toInstant(zone).toEpochMilli()
        return List(570) { index ->
            val time = start + index * 60_000L
            when {
                index < 30 || index >= 510 -> minute(time, movement10 = 80 + (index * 5) % 80, bpm = 64)
                else -> minute(time, movement10 = 0, bpm = (52 + 4 * sin(2 * Math.PI * index / 90.0)).toInt())
            }
        }
    }

    @Test
    fun aNightTheWatchRecordedFillsSleepAndRestingHeartRate() {
        val night = WearReadings.night(today, zone, nightRows())

        requireNotNull(night)
        assertTrue("asleep ${night.asleepMinutes}", night.asleepMinutes > 360)
        assertTrue("stages ${night.stageMinutes}", night.stageMinutes.values.sum() >= night.asleepMinutes)
        val resting = requireNotNull(night.restingHeartRate)
        assertTrue("resting $resting", resting in 46..56)

        val readings = week(WearReadings.day(today, zone, emptyList(), emptyList(), emptyList(), sleep = night))
        val metrics = WearReadings.metrics(readings, now, zone)
        assertEquals(night.asleepMinutes.toDouble(), metrics.getValue(MetricTile.SLEEP).current!!, 0.0)
        assertEquals(7, metrics.getValue(MetricTile.SLEEP).week.size)
        assertEquals(resting.toDouble(), metrics.getValue(MetricTile.RESTING_HEART_RATE).current!!, 0.0)
        assertEquals(night.stageMinutes, WearReadings.sleepStages(readings))
    }

    /** 08:00 to 16:00 on [today]: walking at a pulse of 75, with a still 45 minute nap from 10:00 at 58. */
    private fun napDayRows(): List<WearLinkProtocol.SleepMinute> = List(8 * 60) { index ->
        val time = at(today, 8) + index * 60_000L
        if (index in 120 until 165) minute(time, movement10 = 0, bpm = 58 + (index * 7) % 3) else minute(time, movement10 = 90 + (index * 5) % 60, bpm = 73 + (index * 7) % 5)
    }

    @Test
    fun aNapAloneFillsTheSleepTileAndAddsToANight() {
        val napOnly = requireNotNull(WearReadings.night(today, zone, napDayRows()))
        assertEquals(0, napOnly.asleepMinutes)
        assertTrue("nap ${napOnly.napMinutes}", napOnly.napMinutes in 30..45)
        assertNull(napOnly.restingHeartRate)

        val both = requireNotNull(WearReadings.night(today, zone, nightRows().filter { it.epochMillis < at(today, 8) } + napDayRows()))
        assertTrue(both.asleepMinutes > 360)
        assertEquals(napOnly.napMinutes, both.napMinutes)
        val readings = week(WearReadings.day(today, zone, emptyList(), emptyList(), emptyList(), sleep = both))
        assertEquals(both.totalMinutes.toDouble(), WearReadings.metrics(readings, now, zone).getValue(MetricTile.SLEEP).current!!, 0.0)
    }

    @Test
    fun theNightsHrvIsTheMeanOfItsWindowsAndFillsTheTile() {
        val windows = listOf(
            WearLinkProtocol.HrvWindow(at(today, 2), 400, 280),
            WearLinkProtocol.HrvWindow(at(today, 3), 500, 270),
        )
        assertEquals(45.0, WearReadings.nightHrv(windows)!!, 1e-9)
        assertNull(WearReadings.nightHrv(emptyList()))

        val yesterday = day(today.minusDays(1)).copy(nightHrv = 38.0)
        val readings = week(day(today).copy(nightHrv = 45.0), earlier = mapOf(1 to yesterday))
        val hrv = WearReadings.metrics(readings, now, zone).getValue(MetricTile.HRV)

        assertEquals(45.0, hrv.current!!, 0.0)
        assertEquals(listOf(38.0, 45.0), hrv.week)
    }

    @Test
    fun noWindowsMeanNoHrvTile() {
        assertFalse(WearReadings.metrics(week(day(today)), now, zone).containsKey(MetricTile.HRV))
    }

    @Test
    fun noMinutesMeanNoSleepTile() {
        assertNull(WearReadings.night(today, zone, emptyList()))
        assertFalse(WearReadings.metrics(week(day(today)), now, zone).containsKey(MetricTile.SLEEP))
    }
}
