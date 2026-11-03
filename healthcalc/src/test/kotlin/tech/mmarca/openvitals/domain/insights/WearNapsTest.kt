package tech.mmarca.openvitals.domain.insights

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openvitals.domain.model.WearSleepMinute

class WearNapsTest {

    private val offset = WearSleepFixtures.zone
    private val date = LocalDate.of(2026, 11, 3)

    /** 08:00 local. */
    private val morning = date.atTime(8, 0).toInstant(offset)

    private fun awake(index: Int, time: java.time.Instant, bpm: Float = 74f) =
        WearSleepFixtures.worn(time, movement = 9f + (index * 5) % 7, heartRate = bpm + (index * 7) % 5 - 2)

    private fun nap(index: Int, time: java.time.Instant) =
        WearSleepFixtures.worn(time, movement = 0f, heartRate = 58f + (if (index < 8) (8 - index) * 1.5f else 0f) + (index * 7) % 3 * 0.4f)

    private fun still(index: Int, time: java.time.Instant, bpm: Float) =
        WearSleepFixtures.worn(time, movement = 0f, heartRate = bpm + (index * 7) % 3 * 0.4f)

    /** Walking until 10:00, a 45 minute nap, then the day goes on for [after] minutes made by [rest]. */
    private fun day(after: Int, rest: (Int, java.time.Instant) -> WearSleepMinute): List<WearSleepMinute> =
        WearSleepFixtures.minutes(morning, 120) { i, t -> awake(i, t) } +
            WearSleepFixtures.minutes(morning.plusSeconds(120 * 60L), 45) { i, t -> nap(i, t) } +
            WearSleepFixtures.minutes(morning.plusSeconds(165 * 60L), after, rest)

    @Test
    fun `a still period with a clear heart rate drop is a nap`() {
        val rows = day(240) { i, t -> awake(i, t) }

        val naps = WearNaps.ofDay(rows, date, offset)

        assertEquals(1, naps.size)
        val nap = naps.single()
        // The pulse starts to fall at 10:00, the median needs a few minutes of it.
        assertTrue("onset ${nap.onset}", nap.onset in morning.plusSeconds(120 * 60L)..morning.plusSeconds(130 * 60L))
        assertTrue("end ${nap.end}", nap.end in morning.plusSeconds(160 * 60L)..morning.plusSeconds(168 * 60L))
        assertTrue(nap.session.sleepMinutes in 30..45)
    }

    @Test
    fun `the same minutes give the same nap identity as the day fills in`() {
        val full = day(240) { i, t -> awake(i, t) }
        val early = full.filter { it.time < morning.plusSeconds(200 * 60L) }

        assertEquals(WearNaps.ofDay(full, date, offset).single().onset, WearNaps.ofDay(early, date, offset).single().onset)
    }

    @Test
    fun `sitting still with a steady heart rate is not a nap`() {
        val rows = WearSleepFixtures.minutes(morning, 120) { i, t -> awake(i, t) } +
            WearSleepFixtures.minutes(morning.plusSeconds(120 * 60L), 90) { i, t -> still(i, t, 72f) } +
            WearSleepFixtures.minutes(morning.plusSeconds(210 * 60L), 120) { i, t -> awake(i, t) }

        assertEquals(emptyList<WearNap>(), WearNaps.ofDay(rows, date, offset))
    }

    @Test
    fun `a still period with a small drop is not a nap by day`() {
        val rows = WearSleepFixtures.minutes(morning, 120) { i, t -> awake(i, t) } +
            WearSleepFixtures.minutes(morning.plusSeconds(120 * 60L), 60) { i, t -> still(i, t, 67f) } +
            WearSleepFixtures.minutes(morning.plusSeconds(180 * 60L), 120) { i, t -> awake(i, t) }

        assertEquals(emptyList<WearNap>(), WearNaps.ofDay(rows, date, offset))
    }

    @Test
    fun `a short quiet period is not a nap`() {
        val rows = WearSleepFixtures.minutes(morning, 120) { i, t -> awake(i, t) } +
            WearSleepFixtures.minutes(morning.plusSeconds(120 * 60L), 14) { i, t -> still(i, t, 56f) } +
            WearSleepFixtures.minutes(morning.plusSeconds(134 * 60L), 120) { i, t -> awake(i, t) }

        assertEquals(emptyList<WearNap>(), WearNaps.ofDay(rows, date, offset))
    }

    @Test
    fun `without a waking level there is no nap`() {
        val rows = WearSleepFixtures.minutes(morning, 40) { i, t -> awake(i, t) } +
            WearSleepFixtures.minutes(morning.plusSeconds(40 * 60L), 45) { i, t -> nap(i, t) }

        assertEquals(emptyList<WearNap>(), WearNaps.ofDay(rows, date, offset))
    }

    @Test
    fun `the main night is not a nap and the night estimate does not change`() {
        val night = date.plusDays(1)
        val evening = date.atTime(15, 0).toInstant(offset)
        val dayRows = WearSleepFixtures.minutes(morning, 7 * 60) { i, t -> awake(i, t) }
        val nightStart = date.atTime(22, 30).toInstant(offset)
        val nightRows = WearSleepFixtures.wornNight(nightStart)
        val withDay = dayRows + nightRows

        val alone = WearNight.estimate(nightRows, night, offset)
        val together = WearNight.estimate(withDay, night, offset)

        assertNotNull(alone)
        assertEquals(alone!!.session.onset, together!!.session.onset)
        assertEquals(alone.session.end, together.session.end)
        assertEquals(alone.session.sleepMinutes, together.session.sleepMinutes)
        assertEquals(emptyList<WearNap>(), WearNaps.detectAll(withDay, offset))
        assertTrue(evening.isBefore(nightStart))
    }

    @Test
    fun `the first hours of a night not estimated yet are not a nap`() {
        val nightStart = date.atTime(23, 0).toInstant(offset)
        val rows = WearSleepFixtures.minutes(morning, 7 * 60) { i, t -> awake(i, t) } +
            WearSleepFixtures.minutes(nightStart, 90) { i, t -> WearSleepFixtures.worn(t, movement = 0f, heartRate = 56f + i % 2) }

        assertEquals(emptyList<WearNap>(), WearNaps.detectAll(rows, offset))
    }

    @Test
    fun `a nap before the night is found next to the night`() {
        val dayRows = day(8 * 60) { i, t -> awake(i, t) }
        val nightStart = date.atTime(22, 30).toInstant(offset)
        val rows = dayRows + WearSleepFixtures.wornNight(nightStart)

        val naps = WearNaps.detectAll(rows, offset)

        assertEquals(1, naps.size)
        assertTrue(naps.single().end.isBefore(nightStart))
    }
}
