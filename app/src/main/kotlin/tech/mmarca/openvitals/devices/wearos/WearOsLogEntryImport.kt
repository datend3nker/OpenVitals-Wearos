package tech.mmarca.openvitals.devices.wearos

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.BloodPressureRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.BodyTemperatureRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Volume
import androidx.health.connect.client.units.celsius
import androidx.health.connect.client.units.millimetersOfMercury
import androidx.health.connect.client.units.percent
import java.time.Duration
import java.time.ZoneId
import java.util.Locale
import kotlin.reflect.KClass
import tech.mmarca.openvitals.domain.model.BpRecordValues
import tech.mmarca.openvitals.domain.model.GlucoseRecordValues
import tech.mmarca.openvitals.domain.model.NutritionNutrient
import tech.mmarca.openvitals.healthconnect.nutritionRecordOf
import tech.mmarca.openvitals.wearlink.WearLinkProtocol.LogKind

/**
 * Maps what the wearer logged on the watch to Health Connect records, one
 * per entry. Pure, so it is testable without a device.
 *
 * Each record's `clientRecordId` is the kind, the entry's time and its
 * value, so pulling the same entry again writes the same record and Health
 * Connect refuses it as present. The entries carry no id of their own: two
 * entries of one kind, one time and one value are one record.
 *
 * Heart rate is not written: the watch's own samples already reach Health
 * Connect as heart rate, and a spot reading would be a second sample of the
 * same moment. Every other kind has a record here.
 */
object WearOsLogEntryImport {

    const val CLIENT_ID_PREFIX = "wearos_log_"

    /** Health Connect's write permission for [kind]; null for a kind the phone does not write. */
    fun writePermission(kind: LogKind): String? = when (kind) {
        LogKind.WATER -> write(HydrationRecord::class)
        LogKind.WEIGHT -> write(WeightRecord::class)
        LogKind.BLOOD_PRESSURE -> write(BloodPressureRecord::class)
        LogKind.MINDFULNESS -> write(MindfulnessSessionRecord::class)
        LogKind.HEART_RATE -> null
        LogKind.HRV -> write(HeartRateVariabilityRmssdRecord::class)
        LogKind.BODY_FAT -> write(BodyFatRecord::class)
        LogKind.BLOOD_OXYGEN -> write(OxygenSaturationRecord::class)
        LogKind.RESPIRATORY_RATE -> write(RespiratoryRateRecord::class)
        LogKind.BODY_TEMPERATURE -> write(BodyTemperatureRecord::class)
        LogKind.BLOOD_GLUCOSE -> write(BloodGlucoseRecord::class)
        LogKind.HEIGHT -> write(HeightRecord::class)
        LogKind.CAFFEINE -> write(NutritionRecord::class)
    }

    /** Deterministic: the kind, the time in epoch millis and the values as the watch sent them. */
    fun clientRecordId(entry: WearOsLogEntry): String {
        val second = entry.value2?.let { "_$it" }.orEmpty()
        return "$CLIENT_ID_PREFIX${entry.kind.name.lowercase(Locale.ROOT)}_${entry.time.toEpochMilli()}_${entry.value}$second"
    }

    /**
     * The record of [entry], or null when the phone keeps none of this kind
     * ([LogKind.HEART_RATE]), or the entry cannot be a record: a blood
     * pressure without its diastolic, a breathing session of no minutes, or
     * a value Health Connect refuses on its own checks. A null is dropped,
     * never retried: the same entry would be refused again.
     */
    fun record(entry: WearOsLogEntry, zone: ZoneId): Record? = try {
        build(entry, zone)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun build(entry: WearOsLogEntry, zone: ZoneId): Record? {
        val at = entry.time
        val offset = zone.rules.getOffset(at)
        val value = entry.value
        val metadata = Metadata.manualEntry(
            device = Device(type = Device.TYPE_WATCH),
            clientRecordId = clientRecordId(entry),
            clientRecordVersion = 1L,
        )
        return when (entry.kind) {
            LogKind.WATER -> {
                // Health Connect wants an interval; a drink is a second long, as the phone's own drinks are.
                val end = at.plusSeconds(1)
                HydrationRecord(
                    startTime = at,
                    startZoneOffset = offset,
                    endTime = end,
                    endZoneOffset = zone.rules.getOffset(end),
                    volume = Volume.milliliters(value),
                    metadata = metadata,
                )
            }
            LogKind.WEIGHT -> WeightRecord(at, offset, Mass.kilograms(value), metadata)
            LogKind.BLOOD_PRESSURE -> BloodPressureRecord(
                time = at,
                zoneOffset = offset,
                metadata = metadata,
                systolic = value.millimetersOfMercury,
                diastolic = (entry.value2 ?: return null).millimetersOfMercury,
                bodyPosition = BpRecordValues.BODY_POSITION_UNKNOWN,
                measurementLocation = BpRecordValues.MEASUREMENT_LOCATION_UNKNOWN,
            )
            LogKind.MINDFULNESS -> {
                // The watch logs a session when it ends, with its length in minutes.
                val length = minutesDuration(value) ?: return null
                val start = at.minus(length)
                MindfulnessSessionRecord(
                    startTime = start,
                    startZoneOffset = zone.rules.getOffset(start),
                    endTime = at,
                    endZoneOffset = offset,
                    metadata = metadata,
                    mindfulnessSessionType = MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_BREATHING,
                )
            }
            LogKind.HEART_RATE -> null
            LogKind.HRV -> HeartRateVariabilityRmssdRecord(
                time = at,
                zoneOffset = offset,
                heartRateVariabilityMillis = value,
                metadata = metadata,
            )
            LogKind.BODY_FAT -> BodyFatRecord(at, offset, value.percent, metadata)
            LogKind.BLOOD_OXYGEN -> OxygenSaturationRecord(at, offset, value.percent, metadata)
            LogKind.RESPIRATORY_RATE -> RespiratoryRateRecord(at, offset, value, metadata)
            LogKind.BODY_TEMPERATURE -> BodyTemperatureRecord(
                time = at,
                zoneOffset = offset,
                metadata = metadata,
                temperature = value.celsius,
            )
            LogKind.BLOOD_GLUCOSE -> BloodGlucoseRecord(
                time = at,
                zoneOffset = offset,
                metadata = metadata,
                level = BloodGlucose.millimolesPerLiter(value),
                specimenSource = GlucoseRecordValues.SPECIMEN_SOURCE_UNKNOWN,
                mealType = GlucoseRecordValues.MEAL_TYPE_UNKNOWN,
                relationToMeal = GlucoseRecordValues.RELATION_TO_MEAL_UNKNOWN,
            )
            LogKind.CAFFEINE -> {
                // Milligrams on the watch, grams in the phone's nutrition mapping. A drink is a second long, as water is.
                val end = at.plusSeconds(1)
                nutritionRecordOf(
                    startTime = at,
                    startZoneOffset = offset,
                    endTime = end,
                    endZoneOffset = zone.rules.getOffset(end),
                    metadata = metadata,
                    nutrientValues = mapOf(NutritionNutrient.CAFFEINE to value / MILLIGRAMS_PER_GRAM),
                    name = null,
                )
            }
            // Centimetres on the watch, metres in Health Connect.
            LogKind.HEIGHT -> HeightRecord(at, offset, Length.meters(value / CENTIMETRES_PER_METRE), metadata)
        }
    }

    /** Whole milliseconds of [minutes]; null for none or for longer than a day. */
    private fun minutesDuration(minutes: Double): Duration? {
        if (minutes <= 0.0 || minutes > MAX_MINDFULNESS_MINUTES) return null
        return Duration.ofMillis(Math.round(minutes * MILLIS_PER_MINUTE))
    }

    private fun write(type: KClass<out Record>): String = HealthPermission.getWritePermission(type)

    private const val CENTIMETRES_PER_METRE = 100.0
    private const val MILLIGRAMS_PER_GRAM = 1_000.0
    private const val MILLIS_PER_MINUTE = 60_000.0
    private const val MAX_MINDFULNESS_MINUTES = 1_440.0
}
