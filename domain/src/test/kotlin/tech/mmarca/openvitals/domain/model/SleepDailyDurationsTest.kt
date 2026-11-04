package tech.mmarca.openvitals.domain.model

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.mmarca.openvitals.domain.preferences.SleepWindow

class SleepDailyDurationsTest {

    private val zone = ZoneOffset.UTC

    private fun t(day: Int, hour: Int): Instant = LocalDateTime.of(2026, 7, day, hour, 0).atZone(zone).toInstant()

    private fun session(id: String, start: Instant, end: Instant, source: String) = SleepData(
        id = id,
        startTime = start,
        endTime = end,
        durationMs = Duration.between(start, end).toMillis(),
        source = source,
    )

    @Test fun `each date gets the night the day view files under it, zero without one`() {
        val sessions = listOf(
            session("a", t(15, 23), t(16, 6), "preferred"),
            session("b", t(17, 23), t(18, 5), "preferred"),
        )

        val result = dailySleepDurationsFromSessions(
            sessions, LocalDate.of(2026, 7, 16), LocalDate.of(2026, 7, 18), SleepWindow.Default, zone,
        )

        assertEquals(listOf(LocalDate.of(2026, 7, 16), LocalDate.of(2026, 7, 17), LocalDate.of(2026, 7, 18)), result.map { it.date })
        assertEquals(listOf(7L, 0L, 6L), result.map { it.durationMs / 3_600_000 })
    }

    @Test fun `only the sessions handed in count, so a preferred source decides the total`() {
        val preferred = session("p", t(15, 23), t(16, 5), "preferred")
        val other = session("o", t(15, 22), t(16, 8), "other")
        val merged = mergeSleepSessions(listOf(preferred, other), preferredSource = "preferred")

        val result = dailySleepDurationsFromSessions(
            merged, LocalDate.of(2026, 7, 16), LocalDate.of(2026, 7, 16), SleepWindow.Default, zone,
        )

        assertEquals(6L, result.single().durationMs / 3_600_000)
    }
}
