package tech.mmarca.openvitals.domain.model

import java.time.LocalDate
import java.time.ZoneId
import tech.mmarca.openvitals.domain.preferences.SleepWindow

/**
 * The slept time of each date from [sessions], filed to a date the way the day
 * view files it ([dailySleepSummary]). Used when the wearer prefers one sleep
 * source: Health Connect's own daily total follows its own source priority,
 * so the week, month and year would show another source than the day.
 * [sessions] are the merged sessions with the preferred source applied.
 */
fun dailySleepDurationsFromSessions(
    sessions: List<SleepData>,
    startDate: LocalDate,
    endDate: LocalDate,
    sleepWindow: SleepWindow,
    zone: ZoneId = ZoneId.systemDefault(),
): List<DailySleepDuration> =
    generateSequence(startDate) { it.plusDays(1).takeUnless { next -> next.isAfter(endDate) } }
        .map { date ->
            DailySleepDuration(
                date = date,
                durationMs = dailySleepSummary(sessions, date, sleepWindow, zone)?.durationMs ?: 0L,
            )
        }
        .toList()
