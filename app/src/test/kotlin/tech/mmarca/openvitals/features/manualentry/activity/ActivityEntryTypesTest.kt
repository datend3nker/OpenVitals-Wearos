package tech.mmarca.openvitals.features.manualentry.activity

import androidx.health.connect.client.records.ExerciseSessionRecord
import java.lang.reflect.Modifier
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openvitals.domain.model.ExerciseData

/** The form writes every session type Health Connect defines, so a session from another app can be made here too. */
class ActivityEntryTypesTest {

    private val healthConnectExerciseTypes: Map<String, Int> = ExerciseSessionRecord::class.java.fields
        .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType }
        .filter { it.name.startsWith("EXERCISE_TYPE_") }
        .associate { it.name to it.getInt(null) }

    @Test fun `every Health Connect session type has an entry type`() {
        // Health Connect 1.1 defines 61 (60 sports and "other workout"); the reflection must see them.
        assertTrue(healthConnectExerciseTypes.size.toString(), healthConnectExerciseTypes.size >= 61)
        val listed = DefaultActivityEntryTypes.map { it.exerciseType }.toSet()

        val missing = healthConnectExerciseTypes.filterValues { it !in listed }.keys

        assertEquals("not in DefaultActivityEntryTypes", emptySet<String>(), missing)
    }

    @Test fun `entry ids are unique`() {
        val ids = DefaultActivityEntryTypes.map { it.id }

        assertEquals(ids.distinct(), ids)
    }

    @Test fun `a type without a route is a timed session, not one the form cannot record`() {
        // "Other workout" is the one deliberate exception: the generic bucket is manual-only.
        val unrecordable = DefaultActivityEntryTypes
            .filter { !it.supportsLiveRecording && !it.isRepetitionLike }
            .map { it.exerciseType }

        assertEquals(listOf(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT), unrecordable)
    }

    @Test fun `a volleyball session reopens as beach volleyball only when it carries the variant`() {
        fun volleyball(title: String?, clientRecordId: String?) = ExerciseData(
            id = "v",
            title = title,
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL,
            startTime = Instant.EPOCH,
            endTime = Instant.EPOCH.plusSeconds(3_600),
            durationMs = 3_600_000,
            source = "test",
            clientRecordId = clientRecordId,
        ).inferStoredActivityType().id

        val plain = ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL.toString()
        assertEquals("beach_volleyball", volleyball(null, "openvitals_activity_1_k_variant_beach_volleyball"))
        assertEquals("beach_volleyball", volleyball("Saturday", "openvitals_activity_1_k_variant_beach_volleyball"))
        // A title says nothing: "Long Beach VC" is a club.
        assertEquals(plain, volleyball("Long Beach VC", "openvitals_activity_1_k"))
        assertEquals(plain, volleyball(null, null))
    }

    @Test fun `volleyball estimates calories by Compendium MET, sand above court`() {
        fun active(typeId: String): Double =
            activityCalorieEstimate(activityEntryTypeById(typeId)!!, null, "60", weightKg = 70.0)!!
                .activeCaloriesText.toDouble()
        val court = active(ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL.toString())
        val sand = active(tech.mmarca.openvitals.domain.model.BeachVolleyballVariantId)
        assertEquals((4.0 - 1.0) * 70.0, court, 1.0)
        assertEquals((8.0 - 1.0) * 70.0, sand, 1.0)
    }

    @Test fun `no weight, no estimate, and the type that could be estimated is marked`() {
        val volleyball = activityEntryTypeById(ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL.toString())!!
        assertEquals(null, activityCalorieEstimate(volleyball, null, "60", weightKg = null))
        assertTrue(volleyball.estimatesCalories)
        assertTrue(!activityEntryTypeById(ExerciseSessionRecord.EXERCISE_TYPE_YOGA.toString())!!.estimatesCalories)
    }
}
