package tech.mmarca.openvitals.wear.features.quicklog

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import tech.mmarca.openvitals.wear.MetricUiState
import tech.mmarca.openvitals.wear.MetricTile
import tech.mmarca.openvitals.wear.WearUiState

private const val DaysInWeek = 7

/**
 * Folds the watch's own entries into the metrics they feed: water into
 * hydration, drinks into caffeine, weigh-ins into weight, spot measurements into heart rate and
 * HRV where nothing else has a reading. A metric without entries keeps
 * whatever it had.
 */
internal fun WearUiState.withEntries(
    entries: List<LoggedEntry>,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): WearUiState {
    val byType = entries.groupBy { it.type }
    val updated = metrics.toMutableMap()

    byType[EntryType.WATER]?.let { water ->
        // The phone's total for a day already holds what it synced from the watch, so the larger of the two stands.
        val phoneWeek = metrics[MetricTile.HYDRATION]?.week.orEmpty()
        val week = lastDays(today).mapIndexed { index, day ->
            waterOnDay(phoneWeek.getOrNull(index), water, day, zone)
        }
        updated[MetricTile.HYDRATION] = MetricUiState(
            current = week.last(),
            goal = preferences.waterGoalMl.toDouble(),
            today = water.onDay(today, zone).hourlyTotals(zone),
            week = week,
        )
    }
    byType[EntryType.CAFFEINE]?.let { drinks ->
        // The phone sends no caffeine total, so the watch's own entries are all there is.
        val week = lastDays(today).map { day -> drinks.onDay(day, zone).sumOf { it.value } }
        updated[MetricTile.CAFFEINE] = MetricUiState(
            current = week.last(),
            today = drinks.onDay(today, zone).hourlyTotals(zone),
            week = week,
        )
    }
    byType[EntryType.WEIGHT]?.let { weights ->
        updated[MetricTile.WEIGHT] = MetricUiState(
            current = weights.last().value,
            today = weights.onDay(today, zone).map { it.value },
            week = lastDays(today).mapNotNull { day -> weights.onDay(day, zone).lastOrNull()?.value },
        )
    }
    byType[EntryType.HEART_RATE]?.let { updated.fillCurrent(MetricTile.HEART_RATE, it.last().value) }
    byType[EntryType.HRV]?.let { updated.fillCurrent(MetricTile.HRV, it.last().value) }

    return copy(metrics = updated, entries = entries)
}

/**
 * Water drunk on [day] in millilitres: the larger of the phone's total for it
 * ([phoneMl], null when none was sent) and the watch's own [entries]. The
 * phone's total already holds what it synced from the watch. The main screen
 * and the quick log tile both use this.
 */
internal fun waterOnDay(
    phoneMl: Double?,
    entries: List<LoggedEntry>,
    day: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): Double = maxOf(
    entries.filter { it.type == EntryType.WATER }.onDay(day, zone).sumOf { it.value },
    phoneMl ?: 0.0,
)

private fun MutableMap<MetricTile, MetricUiState>.fillCurrent(metric: MetricTile, value: Double) {
    val existing = this[metric] ?: MetricUiState()
    if (existing.current == null) this[metric] = existing.copy(current = value)
}

private fun List<LoggedEntry>.onDay(day: LocalDate, zone: ZoneId): List<LoggedEntry> =
    filter { Instant.ofEpochMilli(it.timeMillis).atZone(zone).toLocalDate() == day }

/** Totals per hour from midnight up to the last hour with an entry. */
private fun List<LoggedEntry>.hourlyTotals(zone: ZoneId): List<Double> {
    if (isEmpty()) return emptyList()
    val hours = map { Instant.ofEpochMilli(it.timeMillis).atZone(zone).hour }
    val totals = DoubleArray(hours.max() + 1)
    forEachIndexed { index, entry -> totals[hours[index]] += entry.value }
    return totals.toList()
}

/** The last seven days, oldest first, today last. */
private fun lastDays(today: LocalDate): List<LocalDate> =
    (DaysInWeek - 1 downTo 0).map { today.minusDays(it.toLong()) }
