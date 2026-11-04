package tech.mmarca.openvitals.wear

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.mmarca.openvitals.wearlink.DebugTraceFile

class DebugTraceTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file get() = DebugTraceFile(folder.newFolder("debug"), "trace", zone = { ZoneOffset.UTC })

    private fun trace(file: DebugTraceFile, now: Long = 1_000L) = DebugTrace(file, { now }, async = false)

    @Test
    fun `while off nothing is written and no file is made`() {
        val log = file
        val trace = trace(log)

        trace.record("Nap", "found", "start" to 1)
        trace.recordIfChanged("k", "a", "Nap", "found")

        assertTrue(log.files().isEmpty())
    }

    @Test
    fun `while on each event is one line with its fields`() {
        val log = file
        val trace = trace(log, now = 1_234L).apply { enabled = true }

        trace.record("HeartRate", "interval", "seconds" to 10, "why" to "day")

        assertEquals(listOf("1234;HeartRate;interval;seconds=10;why=day"), log.files().single().readLines())
    }

    @Test
    fun `a result is written again only when it changes`() {
        val log = file
        val trace = trace(log).apply { enabled = true }

        trace.recordIfChanged("nap", "a", "Nap", "found", "n" to 1)
        trace.recordIfChanged("nap", "a", "Nap", "found", "n" to 1)
        trace.recordIfChanged("nap", "b", "Nap", "found", "n" to 2)
        trace.forgetChanges()
        trace.recordIfChanged("nap", "b", "Nap", "found", "n" to 2)

        assertEquals(3, log.files().single().readLines().size)
    }

    @Test
    fun `the background writer finishes after flush`() {
        val log = file
        val trace = DebugTrace(log, { 5L }).apply { enabled = true }

        repeat(50) { trace.record("S", "e", "i" to it) }
        trace.flush()

        assertEquals(50, log.files().single().readLines().size)
    }

    @Test
    fun `the shared instance does nothing until a folder is installed and the switch is on`() {
        assertFalse(DebugTrace.shared.enabled)
        DebugTrace.event("S", "e")
        assertFalse(DebugTrace.isEnabled)
    }
}
