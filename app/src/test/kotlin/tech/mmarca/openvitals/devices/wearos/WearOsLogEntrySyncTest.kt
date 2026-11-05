package tech.mmarca.openvitals.devices.wearos

import androidx.health.connect.client.records.Record
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import tech.mmarca.openvitals.data.repository.AppleHealthImportRepository
import tech.mmarca.openvitals.devices.FakeSharedPreferences
import tech.mmarca.openvitals.wearlink.WearLinkProtocol.LogKind

class WearOsLogEntrySyncTest {

    private val t1 = Instant.parse("2026-10-07T10:00:00Z")
    private val t2 = t1.plusSeconds(60)
    private val t3 = t1.plusSeconds(120)
    private val zone = ZoneId.of("Europe/Berlin")

    private val importRepository = mockk<AppleHealthImportRepository>(relaxed = true)
    private val cursors = WearOsSyncCursorStore(FakeSharedPreferences())

    /** The entries the watch holds, and the page size it answers with. */
    private val watch = mutableListOf<WearOsLogEntry>()
    private var pageSize = 500
    private val pullsSince = mutableListOf<Instant>()

    /** The grants the user has given, as Health Connect permission strings. */
    private var allowed: Set<String> = ALL_PERMISSIONS
    private var mindfulnessAvailable = true

    /** Records the platform took, and the version each clientRecordId holds. */
    private val written = mutableListOf<Record>()
    private val versions = HashMap<String, Long>()
    private var refused: (Record) -> Boolean = { false }

    init {
        every { importRepository.isMindfulnessAvailable() } answers { mindfulnessAvailable }
        coEvery { importRepository.insertImportedRecords(any()) } answers {
            val records = firstArg<List<Record>>()
            if (records.any(refused)) throw SecurityException("Caller doesn't have the write permission")
            // Health Connect refuses a batch when one id is already there at the same or a higher version.
            val present = records.any { record ->
                (versions[record.metadata.clientRecordId] ?: Long.MIN_VALUE) >= record.metadata.clientRecordVersion
            }
            if (present) throw IllegalStateException("clientRecordId already exists")
            records.forEach { record ->
                written += record
                versions[record.metadata.clientRecordId!!] = record.metadata.clientRecordVersion
            }
        }
    }

    private val sync = WearOsLogEntrySync(
        importRepository = importRepository,
        cursors = cursors,
        granted = { allowed },
        mindfulnessAvailable = { importRepository.isMindfulnessAvailable() },
        zone = { zone },
    )

    /** One page after [since], as the watch answers an `LG_SINCE`. */
    private fun pull(since: Instant): WearOsLogEntryPage {
        pullsSince += since
        val after = watch.filter { it.time.isAfter(since) }.sortedBy { it.time }
        return WearOsLogEntryPage(after.take(pageSize), hasMore = after.size > pageSize)
    }

    private fun syncNow(): WearOsLogEntrySync.Outcome? = runBlocking {
        sync.sync(DEVICE) { since -> pull(since) }
    }

    private fun entry(kind: LogKind, time: Instant, value: Double = 1.0, value2: Double? = null) =
        WearOsLogEntry(time, kind, value, value2)

    @Test
    fun `a sync writes what the watch holds after the cursor and moves the cursor to its newest`() {
        watch += entry(LogKind.WATER, t1, 250.0)
        watch += entry(LogKind.WEIGHT, t2, 72.4)

        val outcome = syncNow()

        assertEquals(2, outcome?.written)
        assertEquals(t2, cursors.logCursor(DEVICE))
        assertEquals(listOf(Instant.EPOCH), pullsSince)
        assertEquals(2, written.size)
    }

    @Test
    fun `the next sync asks from the cursor and writes nothing again`() {
        watch += entry(LogKind.WATER, t1, 250.0)
        syncNow()
        pullsSince.clear()
        watch += entry(LogKind.WATER, t2, 300.0)

        val outcome = syncNow()

        assertEquals(listOf(t1), pullsSince)
        assertEquals(1, outcome?.written)
        assertEquals(2, written.size)
    }

    @Test
    fun `pages are pulled in turn, each from the newest entry of the one before`() {
        pageSize = 2
        watch += entry(LogKind.WATER, t1, 250.0)
        watch += entry(LogKind.WATER, t2, 250.0)
        watch += entry(LogKind.WATER, t3, 250.0)

        val outcome = syncNow()

        assertEquals(3, outcome?.written)
        assertEquals(listOf(Instant.EPOCH, t2), pullsSince)
        assertEquals(t3, cursors.logCursor(DEVICE))
    }

    @Test
    fun `a kind whose grant is missing stays on the watch, and the cursor stops before its first entry`() {
        watch += entry(LogKind.WATER, t1, 250.0)
        watch += entry(LogKind.WEIGHT, t2, 72.4)
        watch += entry(LogKind.WATER, t3, 300.0)
        allowed = ALL_PERMISSIONS - WearOsLogEntryImport.writePermission(LogKind.WEIGHT)!!

        val outcome = syncNow()

        assertEquals(2, outcome?.written)
        assertEquals(setOf(WearOsLogEntryImport.writePermission(LogKind.WEIGHT)), outcome?.withheld)
        assertEquals(t2.minusMillis(1), cursors.logCursor(DEVICE))
        assertEquals(0, written.count { it.metadata.clientRecordId?.contains("weight") == true })

        // The grant given, the next sync writes the held entry, and the water that the re-pull reaches is already there.
        allowed = ALL_PERMISSIONS
        pullsSince.clear()
        val later = syncNow()

        assertEquals(listOf(t2.minusMillis(1)), pullsSince)
        assertEquals(1, later?.written)
        assertTrue(later?.withheld.isNullOrEmpty())
        assertEquals(t3, cursors.logCursor(DEVICE))
    }

    @Test
    fun `caffeine without the nutrition grant stays on the watch while the water is written`() {
        watch += entry(LogKind.CAFFEINE, t1, 95.0)
        watch += entry(LogKind.WATER, t2, 250.0)
        allowed = ALL_PERMISSIONS - WearOsLogEntryImport.writePermission(LogKind.CAFFEINE)!!

        val outcome = syncNow()

        assertEquals(1, outcome?.written)
        assertEquals(setOf(WearOsLogEntryImport.writePermission(LogKind.CAFFEINE)), outcome?.withheld)
        assertEquals(t1.minusMillis(1), cursors.logCursor(DEVICE))
    }

    @Test
    fun `a write the platform refuses for want of its grant is held on the watch the same way`() {
        watch += entry(LogKind.WATER, t1, 250.0)
        watch += entry(LogKind.WATER, t2, 300.0)
        refused = { record -> record.metadata.clientRecordId?.startsWith("wearos_log_water") == true }

        val outcome = syncNow()

        assertEquals(0, outcome?.written)
        assertEquals(setOf(WearOsLogEntryImport.writePermission(LogKind.WATER)), outcome?.withheld)
        assertEquals(t1.minusMillis(1), cursors.logCursor(DEVICE))
    }

    @Test
    fun `a breathing session the device cannot record is skipped and holds no cursor`() {
        mindfulnessAvailable = false
        watch += entry(LogKind.MINDFULNESS, t1, 1.0)
        watch += entry(LogKind.WATER, t2, 250.0)

        val outcome = syncNow()

        assertEquals(1, outcome?.written)
        assertTrue(outcome?.withheld.isNullOrEmpty())
        assertEquals(t2, cursors.logCursor(DEVICE))
    }

    @Test
    fun `heart rate is pulled past but never written`() {
        watch += entry(LogKind.HEART_RATE, t1, 64.0)
        watch += entry(LogKind.WATER, t2, 250.0)

        val outcome = syncNow()

        assertEquals(1, outcome?.written)
        assertEquals(t2, cursors.logCursor(DEVICE))
    }

    @Test
    fun `no bonded watch means nothing is written and the cursor stays where it was`() {
        watch += entry(LogKind.WATER, t1, 250.0)
        val none = runBlocking { sync.sync(DEVICE) { null } }

        assertEquals(null, none)
        assertEquals(Instant.EPOCH, cursors.logCursor(DEVICE))
        assertTrue(written.isEmpty())
    }

    private companion object {
        const val DEVICE = "watch-1"

        val ALL_PERMISSIONS: Set<String> = LogKind.entries
            .mapNotNull { WearOsLogEntryImport.writePermission(it) }
            .toSet()
    }
}
