package tech.mmarca.openvitals.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PpgBufferTest {
    @Test
    fun `keeps only the trailing window, oldest first`() {
        val buffer = PpgBuffer(maxMillis = 1_000)
        for (i in 0..100) buffer.add(timeMillis = i * 40.0, value = i.toDouble())

        val snapshot = buffer.snapshot()

        assertTrue(snapshot.spanMillis <= 1_000.0)
        assertEquals(snapshot.values.size, snapshot.times.size)
        assertEquals(100.0, snapshot.values.last(), 0.0)
        assertTrue(snapshot.times.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun `survives far more samples than its first capacity`() {
        val buffer = PpgBuffer(maxMillis = 60_000)
        for (i in 0 until 20_000) buffer.add(i * 40.0, i.toDouble())

        val snapshot = buffer.snapshot()

        assertEquals(19_999.0, snapshot.values.last(), 0.0)
        assertTrue(snapshot.times.first() >= 20_000 * 40.0 - 60_000 - 80)
        assertEquals(snapshot.times.size, snapshot.values.size)
    }

    @Test
    fun `an empty buffer snapshots empty`() {
        val snapshot = PpgBuffer(1_000).snapshot()

        assertTrue(snapshot.isEmpty)
        assertEquals(0.0, snapshot.spanMillis, 0.0)
    }
}
