package tech.mmarca.openvitals.wear

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import tech.mmarca.openvitals.wearlink.AscentCredit
import tech.mmarca.openvitals.wearlink.WearLinkProtocol

/** What the watch recorded over the last 7 days, as read from the [MetricStore]. */
data class WatchRecords(
    val heartRate: List<WearLinkProtocol.HeartRateSample> = emptyList(),
    val steps: List<WearLinkProtocol.StepMinute> = emptyList(),
    val ascent: List<WearLinkProtocol.AscentMinute> = emptyList(),
) {
    operator fun plus(other: WatchRecords) =
        WatchRecords(heartRate + other.heartRate, steps + other.steps, ascent + other.ascent)

    /** Only the rows at or after [epochMillis]. */
    fun from(epochMillis: Long) = WatchRecords(
        heartRate.filter { it.epochMillis >= epochMillis },
        steps.filter { it.epochMillis >= epochMillis },
        ascent.filter { it.epochMillis >= epochMillis },
    )

    /** Only the rows before [epochMillis]. */
    fun before(epochMillis: Long) = WatchRecords(
        heartRate.filter { it.epochMillis < epochMillis },
        steps.filter { it.epochMillis < epochMillis },
        ascent.filter { it.epochMillis < epochMillis },
    )
}

/**
 * Today in detail and the last 7 days as daily values, for the metrics the
 * watch records itself. The week starts at the first day with data. Floors and elevation count only the height that
 * [AscentCredit] credits, as on the phone.
 */
object WatchReadings {

    const val DAYS = 7

    /** Heart rate today: one average per this many minutes. */
    const val HEART_RATE_BUCKET_MINUTES = 5

    fun summarize(records: WatchRecords, zone: ZoneId, now: Instant): Map<MetricTile, MetricUiState> {
        val today = now.atZone(zone).toLocalDate()
        val days = (DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) }
        val dayOf = { epochMillis: Long -> Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate() }
        val hourOf = { epochMillis: Long -> Instant.ofEpochMilli(epochMillis).atZone(zone).hour }
        val hoursSoFar = now.atZone(zone).hour + 1

        val stepsByMinute = latestPerMinute(records.steps, { it.epochMillis }) { it.steps }
        val creditedMetres = AscentCredit.creditedByMinute(
            climbedAt = latestPerMinute(records.ascent, { it.epochMillis }) { it.decimetres / DECIMETRES_PER_METRE },
            stepsAt = stepsByMinute,
        )

        return buildMap {
            cumulative(stepsByMinute.mapValues { it.value.toDouble() }, today, days, dayOf, hourOf, hoursSoFar)
                ?.let { put(MetricTile.STEPS, it) }
            cumulative(creditedMetres.mapValues { it.value / AscentCredit.METRES_PER_FLOOR }, today, days, dayOf, hourOf, hoursSoFar)
                ?.let { put(MetricTile.FLOORS, it) }
            cumulative(creditedMetres, today, days, dayOf, hourOf, hoursSoFar)
                ?.let { put(MetricTile.ELEVATION, it) }
            heartRate(records.heartRate, today, days, dayOf, zone)?.let { put(MetricTile.HEART_RATE, it) }
        }
    }

    /** Null when nothing was recorded in the 7 days. */
    private fun cumulative(
        perMinute: Map<Long, Double>,
        today: LocalDate,
        days: List<LocalDate>,
        dayOf: (Long) -> LocalDate,
        hourOf: (Long) -> Int,
        hoursSoFar: Int,
    ): MetricUiState? {
        if (perMinute.isEmpty()) return null
        val perDay = HashMap<LocalDate, Double>()
        val perHour = DoubleArray(HOURS_PER_DAY)
        for ((start, value) in perMinute) {
            val day = dayOf(start)
            perDay.merge(day, value, Double::plus)
            if (day == today) perHour[hourOf(start)] += value
        }
        return MetricUiState(
            current = perDay[today] ?: 0.0,
            today = perHour.take(hoursSoFar),
            week = days.fromFirstIn(perDay.keys).map { perDay[it] ?: 0.0 },
        )
    }

    /** Null when no sample was recorded in the 7 days. */
    private fun heartRate(
        samples: List<WearLinkProtocol.HeartRateSample>,
        today: LocalDate,
        days: List<LocalDate>,
        dayOf: (Long) -> LocalDate,
        zone: ZoneId,
    ): MetricUiState? {
        if (samples.isEmpty()) return null
        val sorted = samples.sortedBy { it.epochMillis }
        val byDay = sorted.groupBy { dayOf(it.epochMillis) }
        val todays = byDay[today].orEmpty()
        val buckets = todays.groupBy {
            val time = Instant.ofEpochMilli(it.epochMillis).atZone(zone)
            (time.hour * MINUTES_PER_HOUR + time.minute) / HEART_RATE_BUCKET_MINUTES
        }
        return MetricUiState(
            current = todays.lastOrNull()?.bpm?.toDouble(),
            today = buckets.values.map { bucket -> bucket.map { it.bpm }.average() },
            week = days.fromFirstIn(byDay.keys).map { day -> byDay[day]?.map { it.bpm }?.average() ?: 0.0 },
        )
    }

    /** The days from the first one with data, so the days before the watch recorded do not count as zero. */
    private fun List<LocalDate>.fromFirstIn(withData: Set<LocalDate>): List<LocalDate> =
        dropWhile { it !in withData }

    /** The minute's value from its newest row: the recorders rewrite a minute as late readings arrive. */
    private fun <T, V> latestPerMinute(rows: List<T>, timeOf: (T) -> Long, valueOf: (T) -> V): LinkedHashMap<Long, V> {
        val out = LinkedHashMap<Long, V>()
        for (row in rows) out[timeOf(row)] = valueOf(row)
        return out
    }

    private const val HOURS_PER_DAY = 24
    private const val MINUTES_PER_HOUR = 60
    private const val DECIMETRES_PER_METRE = 10.0
}
