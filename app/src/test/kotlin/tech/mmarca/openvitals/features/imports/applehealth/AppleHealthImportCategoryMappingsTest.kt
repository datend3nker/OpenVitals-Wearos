package tech.mmarca.openvitals.features.imports.applehealth

import androidx.health.connect.client.records.ExerciseSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class AppleHealthImportCategoryMappingsTest {

    @Test fun `a team or racket sport keeps its own type`() {
        assertEquals(
            ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL,
            "HKWorkoutActivityTypeVolleyball".toExerciseType(emptyMap()),
        )
        // Not tennis: an exact name, not a substring.
        assertEquals(
            ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS,
            "HKWorkoutActivityTypeTableTennis".toExerciseType(emptyMap()),
        )
    }

    @Test fun `a swim is pool or open water by its location`() {
        assertEquals(
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
            "HKWorkoutActivityTypeSwimming".toExerciseType(mapOf("HKSwimmingLocationType" to "1")),
        )
        assertEquals(
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
            "HKWorkoutActivityTypeSwimming".toExerciseType(mapOf("HKSwimmingLocationType" to "2")),
        )
        assertEquals(
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
            "HKWorkoutActivityTypeSwimming".toExerciseType(emptyMap()),
        )
    }

    @Test fun `a type Health Connect lacks is other workout`() {
        assertEquals(
            ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT,
            "HKWorkoutActivityTypeSwimBikeRun".toExerciseType(emptyMap()),
        )
    }
}
