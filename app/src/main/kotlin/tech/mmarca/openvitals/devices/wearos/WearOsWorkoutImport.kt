package tech.mmarca.openvitals.devices.wearos

import java.time.Instant
import tech.mmarca.openvitals.domain.model.ActivityPauseInterval
import tech.mmarca.openvitals.domain.model.ActivityRecordSource
import tech.mmarca.openvitals.domain.model.ActivityWriteLimits
import tech.mmarca.openvitals.domain.model.ActivityWriteRequest
import tech.mmarca.openvitals.domain.model.ExerciseRoutePoint

/**
 * Maps a workout the watch recorded to the phone's own activity write request,
 * so it is stored as the phone's recording is: a session with its route, pauses
 * and distance.
 *
 * The id is a function of the workout's start ([ActivityWriteRequest.importKey]),
 * so a pull that repeats rewrites the session instead of adding a second one.
 *
 * Steps, climbed height and heart rate are not in the request: the watch's
 * all-day series already carry them minute by minute, and a second copy over
 * the same minutes would count twice. Calories are left to the phone's own
 * estimates.
 *
 * Pure, so it is testable without a device.
 */
object WearOsWorkoutImport {

    const val KEY_PREFIX = "wearos_workout_"

    fun request(workout: WearOsWorkout): ActivityWriteRequest {
        // Every point must lie inside the session, or Health Connect refuses the route.
        val route = workout.route
            .filter { !it.time.isBefore(workout.start) && it.time.isBefore(workout.end) }
            .map {
                ExerciseRoutePoint(
                    time = it.time,
                    latitude = it.latitude,
                    longitude = it.longitude,
                    altitudeMeters = it.altitudeMetres,
                    horizontalAccuracyMeters = it.accuracyMetres,
                    verticalAccuracyMeters = null,
                )
            }
        val distance = workout.distanceMetres?.toDouble()
            ?.takeIf { it > 0.0 && it <= ActivityWriteLimits.MaxDistanceMeters }
        return ActivityWriteRequest(
            exerciseType = workout.exerciseType,
            startTime = workout.start,
            endTime = workout.end,
            routePoints = route,
            pauseIntervals = workout.pauses
                .filter { it.start >= workout.start && it.endInclusive <= workout.end }
                .map { ActivityPauseInterval(it.start, it.endInclusive) },
            distanceMeters = distance,
            source = ActivityRecordSource.WATCH,
            importKey = importKey(workout.start),
        )
    }

    fun importKey(start: Instant): String = "$KEY_PREFIX${start.toEpochMilli()}"
}
