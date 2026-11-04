package tech.mmarca.openvitals.wear

import kotlin.math.roundToInt
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import tech.mmarca.openvitals.domain.insights.EstimatedStage
import tech.mmarca.openvitals.domain.insights.StepEstimates
import tech.mmarca.openvitals.domain.insights.WearNaps
import tech.mmarca.openvitals.domain.insights.WearNight
import tech.mmarca.openvitals.domain.insights.WearSleepEstimator
import tech.mmarca.openvitals.domain.model.WearSleepMinuteMapping
import tech.mmarca.openvitals.domain.preferences.StrideLength
import tech.mmarca.openvitals.wearlink.AscentCredit
import tech.mmarca.openvitals.wearlink.WearLinkProtocol.AscentMinute
import tech.mmarca.openvitals.wearlink.WearLinkProtocol.HeartRateSample
import tech.mmarca.openvitals.wearlink.WearLinkProtocol.HrvWindow
import tech.mmarca.openvitals.wearlink.WearLinkProtocol.SleepMinute
import tech.mmarca.openvitals.wearlink.WearLinkProtocol.StepMinute

/**
 * One local day of what the watch recorded, cut down to what the screens
 * show. A day that is over does not change, so it is made once and kept;
 * only today is read again.
 */
internal data class DayReadings(
    val date: LocalDate,
    /** Steps per hour of the day, 24 values. */
    val stepsByHour: List<Double>,
    /** Metres of height that count, per hour, 24 values. See [AscentCredit]. */
    val ascentByHour: List<Double>,
    /** Mean heart rate per five minutes with a sample, oldest first. */
    val heartRate: List<Double>,
    val heartRateMean: Double?,
    val latestHeartRate: HeartRateSample?,
    val hasSteps: Boolean,
    val hasAscent: Boolean,
    /** The night that ended on the morning of [date], or null when the watch saw none. */
    val sleep: NightSleep? = null,
    /** Mean RMSSD in milliseconds of the night's HRV windows, or null when the watch took none. */
    val nightHrv: Double? = null,
    /** Millilitres of water the phone sent for the day, or null when it sent none. */
    val hydrationMl: Double? = null,
    /** Kilograms the phone sent for the day, or null when it sent none. */
    val weightKg: Double? = null,
    /** Metres per hour of the day from the steps and the step length, 24 values. */
    val distanceByHour: List<Double> = List(24) { 0.0 },
    /** Active kilocalories per hour from the step minutes, 24 values; empty with no known weight. */
    val kcalByHour: List<Double> = emptyList(),
)

/**
 * One day's sleep as the phone estimates it, computed on the watch with the
 * same code (`WearNight` and `WearNaps` in `:healthcalc`).
 */
internal data class NightSleep(
    /** The night's sleep, without naps. */
    val asleepMinutes: Int,
    /** Sleep in naps that began on the day. */
    val napMinutes: Int = 0,
    /** Minutes per stage, awake included. */
    val stageMinutes: Map<SleepStage, Double>,
    /** Null while the night runs, or when too few minutes had a pulse. */
    val restingHeartRate: Int?,
    /** When the wearer settled in for the night, before sleep onset. Null without a night. */
    val bedTime: Instant? = null,
    /** When sleep actually started, from the watch's own estimate. Null without a night. */
    val fellAsleep: Instant? = null,
    /** When the night's sleep ended. Null without a night. */
    val wokeUp: Instant? = null,
) {
    /** What the sleep tile counts: the night and the day's naps. */
    val totalMinutes: Int get() = asleepMinutes + napMinutes

    /** Time between settling in and actually falling asleep, or null without both. */
    val latencyMinutes: Long?
        get() = bedTime?.let { bed -> fellAsleep?.let { asleep -> (asleep.epochSecond - bed.epochSecond) / 60 } }
}

/** What the screens read: the tiles and last night's stages. */
internal data class WearReadingsState(
    val metrics: Map<MetricTile, MetricUiState> = emptyMap(),
    val sleepStages: Map<SleepStage, Double> = emptyMap(),
    val sleepTimes: SleepTimes = SleepTimes(),
)

/** Last night's clock times, for the sleep detail screen. Null fields mean no night was estimated. */
data class SleepTimes(
    val bedTime: Instant? = null,
    val fellAsleep: Instant? = null,
    val wokeUp: Instant? = null,
    val latencyMinutes: Long? = null,
)

/** The Today tile's figures. */
internal data class TodayTotals(val steps: Int?, val heartRate: Int?)

/** Turns the watch's own rows into the values the dashboard and the detail screens read. */
internal object WearReadings {

    private const val HOURS = 24
    private const val DECIMETRES_PER_METRE = 10.0
    private const val MINUTE_MILLIS = 60_000L
    private const val HEART_RATE_BUCKET_MILLIS = 5 * MINUTE_MILLIS

    /** The rows of [date] only; the caller reads them from the store. */
    fun day(
        date: LocalDate,
        zone: ZoneId,
        heartRate: List<HeartRateSample>,
        steps: List<StepMinute>,
        ascent: List<AscentMinute>,
        sleep: NightSleep? = null,
        nightHrv: Double? = null,
        hydrationMl: Double? = null,
        weightKg: Double? = null,
        bodyWeightKg: Double? = null,
        strideMeters: Double = StrideLength.defaultMeters,
    ): DayReadings {
        val stepsAt = steps.associate { it.epochMillis to it.steps }
        val stepsByHour = DoubleArray(HOURS)
        for (minute in steps) stepsByHour[hourOf(minute.epochMillis, zone)] += minute.steps.toDouble()

        val kcalByHour = DoubleArray(HOURS)
        if (bodyWeightKg != null) {
            steps.groupBy { hourOf(it.epochMillis, zone) }.forEach { (hour, minutes) ->
                kcalByHour[hour] = StepEstimates.activeKcal(minutes.map { it.steps }, bodyWeightKg)
            }
        }

        val ascentByHour = DoubleArray(HOURS)
        for (minute in ascent) {
            val walked = (stepsAt[minute.epochMillis - MINUTE_MILLIS] ?: 0) + (stepsAt[minute.epochMillis] ?: 0)
            ascentByHour[hourOf(minute.epochMillis, zone)] +=
                AscentCredit.creditedMetres(minute.decimetres / DECIMETRES_PER_METRE, walked)
        }

        return DayReadings(
            date = date,
            stepsByHour = stepsByHour.toList(),
            ascentByHour = ascentByHour.toList(),
            heartRate = heartRate.groupBy { it.epochMillis / HEART_RATE_BUCKET_MILLIS }
                .toSortedMap()
                .values
                .map { bucket -> bucket.map { it.bpm }.average() },
            heartRateMean = heartRate.takeIf { it.isNotEmpty() }?.map { it.bpm }?.average(),
            latestHeartRate = heartRate.maxByOrNull { it.epochMillis },
            hasSteps = steps.isNotEmpty(),
            hasAscent = ascent.isNotEmpty(),
            sleep = sleep,
            nightHrv = nightHrv,
            hydrationMl = hydrationMl,
            weightKg = weightKg,
            distanceByHour = stepsByHour.map { StepEstimates.distanceMetres(it, strideMeters) },
            kcalByHour = if (bodyWeightKg != null) kcalByHour.toList() else emptyList(),
        )
    }

    /** The night's HRV: the mean RMSSD of its windows, or null with none. */
    fun nightHrv(windows: List<HrvWindow>): Double? =
        windows.takeIf { it.isNotEmpty() }?.map { it.rmssdMillis }?.average()

    /**
     * The sleep of [date] from the watch's minute rows: the night that ended
     * that morning and the naps that began that day, or null when the rows
     * hold neither. The rows should run from 18:00 the evening before to the
     * end of the day; fewer rows find fewer naps.
     */
    fun night(date: LocalDate, offset: ZoneOffset, rows: List<SleepMinute>): NightSleep? {
        val minutes = rows.map(WearSleepMinuteMapping::toDomain)
        val night = WearNight.estimate(minutes, date, offset)?.takeIf { it.session.sleepMinutes > 0 }
        val napReport = WearNaps.ofDayWithReasons(minutes, date, offset)
        val naps = napReport.naps.map { it.nap }.filter { it.session.sleepMinutes > 0 }
        traceSleep(date, night, napReport)
        if (night == null && naps.isEmpty()) return null
        val sessions = listOfNotNull(night?.session) + naps.map { it.session }
        val stageMinutes = sessions.flatMap { it.stages }
            .mapNotNull { span -> span.stage.toSleepStage()?.let { it to (span.end.toEpochMilli() - span.start.toEpochMilli()) / MINUTE_MILLIS.toDouble() } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, spans) -> spans.sum() }
        return NightSleep(
            asleepMinutes = night?.session?.sleepMinutes ?: 0,
            napMinutes = naps.sumOf { it.session.sleepMinutes },
            stageMinutes = stageMinutes,
            restingHeartRate = night?.let { WearNight.restingHeartRate(minutes, it) },
            bedTime = night?.bedTime,
            fellAsleep = night?.session?.onset,
            wokeUp = night?.session?.end,
        )
    }

    /**
     * Writes the night and the naps of [date] to the debug trace when the result changed since the
     * last time it was written, with the numbers that decided it. Does nothing while the switch is off.
     */
    private fun traceSleep(date: LocalDate, night: WearSleepEstimator.Night?, report: WearNaps.Report) {
        if (!DebugTrace.isEnabled) return
        val nightFields = night?.let { WearNight.reasons(it) }.orEmpty()
        val napSignature = report.naps.joinToString("|") {
            "${it.nap.onset.toEpochMilli()}-${it.nap.end.toEpochMilli()}-${it.nap.session.sleepMinutes}"
        }
        val signature = nightFields.joinToString(",") { it.second.toString() } + "#" + napSignature +
            "#${report.wakingLevel?.toInt()}"
        if (!DebugTrace.changed("sleep-$date", signature)) return
        run {
            night?.let { DebugTrace.event("Sleep", "night", *(listOf<Pair<String, Any?>>("date" to date) + WearNight.reasons(it)).toTypedArray()) }
            DebugTrace.event(
                "Sleep", "nap_baseline",
                "date" to date, "wakingLevel" to report.wakingLevel, "dayMinutes" to report.baselineMinutes,
                "needed" to WearNaps.Config().minBaselineMinutes,
            )
            for (reason in report.naps) {
                DebugTrace.event(
                    "Sleep", "nap",
                    "date" to date,
                    "onset" to reason.nap.onset.toEpochMilli(), "end" to reason.nap.end.toEpochMilli(),
                    "durationMin" to (reason.nap.end.epochSecond - reason.nap.onset.epochSecond) / 60,
                    "sleepMin" to reason.nap.session.sleepMinutes,
                    "wakingLevel" to reason.wakingLevel, "requiredDrop" to reason.requiredDrop,
                    "daytime" to reason.daytime, "medianHr" to reason.medianHeartRate,
                    "sleepShare" to reason.sleepShare, "movement" to reason.meanMovement,
                    "lookbackMin" to reason.onsetLookbackMinutes,
                )
            }
        }
    }

    /**
     * The tiles the watch's own recording can fill. [days] are the last
     * seven, oldest first, today last. A tile with no row in any of them is
     * left out, so the screen says "no reading yet" instead of showing zero.
     */
    fun metrics(days: List<DayReadings>, now: Instant, zone: ZoneId): Map<MetricTile, MetricUiState> {
        val today = days.lastOrNull() ?: return emptyMap()
        val thisHour = hourOf(now.toEpochMilli(), zone)
        return buildMap {
            if (days.any { it.hasSteps }) {
                put(MetricTile.STEPS, cumulative(days, thisHour) { it.stepsByHour })
            }
            if (days.any { it.hasSteps }) {
                put(MetricTile.DISTANCE, cumulative(days, thisHour) { it.distanceByHour })
            }
            if (days.any { it.kcalByHour.isNotEmpty() && it.hasSteps }) {
                put(MetricTile.ACTIVE_CALORIES, cumulative(days, thisHour) { it.kcalByHour.ifEmpty { List(24) { 0.0 } } })
            }
            if (days.any { it.hasAscent }) {
                put(MetricTile.ELEVATION, cumulative(days, thisHour) { it.ascentByHour })
                put(
                    MetricTile.FLOORS,
                    cumulative(days, thisHour) { day -> day.ascentByHour.map { it / AscentCredit.METRES_PER_FLOOR } },
                )
            }
            val latest = days.lastNotNullOfOrNull { it.latestHeartRate }
            if (latest != null) {
                put(
                    MetricTile.HEART_RATE,
                    MetricUiState(
                        current = latest.bpm.toDouble(),
                        today = today.heartRate,
                        // The chart labels its bars as the last days, today last: a gap stays a zero bar.
                        week = days.dropWhile { it.heartRateMean == null }.map { it.heartRateMean ?: 0.0 },
                    ),
                )
            }
            if (days.any { it.sleep != null }) {
                put(
                    MetricTile.SLEEP,
                    MetricUiState(
                        current = today.sleep?.totalMinutes?.toDouble(),
                        week = days.map { it.sleep?.totalMinutes?.toDouble() ?: 0.0 },
                    ),
                )
            }
            if (days.any { it.nightHrv != null }) {
                put(
                    MetricTile.HRV,
                    MetricUiState(
                        current = today.nightHrv,
                        week = days.dropWhile { it.nightHrv == null }.map { it.nightHrv ?: 0.0 },
                    ),
                )
            }
            if (days.any { it.hydrationMl != null }) {
                put(
                    MetricTile.HYDRATION,
                    MetricUiState(
                        current = today.hydrationMl ?: 0.0,
                        week = days.map { it.hydrationMl ?: 0.0 },
                    ),
                )
            }
            if (days.any { it.weightKg != null }) {
                put(
                    MetricTile.WEIGHT,
                    MetricUiState(
                        current = days.lastNotNullOfOrNull { it.weightKg },
                        week = days.dropWhile { it.weightKg == null }.mapNotNull { it.weightKg },
                    ),
                )
            }
            if (days.any { it.sleep?.restingHeartRate != null }) {
                put(
                    MetricTile.RESTING_HEART_RATE,
                    MetricUiState(
                        current = today.sleep?.restingHeartRate?.toDouble(),
                        week = days.dropWhile { it.sleep?.restingHeartRate == null }
                            .map { it.sleep?.restingHeartRate?.toDouble() ?: 0.0 },
                    ),
                )
            }
        }
    }

    /** Last night's stages, for the sleep detail screen. */
    fun sleepStages(days: List<DayReadings>): Map<SleepStage, Double> =
        days.lastOrNull()?.sleep?.stageMinutes.orEmpty()

    /** Last night's bedtime, sleep onset and wake, for the sleep detail screen. */
    fun sleepTimes(days: List<DayReadings>): SleepTimes {
        val sleep = days.lastOrNull()?.sleep ?: return SleepTimes()
        return SleepTimes(
            bedTime = sleep.bedTime,
            fellAsleep = sleep.fellAsleep,
            wokeUp = sleep.wokeUp,
            latencyMinutes = sleep.latencyMinutes,
        )
    }

    private fun EstimatedStage.toSleepStage(): SleepStage? = when (this) {
        EstimatedStage.AWAKE -> SleepStage.AWAKE
        EstimatedStage.LIGHT -> SleepStage.LIGHT
        EstimatedStage.DEEP -> SleepStage.DEEP
        EstimatedStage.REM -> SleepStage.REM
        EstimatedStage.UNKNOWN -> null
    }

    /** What the Today tile shows: the day's steps and the latest heart rate, each null when there is none. */
    fun totals(day: DayReadings): TodayTotals = TodayTotals(
        steps = day.stepsByHour.sum().roundToInt().takeIf { day.hasSteps },
        heartRate = day.latestHeartRate?.bpm,
    )

    private fun cumulative(
        days: List<DayReadings>,
        thisHour: Int,
        hours: (DayReadings) -> List<Double>,
    ): MetricUiState {
        val today = hours(days.last())
        return MetricUiState(
            current = today.sum(),
            today = today.take(thisHour + 1),
            week = days.map { hours(it).sum() },
        )
    }

    private fun hourOf(epochMillis: Long, zone: ZoneId): Int =
        Instant.ofEpochMilli(epochMillis).atZone(zone).hour

    private fun <T, R : Any> List<T>.lastNotNullOfOrNull(selector: (T) -> R?): R? =
        asReversed().firstNotNullOfOrNull(selector)
}
