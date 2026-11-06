package tech.mmarca.openvitals.wear.features.metric

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import tech.mmarca.openvitals.wear.DashboardMetric
import tech.mmarca.openvitals.wear.features.quicklog.EntryType
import tech.mmarca.openvitals.wear.features.quicklog.LoggedEntry

/**
 * The entries of one kind a tile's detail screen lists: what the wearer measured or logged on the
 * watch. Heart rate has none: its detail screen already shows the day's curve.
 */
internal fun DashboardMetric.entryType(): EntryType? = when (this) {
    DashboardMetric.HRV -> EntryType.HRV
    DashboardMetric.WEIGHT -> EntryType.WEIGHT
    DashboardMetric.HYDRATION -> EntryType.WATER
    DashboardMetric.CAFFEINE -> EntryType.CAFFEINE
    else -> null
}

/** One line of the list: when, and the value. [dayLabel] is empty for today. */
internal data class RecentEntry(val timeMillis: Long, val dayLabel: String, val value: Double)

/**
 * The newest [count] entries of [metric], newest first. A measurement that did not show up on the
 * tile (the tile shows the night's value when there is one) is still found here.
 */
internal fun recentEntries(
    entries: List<LoggedEntry>,
    metric: DashboardMetric,
    today: LocalDate,
    zone: ZoneId,
    count: Int = RecentCount,
): List<RecentEntry> {
    val type = metric.entryType() ?: return emptyList()
    return entries.asSequence()
        .filter { it.type == type }
        .sortedByDescending { it.timeMillis }
        .take(count)
        .map { entry ->
            val day = Instant.ofEpochMilli(entry.timeMillis).atZone(zone).toLocalDate()
            RecentEntry(entry.timeMillis, if (day == today) "" else day.format(DayFormat), entry.value)
        }
        .toList()
}

private const val RecentCount = 5
private val DayFormat = DateTimeFormatter.ofPattern("d.M.")
