package tech.mmarca.openvitals.wear

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Reads [WatchRecords] from the [MetricStore]. The days before today are
 * read once per day and kept; today is read on every call. Call it off the
 * main thread.
 */
class WatchRecordsSource(private val store: MetricStore) {

    private var past: Pair<LocalDate, WatchRecords>? = null

    @Synchronized
    fun load(zone: ZoneId, now: Instant): WatchRecords {
        val today = now.atZone(zone).toLocalDate()
        val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val cached = past?.takeIf { it.first == today }?.second
            ?: read(today.minusDays(WatchReadings.DAYS - 1L).atStartOfDay(zone).toInstant().toEpochMilli())
                .before(todayStart)
                .also { past = today to it }
        // From a minute before midnight: the first minute's floors count the steps of the minute before.
        return cached.before(todayStart - MINUTE_MILLIS) + read(todayStart - MINUTE_MILLIS)
    }

    private fun read(fromEpochMillis: Long): WatchRecords {
        // since() is exclusive.
        val after = fromEpochMillis - 1
        return WatchRecords(
            heartRate = store.since(WearMetrics.HEART_RATE, after, Int.MAX_VALUE),
            steps = store.since(WearMetrics.STEPS, after, Int.MAX_VALUE),
            ascent = store.since(WearMetrics.ASCENT, after, Int.MAX_VALUE),
        )
    }

    private companion object {
        const val MINUTE_MILLIS = 60_000L
    }
}
