package tech.mmarca.openvitals.devices.wearos

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openvitals.devices.FakeSharedPreferences
import tech.mmarca.openvitals.domain.model.ActivityWriteRequest

class WearOsWorkoutSyncTest {

    private val cursors = WearOsSyncCursorStore(FakeSharedPreferences())
    private val written = mutableListOf<ActivityWriteRequest>()
    private var missing: Set<String> = emptySet()
    private var denyOnWrite = false
    private val sync = WearOsWorkoutSync(
        cursors,
        missingPermissions = { missing },
        write = {
            if (denyOnWrite) throw SecurityException("no grant")
            written += it
        },
    )

    private fun workout(minutes: Long) = WearOsWorkout(
        start = Instant.ofEpochSecond(1_700_000_000L + minutes * 3600),
        end = Instant.ofEpochSecond(1_700_000_000L + minutes * 3600 + 1500),
        exerciseType = 56, steps = null, distanceMetres = 1000, ascentMetres = null, pauses = emptyList(), route = emptyList(),
    )

    @Test fun `each page is written and the cursor follows the newest workout`() = runBlocking {
        val pages = ArrayDeque(listOf(WearOsWorkoutPage(listOf(workout(0), workout(1)), true), WearOsWorkoutPage(listOf(workout(2)), false)))
        val seen = mutableListOf<Instant>()

        val outcome = sync.sync("w") { since -> seen += since; pages.removeFirst() }!!

        assertEquals(3, outcome.written)
        assertTrue(outcome.withheld.isEmpty())
        assertEquals(workout(2).start, cursors.workoutCursor("w"))
        assertEquals(listOf(Instant.EPOCH, workout(1).start), seen)
    }

    @Test fun `a missing grant leaves the workout and the cursor on the watch`() = runBlocking {
        missing = setOf("write exercise")

        val outcome = sync.sync("w") { WearOsWorkoutPage(listOf(workout(0)), false) }!!

        assertEquals(0, outcome.written)
        assertEquals(missing, outcome.withheld)
        assertEquals(Instant.EPOCH, cursors.workoutCursor("w"))
        assertTrue(written.isEmpty())
    }

    @Test fun `a grant refused during the write is withheld, not a failure`() = runBlocking {
        denyOnWrite = true
        missing = emptySet()

        val outcome = sync.sync("w") { WearOsWorkoutPage(listOf(workout(0)), false) }!!

        assertEquals(0, outcome.written)
        assertEquals(Instant.EPOCH, cursors.workoutCursor("w"))
    }

    @Test fun `no bonded watch gives none`() = runBlocking {
        assertNull(sync.sync("w") { null })
    }
}
