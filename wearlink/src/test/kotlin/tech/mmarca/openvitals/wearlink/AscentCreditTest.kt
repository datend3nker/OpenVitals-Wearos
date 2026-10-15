package tech.mmarca.openvitals.wearlink

import org.junit.Assert.assertEquals
import org.junit.Test

class AscentCreditTest {

    @Test
    fun `ten steps carry one floor's height, and no more than was climbed`() {
        assertEquals(6.0, AscentCredit.creditedMetres(climbedMetres = 15.0, steps = 20), 1e-9)
        assertEquals(4.0, AscentCredit.creditedMetres(climbedMetres = 4.0, steps = 60), 1e-9)
        assertEquals(0.0, AscentCredit.creditedMetres(climbedMetres = 9.0, steps = 0), 1e-9)
    }

    @Test
    fun `a minute counts its own steps and those of the minute before`() {
        val credited = AscentCredit.creditedByMinute(
            climbedAt = mapOf(60_000L to 3.0, 180_000L to 3.0),
            stepsAt = mapOf(0L to 5, 60_000L to 5),
        )
        assertEquals(mapOf(60_000L to 3.0), credited)
    }
}
