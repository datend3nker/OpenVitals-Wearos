package tech.mmarca.openvitals.devices.wearos

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openvitals.devices.FakeSharedPreferences
import tech.mmarca.openvitals.wearlink.WearLinkToken

class WearOsLinkStoreTest {

    private val prefs = FakeSharedPreferences()
    private val store = WearOsLinkStore(prefs)

    @Test
    fun `one token per added watch, kept across a new pairing and restarts, new once the watch is removed`() {
        val token = store.tokenFor("7f:12:34:56:78:9a", "A8:D1:62:BE:3A:3B")

        assertTrue(WearLinkToken.isWellFormed(token))
        // Paired again, the bond is listed under another address.
        assertEquals(token, store.tokenFor("7F:12:34:56:78:9A", "4A:F1:89:49:3C:85"))
        assertEquals(token, WearOsLinkStore(prefs).tokenFor("7F:12:34:56:78:9A", "A8:D1:62:BE:3A:3B"))
        assertNotEquals(token, store.tokenFor("11:22:33:44:55:66", "A8:D1:62:BE:3A:3B"))

        store.clear("watch-1", "7F:12:34:56:78:9A")

        assertNotEquals(token, store.tokenFor("7F:12:34:56:78:9A", "A8:D1:62:BE:3A:3B"))
    }

    @Test
    fun `a pushed heart rate interval is kept per watch and period, and the default stands until set`() {
        val key = "hr_night"
        assertEquals("10", store.watchValue("7F:12:34:56:78:9A", key, default = "10"))

        store.setWatchValue("7f:12:34:56:78:9a", key, "60")

        assertEquals("60", store.watchValue("7F:12:34:56:78:9A", key, default = "10"))
        assertEquals("60", WearOsLinkStore(prefs).watchValue("7F:12:34:56:78:9A", key, default = "10"))
        assertEquals("10", store.watchValue("7F:12:34:56:78:9A", "hr_day", default = "10"))
        assertEquals("10", store.watchValue("11:22:33:44:55:66", key, default = "10"))

        store.clear("watch-1", "7F:12:34:56:78:9A")

        assertEquals("10", store.watchValue("7F:12:34:56:78:9A", key, default = "10"))
    }

    @Test
    fun `a pushed watch setting is kept per watch and the default stands until set, cleared when the watch is removed`() {
        val key = "sleep"
        // Never set: the default stands.
        assertTrue(store.watchSetting("7F:12:34:56:78:9A", key, default = true))

        store.setWatchSetting("7f:12:34:56:78:9a", key, enabled = false)

        // Stored under the registered address, case-insensitive, and survives a restart.
        assertFalse(store.watchSetting("7F:12:34:56:78:9A", key, default = true))
        assertFalse(WearOsLinkStore(prefs).watchSetting("7F:12:34:56:78:9A", key, default = true))
        // Another watch keeps its own default.
        assertTrue(store.watchSetting("11:22:33:44:55:66", key, default = true))

        store.clear("watch-1", "7F:12:34:56:78:9A")

        assertTrue(store.watchSetting("7F:12:34:56:78:9A", key, default = true))
    }

    @Test
    fun `a token kept per bond address is adopted once by the watch reached through it`() {
        val allowed = WearLinkToken.generate()
        prefs.edit().putString("token_4A:F1:89:49:3C:85", allowed).apply()

        assertEquals(allowed, store.tokenFor("4A:F1:89:49:3C:85", "4a:f1:89:49:3c:85"))
        assertEquals(allowed, store.tokenFor("4A:F1:89:49:3C:85", "A8:D1:62:BE:3A:3B"))
        assertFalse(prefs.contains("token_4A:F1:89:49:3C:85"))
    }

    @Test
    fun `the link address is kept per registered address`() {
        assertNull(store.linkAddress("7F:12:34:56:78:9A"))

        store.setLinkAddress("7F:12:34:56:78:9A", "a8:d1:62:be:3a:3b")

        assertEquals("A8:D1:62:BE:3A:3B", store.linkAddress("7f:12:34:56:78:9a"))
        store.clear("watch-1", "7F:12:34:56:78:9A")
        assertNull(store.linkAddress("7F:12:34:56:78:9A"))
    }

    @Test
    fun `snapshots are per device and the latest wins`() {
        val t = Instant.parse("2026-10-09T07:00:00Z")
        store.record("watch-1", WearOsLinkSnapshot(WearOsAppStatus.NO_ANSWER, checkedAt = t))
        store.record("watch-1", WearOsLinkSnapshot(WearOsAppStatus.APP_RUNNING, watchName = "Galaxy Watch8", checkedAt = t.plusSeconds(60)))

        assertEquals("Galaxy Watch8", store.snapshots.value.getValue("watch-1").watchName)
        store.clear("watch-1", null)
        assertTrue(store.snapshots.value.isEmpty())
    }
}
