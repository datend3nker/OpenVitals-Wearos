package tech.mmarca.openvitals.devices.wearos

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openvitals.domain.model.ActivityRecordSource
import tech.mmarca.openvitals.domain.model.importSessionClientRecordId

class WearOsWorkoutImportTest {

    private val t0 = Instant.ofEpochMilli(1_700_000_040_000L)

    private fun point(seconds: Long) = WearOsWorkoutPoint(t0.plusSeconds(seconds), 48.0, 11.0, 500.0, 4.0)

    private fun workout(start: Instant = t0, distance: Int? = 3210) = WearOsWorkout(
        start = start,
        end = start.plusSeconds(1500),
        exerciseType = 56,
        steps = 4000,
        distanceMetres = distance,
        ascentMetres = 42.0,
        pauses = listOf(start.plusSeconds(600)..start.plusSeconds(660)),
        route = listOf(point(0), point(5), point(1499), point(1500), point(2000)),
    )

    @Test fun `a workout becomes a watch session with route, pause and distance`() {
        val request = WearOsWorkoutImport.request(workout())

        assertEquals(56, request.exerciseType)
        assertEquals(ActivityRecordSource.WATCH, request.source)
        assertEquals(3210.0, request.distanceMeters!!, 0.0)
        assertEquals(1, request.pauseIntervals.size)
        assertEquals(3, request.routePoints.size)
    }

    @Test fun `steps and climb are left to the watch's all-day series`() {
        val request = WearOsWorkoutImport.request(workout())

        assertNull(request.stepsCount)
        assertNull(request.elevationGainedMeters)
        assertNull(request.floorsClimbed)
    }

    @Test fun `the session id is a function of the start, so a second pull rewrites it`() {
        val a = WearOsWorkoutImport.request(workout()).importSessionClientRecordId()
        val b = WearOsWorkoutImport.request(workout()).importSessionClientRecordId()

        assertEquals(a, b)
        assertTrue(a!!.contains("wearos_workout_1700000040000"))
        assertTrue(a != WearOsWorkoutImport.request(workout(t0.plusSeconds(3600))).importSessionClientRecordId())
    }

    @Test fun `no distance is written for a sport without one`() {
        assertNull(WearOsWorkoutImport.request(workout(distance = null)).distanceMeters)
        assertNull(WearOsWorkoutImport.request(workout(distance = 0)).distanceMeters)
    }
}
