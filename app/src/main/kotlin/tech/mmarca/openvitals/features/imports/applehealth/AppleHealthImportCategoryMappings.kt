package tech.mmarca.openvitals.features.imports.applehealth

import androidx.health.connect.client.records.CervicalMucusRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.MenstruationFlowRecord
import androidx.health.connect.client.records.OvulationTestRecord
import androidx.health.connect.client.records.SexualActivityRecord
import androidx.health.connect.client.records.SleepSessionRecord
import java.util.Locale

internal fun String?.toSleepStageType(): Int? =
    when (this) {
        AppleSleepInBed -> SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED
        AppleSleepAsleep,
        AppleSleepAsleepUnspecified,
        -> SleepSessionRecord.STAGE_TYPE_SLEEPING
        AppleSleepAsleepCore -> SleepSessionRecord.STAGE_TYPE_LIGHT
        AppleSleepAsleepDeep -> SleepSessionRecord.STAGE_TYPE_DEEP
        AppleSleepAsleepRem -> SleepSessionRecord.STAGE_TYPE_REM
        AppleSleepAwake -> SleepSessionRecord.STAGE_TYPE_AWAKE
        else -> null
    }

/**
 * Apple's workout type to Health Connect's, by exact name. A type with no Health
 * Connect counterpart is "other workout"; the session title keeps Apple's name.
 * A swim is pool or open water by its location metadata.
 */
internal fun String.toExerciseType(metadata: Map<String, String>): Int {
    val type = removePrefix("HKWorkoutActivityType").lowercase(Locale.US)
    if (type == "swimming") return metadata.toSwimmingExerciseType()
    return AppleWorkoutExerciseTypes[type] ?: ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
}

/** `HKWorkoutSwimmingLocationType`: 1 pool, 2 open water. Unknown stays open water, as before. */
private fun Map<String, String>.toSwimmingExerciseType(): Int {
    val location = this["HKSwimmingLocationType"] ?: this["HKMetadataKeySwimmingLocationType"]
    return when (location?.trim()?.lowercase(Locale.US)) {
        "1", "pool" -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
        else -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER
    }
}

// Keys are HKWorkoutActivityType case names, lowercased.
private val AppleWorkoutExerciseTypes: Map<String, Int> = mapOf(
    "americanfootball" to ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN,
    "australianfootball" to ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN,
    "badminton" to ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON,
    "barre" to ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS,
    "baseball" to ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL,
    "basketball" to ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL,
    "boxing" to ExerciseSessionRecord.EXERCISE_TYPE_BOXING,
    "cardiodance" to ExerciseSessionRecord.EXERCISE_TYPE_DANCING,
    "climbing" to ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING,
    "cooldown" to ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING,
    "coretraining" to ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
    "cricket" to ExerciseSessionRecord.EXERCISE_TYPE_CRICKET,
    "crosscountryskiing" to ExerciseSessionRecord.EXERCISE_TYPE_SKIING,
    "cycling" to ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
    "dance" to ExerciseSessionRecord.EXERCISE_TYPE_DANCING,
    "danceinspiredtraining" to ExerciseSessionRecord.EXERCISE_TYPE_DANCING,
    "discsports" to ExerciseSessionRecord.EXERCISE_TYPE_FRISBEE_DISC,
    "downhillskiing" to ExerciseSessionRecord.EXERCISE_TYPE_SKIING,
    "elliptical" to ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL,
    "fencing" to ExerciseSessionRecord.EXERCISE_TYPE_FENCING,
    "flexibility" to ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING,
    "functionalstrengthtraining" to ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
    "golf" to ExerciseSessionRecord.EXERCISE_TYPE_GOLF,
    "gymnastics" to ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS,
    "handcycling" to ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
    "handball" to ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL,
    "highintensityintervaltraining" to ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
    "hiking" to ExerciseSessionRecord.EXERCISE_TYPE_HIKING,
    "hockey" to ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY,
    "jumprope" to ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
    "kickboxing" to ExerciseSessionRecord.EXERCISE_TYPE_BOXING,
    "martialarts" to ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS,
    "paddlesports" to ExerciseSessionRecord.EXERCISE_TYPE_PADDLING,
    "pilates" to ExerciseSessionRecord.EXERCISE_TYPE_PILATES,
    "racquetball" to ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL,
    "rowing" to ExerciseSessionRecord.EXERCISE_TYPE_ROWING,
    "rugby" to ExerciseSessionRecord.EXERCISE_TYPE_RUGBY,
    "running" to ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
    "sailing" to ExerciseSessionRecord.EXERCISE_TYPE_SAILING,
    "skatingsports" to ExerciseSessionRecord.EXERCISE_TYPE_SKATING,
    "snowboarding" to ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING,
    "soccer" to ExerciseSessionRecord.EXERCISE_TYPE_SOCCER,
    "socialdance" to ExerciseSessionRecord.EXERCISE_TYPE_DANCING,
    "softball" to ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL,
    "squash" to ExerciseSessionRecord.EXERCISE_TYPE_SQUASH,
    "stairclimbing" to ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING,
    "stairs" to ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING,
    "steptraining" to ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS,
    "surfingsports" to ExerciseSessionRecord.EXERCISE_TYPE_SURFING,
    "tabletennis" to ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS,
    "tennis" to ExerciseSessionRecord.EXERCISE_TYPE_TENNIS,
    "traditionalstrengthtraining" to ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
    "underwaterdiving" to ExerciseSessionRecord.EXERCISE_TYPE_SCUBA_DIVING,
    "volleyball" to ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL,
    "walking" to ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
    "waterpolo" to ExerciseSessionRecord.EXERCISE_TYPE_WATER_POLO,
    "wheelchairrunpace" to ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR,
    "wheelchairwalkpace" to ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR,
    "wrestling" to ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS,
    "yoga" to ExerciseSessionRecord.EXERCISE_TYPE_YOGA,
)

internal fun String?.toMenstrualFlow(): Int =
    when (this) {
        "HKCategoryValueMenstrualFlowLight" -> MenstruationFlowRecord.FLOW_LIGHT
        "HKCategoryValueMenstrualFlowMedium" -> MenstruationFlowRecord.FLOW_MEDIUM
        "HKCategoryValueMenstrualFlowHeavy" -> MenstruationFlowRecord.FLOW_HEAVY
        else -> MenstruationFlowRecord.FLOW_UNKNOWN
    }

internal fun String?.toOvulationResult(): Int =
    when (this) {
        "HKCategoryValueOvulationTestResultPositive" -> OvulationTestRecord.RESULT_POSITIVE
        "HKCategoryValueOvulationTestResultNegative" -> OvulationTestRecord.RESULT_NEGATIVE
        "HKCategoryValueOvulationTestResultLuteinizingHormoneSurge" -> OvulationTestRecord.RESULT_HIGH
        else -> OvulationTestRecord.RESULT_INCONCLUSIVE
    }

internal fun String?.toCervicalMucusAppearance(): Int =
    when (this) {
        "HKCategoryValueCervicalMucusQualityDry" -> CervicalMucusRecord.APPEARANCE_DRY
        "HKCategoryValueCervicalMucusQualitySticky" -> CervicalMucusRecord.APPEARANCE_STICKY
        "HKCategoryValueCervicalMucusQualityCreamy" -> CervicalMucusRecord.APPEARANCE_CREAMY
        "HKCategoryValueCervicalMucusQualityWatery" -> CervicalMucusRecord.APPEARANCE_WATERY
        "HKCategoryValueCervicalMucusQualityEggWhite" -> CervicalMucusRecord.APPEARANCE_EGG_WHITE
        else -> CervicalMucusRecord.APPEARANCE_UNKNOWN
    }

internal fun Map<String, String>.toProtectionUsed(): Int {
    val value = this["HKSexualActivityProtectionUsed"] ?: this["HKMetadataKeySexualActivityProtectionUsed"]
    return when (value?.lowercase(Locale.US)) {
        "true", "1", "yes" -> SexualActivityRecord.PROTECTION_USED_PROTECTED
        "false", "0", "no" -> SexualActivityRecord.PROTECTION_USED_UNPROTECTED
        else -> SexualActivityRecord.PROTECTION_USED_UNKNOWN
    }
}
