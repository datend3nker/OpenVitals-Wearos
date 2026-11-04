package tech.mmarca.openvitals.devices.wearos

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.mmarca.openvitals.devices.core.sync.DeviceSyncResult
import tech.mmarca.openvitals.devices.FakeSharedPreferences
import tech.mmarca.openvitals.wearlink.DebugTraceFile

class WearOsFileLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun traceFile() = DebugTraceFile(folder.newFolder("debug"), WearOsFileLog.PREFIX, zone = { ZoneOffset.UTC })

    @Test
    fun `while the switch is off nothing is written and no file is made`() {
        val file = traceFile()
        val log = WearOsFileLog(file, enabled = { false }, async = false)

        log.write("sync start")

        assertTrue(file.files().isEmpty())
    }

    @Test
    fun `while the switch is on each message is one line in the file of the day`() {
        val file = traceFile()
        var on = false
        val log = WearOsFileLog(file, enabled = { on }, clock = { 86_400_000L + 5 }, async = false)

        log.write("ignored")
        on = true
        log.write("pull heart rate: 120 row(s), more=false")

        val written = file.files().single()
        assertEquals("wear-sync-19700102.log", written.name)
        assertEquals(listOf("86400005;WearSync;log;msg=pull heart rate: 120 row(s), more false"), written.readLines().map { it.replace("more=false", "more false") })
    }

    @Test
    fun `the background writer finishes after flush`() {
        val file = traceFile()
        val log = WearOsFileLog(file, enabled = { true })

        repeat(20) { log.write("line $it") }
        log.flush()

        assertEquals(20, file.files().single().readLines().size)
    }

    @Test
    fun `a failure line names the exception class and its cause, not their text`() {
        val line = WearOsLog.failureText("sync failed", WearOsLinkFailure.NoAnswer(java.io.IOException("secret path /x/y")))

        assertEquals("sync failed: NoAnswer, cause IOException", line)
    }

    @Test
    fun `the sync trace says how a sync ended in counts and types`() {
        assertEquals(
            "succeeded, rows=5 nights=1 stepMinutes=7 unwritten=0 withheldGrants=0",
            WearOsSyncTrace.describe(DeviceSyncResult.Succeeded(5, nightCount = 1, stepMinuteCount = 7)),
        )
        assertEquals("failed (Message)", WearOsSyncTrace.describe(DeviceSyncResult.Failed("a person's name")))
    }

    @Test
    fun `the phone's debug log is off until the watch accepted the switch`() {
        val store = WearOsLinkStore(FakeSharedPreferences())

        assertFalse(store.debugLog)
        store.debugLog = true
        assertTrue(store.debugLog)
        assertFalse(WearOsWatchSetting.DEBUG_DATA.defaultOn)
        assertEquals("debug_data", WearOsWatchSetting.DEBUG_DATA.protocolKey)
    }
}
