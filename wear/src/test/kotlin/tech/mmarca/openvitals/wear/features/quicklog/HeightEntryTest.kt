package tech.mmarca.openvitals.wear.features.quicklog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openvitals.wear.wireKind
import tech.mmarca.openvitals.wearlink.WearLinkProtocol

class HeightEntryTest {

    private val height = LogValueSpecs.getValue(EntryType.HEIGHT)

    @Test
    fun `height is stored in whole centimetres and shown in inches with the imperial units`() {
        // 178 cm is 70.08 in, which the stepper shows as 70 in.
        assertEquals(70.0, height.toDisplay(178.0, imperial = true).let { Math.round(it).toDouble() }, 1e-9)
        assertEquals(178.0, height.toMetric(70.0, imperial = true).let { Math.round(it).toDouble() }, 1e-9)
        assertEquals(0, height.decimals)
    }

    @Test
    fun `the metric range and default are plausible adult heights`() {
        assertTrue(height.metricDefault in height.metricRange)
        assertEquals(170.0, height.metricDefault, 1e-9)
    }

    @Test
    fun `every entry type has a protocol kind of the same name`() {
        assertEquals(
            EntryType.entries.map { it.name },
            EntryType.entries.map { it.wireKind().name },
        )
        assertTrue(WearLinkProtocol.LogKind.fromWord("HEIGHT") == WearLinkProtocol.LogKind.HEIGHT)
    }
}
