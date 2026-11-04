package tech.mmarca.openvitals.wear.features.measure

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openvitals.wear.PpgBuffer

class HrvSpotTest {

    /** A pulse every ~1000 ms (alternating +-25 ms) on a drifting baseline, 25 Hz, as `NightHrvTest` builds it. */
    private fun snapshot(durationMillis: Long): PpgBuffer.Snapshot {
        val random = Random(1)
        val beats = ArrayList<Double>()
        var t = 700.0
        var i = 0
        while (t < durationMillis + 2000) {
            beats += t
            t += 1000.0 + (if (i++ % 2 == 0) 25.0 else -25.0) + random.nextGaussian() * 12
        }
        val count = (durationMillis / 40).toInt()
        val times = DoubleArray(count) { it * 40.0 }
        val values = DoubleArray(count) { k ->
            val at = times[k]
            var v = 2_100_000.0 + 3_000 * sin(2 * PI * at / 1000 / 20)
            for (b in beats) {
                if (abs(at - b) > 1500) continue
                v += 1_000 * exp(-sq((at - b) / 110.0)) + 250 * exp(-sq((at - b - 330) / 130.0))
            }
            v
        }
        return PpgBuffer.Snapshot(times, values)
    }

    private fun sq(x: Double) = x * x

    @Test
    fun `no value before the window is long enough`() {
        assertNull(HrvSpot.read(snapshot(90_000)).rmssdMillis)
    }

    @Test
    fun `a clean window of enough length gives a physiological RMSSD`() {
        val rmssd = HrvSpot.read(snapshot(HrvSpot.WINDOW_MILLIS)).rmssdMillis
        assertNotNull(rmssd)
        assertTrue("rmssd $rmssd", rmssd!! in 20.0..80.0)
    }

    @Test
    fun `an empty snapshot gives nothing`() {
        assertNull(HrvSpot.read(PpgBuffer.Snapshot(DoubleArray(0), DoubleArray(0))).rmssdMillis)
    }
}
