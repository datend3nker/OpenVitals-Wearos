package tech.mmarca.openvitals.features.manualentry.activity

import androidx.health.connect.client.records.ExerciseSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import tech.mmarca.openvitals.domain.workout.WorkoutSport

/** The form takes its sports from the table the watch uses; what it shows for them did not change. */
class WorkoutSportEntryTypesTest {

    private data class Traits(
        val exerciseType: Int,
        val gps: Boolean,
        val distance: Boolean,
        val elevation: Boolean,
        val sensor: ActivityRecordingSensor,
    )

    private val before = mapOf(
        "56" to Traits(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, true, true, true, ActivityRecordingSensor.GPS),
        "8" to Traits(ExerciseSessionRecord.EXERCISE_TYPE_BIKING, true, true, true, ActivityRecordingSensor.GPS),
        "stationary_bike" to Traits(ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY, false, true, false, ActivityRecordingSensor.BLE),
        "79" to Traits(ExerciseSessionRecord.EXERCISE_TYPE_WALKING, true, true, true, ActivityRecordingSensor.GPS),
        "37" to Traits(ExerciseSessionRecord.EXERCISE_TYPE_HIKING, true, true, true, ActivityRecordingSensor.GPS),
        "70" to Traits(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, false, false, false, ActivityRecordingSensor.BLE),
        "treadmill" to Traits(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL, false, true, false, ActivityRecordingSensor.STEP_DETECTOR),
        "68" to Traits(ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING, false, true, true, ActivityRecordingSensor.BLE),
        "69" to Traits(ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE, false, true, false, ActivityRecordingSensor.BLE),
        "25" to Traits(ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL, false, true, false, ActivityRecordingSensor.BLE),
        "54" to Traits(ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE, false, true, false, ActivityRecordingSensor.BLE),
        "36" to Traits(ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING, false, false, false, ActivityRecordingSensor.BLE),
        "83" to Traits(ExerciseSessionRecord.EXERCISE_TYPE_YOGA, false, false, false, ActivityRecordingSensor.BLE),
        "48" to Traits(ExerciseSessionRecord.EXERCISE_TYPE_PILATES, false, false, false, ActivityRecordingSensor.BLE),
        "71" to Traits(ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING, false, false, false, ActivityRecordingSensor.BLE),
        "16" to Traits(ExerciseSessionRecord.EXERCISE_TYPE_DANCING, false, false, false, ActivityRecordingSensor.BLE),
        "0" to Traits(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT, false, false, false, ActivityRecordingSensor.NONE),
        "78" to Traits(ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL, false, false, false, ActivityRecordingSensor.BLE),
    )

    @Test fun `every sport of the table is an entry type with the traits it had`() {
        assertEquals(before.keys, WorkoutSport.entries.map { it.id }.toSet())
        for (sport in WorkoutSport.entries) {
            val entry = entryOf(activityEntryTypeById(sport.id), sport.id)
            val expected = before.getValue(sport.id)
            val actual = Traits(entry.exerciseType, entry.supportsGpsRoute, entry.supportsDistance, entry.supportsElevation, entry.recordingSensor)
            assertEquals(sport.id, expected, actual)
        }
    }

    private fun <T> entryOf(value: T?, message: String): T {
        assertNotNull(message, value)
        return value!!
    }
}
