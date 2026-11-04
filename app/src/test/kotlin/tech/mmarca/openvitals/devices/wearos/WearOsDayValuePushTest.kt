package tech.mmarca.openvitals.devices.wearos

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import tech.mmarca.openvitals.domain.model.DailyHydration
import tech.mmarca.openvitals.domain.model.WeightEntry
import tech.mmarca.openvitals.wearlink.WearLinkProtocol.DayKind

class WearOsDayValuePushTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val today = LocalDate.of(2026, 11, 4)

    private fun push(
        water: List<DailyHydration> = emptyList(),
        weights: List<WeightEntry> = emptyList(),
    ) = WearOsDayValuePush(object : WearOsNodePort by io.mockk.mockk(relaxed = true) {}, { 70.0 }, { 0.7 }, { _, _ -> water }, { _, _ -> weights }, { zone }, { today })

    private fun at(date: LocalDate, hour: Int): Instant = date.atTime(hour, 0).atZone(zone).toInstant()

    @Test
    fun `water is millilitres in tenths and weight is the day's last, in tenths of a kilogram`() = runBlocking {
        val values = push(
            water = listOf(DailyHydration(today, 1.85)),
            weights = listOf(
                WeightEntry(at(today, 7), 81.0, "a"),
                WeightEntry(at(today, 20), 81.24, "b"),
            ),
        ).values()

        assertEquals(
            listOf(
                WearOsDayValue(DayKind.WEIGHT, today.toEpochDay(), 812),
                WearOsDayValue(DayKind.HYDRATION, today.toEpochDay(), 18_500),
            ),
            values.sortedBy { it.kind },
        )
    }

    @Test
    fun `an empty day, a zero reading and a day outside the week send nothing`() = runBlocking {
        val values = push(
            water = listOf(DailyHydration(today, 0.0), DailyHydration(today.minusDays(7), 2.0)),
            weights = listOf(WeightEntry(at(today.minusDays(9), 8), 80.0, "a"), WeightEntry(at(today, 8), 0.0, "b")),
        ).values()

        assertEquals(emptyList<WearOsDayValue>(), values)
    }

    @Test
    fun `values come oldest day first`() = runBlocking {
        val values = push(
            water = listOf(DailyHydration(today, 1.0), DailyHydration(today.minusDays(2), 2.0)),
        ).values()

        assertEquals(listOf(today.minusDays(2).toEpochDay(), today.toEpochDay()), values.map { it.epochDay })
    }

    @Test
    fun `a refused read sends nothing and remembers nothing`() = runBlocking {
        var pushes = 0
        val port = object : WearOsNodePort by io.mockk.mockk(relaxed = true) {
            override suspend fun pushDayValues(a: String?, n: String?, v: List<WearOsDayValue>, p: WearOsStepProfile): Int? {
                pushes++
                return v.size + 2
            }
        }
        var remembered: SentDayValues? = null
        val push = WearOsDayValuePush(
            port, { 70.0 }, { 0.7 }, { _, _ -> throw IllegalStateException("rate limited") }, { _, _ -> emptyList() },
            { zone }, { today }, { remembered }, { _, sent -> remembered = sent },
        )

        push.send("watch", "AA", "w")

        assertEquals(0, pushes)
        assertEquals(null, remembered)
    }

    @Test
    fun `the same values are not pushed twice within a day, but are after it`() = runBlocking {
        var pushes = 0
        val port = object : WearOsNodePort by io.mockk.mockk(relaxed = true) {
            override suspend fun pushDayValues(a: String?, n: String?, v: List<WearOsDayValue>, p: WearOsStepProfile): Int? {
                pushes++
                return v.size + 2
            }
        }
        var remembered: SentDayValues? = null
        var now = 1_000L
        val push = WearOsDayValuePush(
            port, { 70.0 }, { 0.7 }, { _, _ -> listOf(DailyHydration(today, 1.0)) }, { _, _ -> emptyList() },
            { zone }, { today }, { remembered }, { _, sent -> remembered = sent }, { now },
        )

        push.send("watch", "AA", "w")
        push.send("watch", "AA", "w")
        assertEquals(1, pushes)

        now += WearOsDayValuePush.RESEND_AFTER_MILLIS
        push.send("watch", "AA", "w")
        assertEquals(2, pushes)
    }
}
