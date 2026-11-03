package tech.mmarca.openvitals.devices.wearos

import java.time.Instant
import kotlinx.coroutines.CancellationException
import tech.mmarca.openvitals.core.presentation.isPermissionFailure
import tech.mmarca.openvitals.domain.model.ActivityWriteRequest

/**
 * Writes the watch's finished workouts as activity sessions, a page at a time
 * from the phone's cursor ([WearOsSyncCursorStore.workoutCursor]). The cursor
 * moves after each workout is written, so a sync that dies mid-way resumes
 * there, and a workout written twice upserts.
 *
 * Without the write grants nothing is written and the cursor stays: the workouts
 * wait on the watch (it keeps a month). That never fails the sync.
 */
internal class WearOsWorkoutSync(
    private val cursors: WearOsSyncCursorStore,
    /** The Health Connect grants [request] needs and the user has not given. */
    private val missingPermissions: suspend (ActivityWriteRequest) -> Set<String>,
    private val write: suspend (ActivityWriteRequest) -> Unit,
) {

    /** [written] sessions, and the grants that [withheld] some workouts. */
    class Outcome(val written: Int, val withheld: Set<String>)

    /** Null when [pullPage] has no bonded watch to ask. A failure other than a missing grant throws. */
    suspend fun sync(deviceId: String, pullPage: suspend (Instant) -> WearOsWorkoutPage?): Outcome? {
        var since = cursors.workoutCursor(deviceId)
        var written = 0
        var pages = 0
        while (true) {
            val page = pullPage(since) ?: return null
            if (page.workouts.isEmpty()) break
            for (workout in page.workouts) {
                val request = WearOsWorkoutImport.request(workout)
                val missing = missingPermissions(request)
                if (missing.isNotEmpty()) return Outcome(written, missing)
                try {
                    write(request)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (error !is SecurityException && !error.isPermissionFailure()) throw error
                    return Outcome(written, missingPermissions(request))
                }
                written++
                since = workout.start
                cursors.setWorkoutCursor(deviceId, since)
            }
            if (!page.hasMore) break
            if (++pages >= MAX_PAGES) break
        }
        return Outcome(written, emptySet())
    }

    private companion object {
        /** A safety net against a watch that always says "more", as the other pulls have. */
        const val MAX_PAGES = 200
    }
}
