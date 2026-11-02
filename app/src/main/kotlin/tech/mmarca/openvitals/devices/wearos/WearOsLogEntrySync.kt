package tech.mmarca.openvitals.devices.wearos

import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import tech.mmarca.openvitals.core.presentation.isPermissionFailure
import tech.mmarca.openvitals.data.repository.AppleHealthImportRepository
import tech.mmarca.openvitals.data.repository.RecordWrite
import tech.mmarca.openvitals.data.repository.insertWithOutcomes
import tech.mmarca.openvitals.wearlink.WearLinkProtocol.LogKind

/**
 * Writes what the wearer logged on the watch to Health Connect. The phone
 * keeps the cursor per watch: each page is asked for after it, written, and
 * the cursor moves past it, so a sync that dies mid-way resumes where it
 * stopped and a page written twice is refused as present.
 *
 * A kind whose write grant the user withheld is left out, and so is every
 * entry of it. The cursor stops just before the first such entry, so those
 * entries stay on the watch for the next sync to write once the grant is
 * given. Other kinds of the same page are written as usual. A kind the device
 * cannot record (mindfulness without its record type) is skipped and does not
 * hold the cursor, since no grant would change it.
 */
internal class WearOsLogEntrySync(
    private val importRepository: AppleHealthImportRepository,
    private val cursors: WearOsSyncCursorStore,
    private val granted: suspend () -> Set<String>,
    private val mindfulnessAvailable: () -> Boolean,
    private val zone: () -> ZoneId,
) {

    /** What the pull came to: [written] records and the write grants [withheld] that kept kinds out. */
    class Outcome(val written: Int, val withheld: Set<String>)

    /**
     * Pulls and writes the entries after the cursor. Null when [pullPage] has
     * no bonded watch to ask (nothing is written then). A failure other than a
     * missing grant throws and leaves the cursor at the last page written.
     */
    suspend fun sync(deviceId: String, pullPage: suspend (Instant) -> WearOsLogEntryPage?): Outcome? {
        var since = cursors.logCursor(deviceId)
        val allowed = granted()
        var written = 0
        val withheld = mutableSetOf<String>()
        var parkedAt: Instant? = null
        var pages = 0
        while (true) {
            val page = pullPage(since) ?: return null
            if (page.entries.isEmpty()) break
            val part = writePage(page.entries, allowed)
            written += part.written
            withheld += part.withheld
            parkedAt = listOfNotNull(parkedAt, part.parkedAt).minOrNull()
            val newest = page.entries.maxOf { it.time }
            // Exclusive: the entry at the parked time is pulled again, the one before it is not.
            cursors.setLogCursor(deviceId, parkedAt?.minusMillis(1) ?: newest)
            since = newest
            if (!page.hasMore) break
            if (++pages >= MAX_PAGES) break
        }
        return Outcome(written, withheld)
    }

    private class PageOutcome(val written: Int, val withheld: Set<String>, val parkedAt: Instant?)

    /** One page: each kind written as one batch, or kept out for want of its grant. */
    private suspend fun writePage(entries: List<WearOsLogEntry>, allowed: Set<String>): PageOutcome {
        var written = 0
        val withheld = mutableSetOf<String>()
        val blocked = mutableSetOf<LogKind>()
        for (kind in entries.map { it.kind }.distinct()) {
            val permission = WearOsLogEntryImport.writePermission(kind) ?: continue
            if (kind == LogKind.MINDFULNESS && !mindfulnessAvailable()) {
                WearOsLog.log("log: mindfulness not written, Health Connect has no mindfulness record here")
                continue
            }
            if (permission !in allowed) {
                withheld += permission
                blocked += kind
                continue
            }
            val ofKind = entries.filter { it.kind == kind }
            val records = ofKind.mapNotNull { WearOsLogEntryImport.record(it, zone()) }
            if (records.size < ofKind.size) WearOsLog.log("log: ${ofKind.size - records.size} $kind not written, Health Connect refused it")
            written += try {
                importRepository.insertWithOutcomes(records).count { it == RecordWrite.WRITTEN }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error !is SecurityException && !error.isPermissionFailure()) throw error
                withheld += permission
                blocked += kind
                0
            }
        }
        val parkedAt = entries.filter { it.kind in blocked }.minOfOrNull { it.time }
        if (withheld.isNotEmpty()) WearOsLog.log("log: ${withheld.size} write grant(s) missing, the entries they cover stay on the watch")
        return PageOutcome(written, withheld, parkedAt)
    }

    private companion object {
        /** A safety net against a watch that always says "more", as the other pulls have. */
        const val MAX_PAGES = 1000
    }
}
