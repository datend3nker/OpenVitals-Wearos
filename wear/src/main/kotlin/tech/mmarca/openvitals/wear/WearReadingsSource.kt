package tech.mmarca.openvitals.wear

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.Dispatchers
import tech.mmarca.openvitals.domain.insights.sleepNightWindow
import tech.mmarca.openvitals.domain.preferences.StrideLength

/** What the phone told the watch about the wearer: the weight (null until sent) and the step length. */
internal data class StepProfile(val weightKg: Double?, val strideMeters: Double)

/**
 * What the watch itself recorded, as the dashboard and the detail screens
 * read it: today by the hour and the last seven days by the day.
 *
 * A day that is over is read from the store once and kept. Only today is
 * read again, so a refresh costs one day of rows, not the week's. A
 * heart-rate day is the largest, up to some 8 600 rows.
 */
internal class WearReadingsSource(
    private val store: MetricStore,
    private val clock: Clock = Clock.systemDefaultZone(),
    /** The weight and step length the phone sent; the distance and energy estimates read them. */
    private val profile: () -> StepProfile = { StepProfile(null, StrideLength.defaultMeters) },
) {
    private val pastDays = HashMap<LocalDate, DayReadings>()
    private var pastDaysProfile: StepProfile? = null

    /** The tiles' values and last night's stages now, read again on store updates or ticker. */
    fun readings(): Flow<WearReadingsState> = merge(
        flow {
            while (true) {
                emit(Unit)
                delay(REFRESH_MILLIS)
            }
        },
        store.updates,
    ).flowOn(Dispatchers.IO).map { load() }

    internal fun load(): WearReadingsState {
        val zone = clock.zone
        val today = LocalDate.now(clock)
        // A day that is over was worked out with the profile of its time; a new weight or step length redoes it.
        profile().let { if (it != pastDaysProfile) pastDays.clear(); pastDaysProfile = it }
        val days = (DAYS - 1 downTo 0).map { back ->
            val date = today.minusDays(back.toLong())
            val day = if (back == 0) read(date, zone) else pastDays.getOrPut(date) { read(date, zone) }
            // The phone may send a past day late, so these are read each time, not kept with the day.
            day.copy(
                hydrationMl = readDay(WearMetrics.DAY_HYDRATION, date),
                weightKg = readDay(WearMetrics.DAY_WEIGHT, date),
            )
        }
        pastDays.keys.retainAll { it >= today.minusDays(DAYS.toLong()) }
        return WearReadingsState(
            metrics = WearReadings.metrics(days, clock.instant(), zone),
            sleepStages = WearReadings.sleepStages(days),
            sleepTimes = WearReadings.sleepTimes(days),
        )
    }

    /** Today only, read now: what the Today tile shows. */
    fun today(): DayReadings = read(LocalDate.now(clock), clock.zone)

    private fun read(date: LocalDate, zone: ZoneId): DayReadings {
        // A row's time is its minute or sample start; a day is [midnight, next midnight).
        val from = date.atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val until = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return WearReadings.day(
            date = date,
            zone = zone,
            heartRate = store.since(WearMetrics.HEART_RATE, from, ROW_LIMIT, until),
            steps = store.since(WearMetrics.STEPS, from, ROW_LIMIT, until),
            ascent = store.since(WearMetrics.ASCENT, from, ROW_LIMIT, until),
            sleep = readNight(date, zone),
            nightHrv = readNightHrv(date, zone),
            bodyWeightKg = profile().weightKg,
            strideMeters = profile().strideMeters,
        )
    }

    /**
     * The sleep of [date]: the night that ended that morning and the naps of
     * the day, estimated as the phone does. The minutes run from the night
     * window's start to the end of the day, so the naps have a waking level.
     */
    private fun readNight(date: LocalDate, zone: ZoneId): NightSleep? {
        val offset = zone.rules.getOffset(date.atStartOfDay(zone).toInstant())
        val (from, _) = sleepNightWindow(date, offset)
        val until = date.plusDays(1).atStartOfDay(zone).toInstant()
        val rows = store.since(WearMetrics.SLEEP_MINUTES, from.toEpochMilli() - 1, ROW_LIMIT, until.toEpochMilli())
        return WearReadings.night(date, offset, rows)
    }

    /** What the phone sent for [date], or null. The row's key is the day number, whatever the zone. */
    private fun readDay(metric: WearMetric<DayValue>, date: LocalDate): Double? {
        val key = DayValue(date.toEpochDay(), 0).keyMillis
        return store.since(metric, key - 1, 1, key + 1).firstOrNull()?.value
    }

    /** The HRV windows of the night that ended on the morning of [date], as their mean. */
    private fun readNightHrv(date: LocalDate, zone: ZoneId): Double? {
        val offset = zone.rules.getOffset(date.atStartOfDay(zone).toInstant())
        val (from, until) = sleepNightWindow(date, offset)
        return WearReadings.nightHrv(store.since(WearMetrics.HRV, from.toEpochMilli() - 1, ROW_LIMIT, until.toEpochMilli()))
    }

    private companion object {
        const val DAYS = 7
        const val REFRESH_MILLIS = 30_000L

        /** Above a day's most: a heart rate sample every ten seconds is 8 640. */
        const val ROW_LIMIT = 10_000
    }
}
