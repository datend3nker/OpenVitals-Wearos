package tech.mmarca.openvitals.wear.features.quicklog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QuickLogItemTest {

    @Test
    fun `every value the quick log steps to has a row to hide`() {
        for (type in LogValueSpecs.keys) assertNotNull("no row for $type", QuickLogItem.of(type))
        listOf(EntryType.WATER, EntryType.WEIGHT, EntryType.BLOOD_PRESSURE).forEach { assertNotNull(QuickLogItem.of(it)) }
    }

    @Test
    fun `spot measurements have no quick log row`() {
        assertNull(QuickLogItem.of(EntryType.HEART_RATE))
        assertNull(QuickLogItem.of(EntryType.HRV))
    }

    @Test
    fun `hidden rows round-trip, nothing stored shows every row and unknown names are dropped`() {
        val hidden = setOf(QuickLogItem.BODY_FAT, QuickLogItem.BREATHE)
        assertEquals(hidden, decodeHidden(encodeHidden(hidden)))
        assertEquals(emptySet<QuickLogItem>(), decodeHidden(null))
        assertEquals(setOf(QuickLogItem.HEIGHT), decodeHidden("HEIGHT,CYCLE"))
    }
}
