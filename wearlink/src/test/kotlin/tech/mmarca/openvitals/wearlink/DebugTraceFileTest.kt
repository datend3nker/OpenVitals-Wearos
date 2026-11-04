package tech.mmarca.openvitals.wearlink

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DebugTraceFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun day(date: LocalDate, minute: Int = 0) = date.atStartOfDay().plusMinutes(minute.toLong()).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun writer(maxDays: Int = 7, maxBytes: Long = 5L * 1024 * 1024) =
        DebugTraceFile(folder.root, "trace", maxDays, maxBytes) { ZoneOffset.UTC }

    @Test
    fun `a line holds the time, source, event and the fields`() {
        assertEquals("5;Nap;found;start=7;level=61.5", DebugTraceFile.line(5, "Nap", "found", listOf("start" to 7, "level" to 61.5)))
        assertEquals("5;A;b", DebugTraceFile.line(5, "A", "b"))
    }

    @Test
    fun `a separator or a line break in a value becomes a space`() {
        assertEquals("1;S;E;why=a b c d", DebugTraceFile.line(1, "S", "E", listOf("why" to "a;b=c\nd")))
    }

    @Test
    fun `each day gets its own file`() {
        val log = writer()
        log.append(day(LocalDate.of(2026, 11, 1)), "one")
        log.append(day(LocalDate.of(2026, 11, 1), 90), "two")
        log.append(day(LocalDate.of(2026, 11, 2)), "three")

        assertEquals(listOf("trace-20261101.log", "trace-20261102.log"), log.files().map { it.name })
        assertEquals(listOf("one", "two"), log.files().first().readLines())
    }

    @Test
    fun `only the newest days are kept`() {
        val log = writer(maxDays = 3)
        for (d in 1..6) log.append(day(LocalDate.of(2026, 11, d)), "x$d")

        assertEquals(listOf("trace-20261104.log", "trace-20261105.log", "trace-20261106.log"), log.files().map { it.name })
    }

    @Test
    fun `a file at its limit takes no more lines`() {
        val log = writer(maxBytes = 30)
        val at = day(LocalDate.of(2026, 11, 1))
        repeat(20) { log.append(at, "0123456789") }

        val file = log.files().single()
        assertTrue("size ${file.length()}", file.length() in 30..(30 + 11))
    }

    @Test
    fun `files of another writer are neither listed nor dropped`() {
        val other = DebugTraceFile(folder.root, "wear-sync", maxDays = 1) { ZoneOffset.UTC }
        other.append(day(LocalDate.of(2026, 11, 1)), "sync")
        val log = writer(maxDays = 1)
        log.append(day(LocalDate.of(2026, 11, 2)), "a")
        log.append(day(LocalDate.of(2026, 11, 3)), "b")

        assertEquals(listOf("trace-20261103.log"), log.files().map { it.name })
        assertEquals(1, other.files().size)
    }
}
