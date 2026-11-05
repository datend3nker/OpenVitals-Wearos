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
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.WeightRecord
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openvitals.wearlink.WearLinkProtocol.LogKind

class WearOsLogEntryImportTest {

    private val at: Instant = Instant.parse("2026-10-07T10:00:00Z")
    private val zone: ZoneId = ZoneId.of("Europe/Berlin")

    private fun entry(kind: LogKind, value: Double, value2: Double? = null, time: Instant = at) =
        WearOsLogEntry(time, kind, value, value2)

    @Test
    fun `each kind the phone writes becomes its own record type`() {
        assertTrue(WearOsLogEntryImport.record(entry(LogKind.WATER, 250.0), zone) is HydrationRecord)
        assertTrue(WearOsLogEntryImport.record(entry(LogKind.WEIGHT, 72.4), zone) is WeightRecord)
        assertTrue(WearOsLogEntryImport.record(entry(LogKind.BLOOD_PRESSURE, 121.0, 79.0), zone) is BloodPressureRecord)
        assertTrue(WearOsLogEntryImport.record(entry(LogKind.MINDFULNESS, 1.0), zone) is MindfulnessSessionRecord)
        assertTrue(WearOsLogEntryImport.record(entry(LogKind.HRV, 42.0), zone) is HeartRateVariabilityRmssdRecord)
        assertTrue(WearOsLogEntryImport.record(entry(LogKind.BODY_FAT, 18.5), zone) is BodyFatRecord)
        assertTrue(WearOsLogEntryImport.record(entry(LogKind.BLOOD_OXYGEN, 97.0), zone) is OxygenSaturationRecord)
        assertTrue(WearOsLogEntryImport.record(entry(LogKind.RESPIRATORY_RATE, 14.0), zone) is RespiratoryRateRecord)
        assertTrue(WearOsLogEntryImport.record(entry(LogKind.BODY_TEMPERATURE, 36.5), zone) is BodyTemperatureRecord)
        assertTrue(WearOsLogEntryImport.record(entry(LogKind.BLOOD_GLUCOSE, 5.5), zone) is BloodGlucoseRecord)
        assertTrue(WearOsLogEntryImport.record(entry(LogKind.HEIGHT, 178.0), zone) is HeightRecord)
    }

    @Test
    fun `heart rate is not written, a spot reading being a second sample of the watch's own`() {
        assertNull(WearOsLogEntryImport.record(entry(LogKind.HEART_RATE, 64.0), zone))
        assertNull(WearOsLogEntryImport.writePermission(LogKind.HEART_RATE))
    }

    @Test
    fun `values keep their metric units`() {
        val water = WearOsLogEntryImport.record(entry(LogKind.WATER, 250.0), zone) as HydrationRecord
        assertEquals(250.0, water.volume.inMilliliters, 1e-9)
        val weight = WearOsLogEntryImport.record(entry(LogKind.WEIGHT, 72.4), zone) as WeightRecord
        assertEquals(72.4, weight.weight.inKilograms, 1e-9)
        val height = WearOsLogEntryImport.record(entry(LogKind.HEIGHT, 178.0), zone) as HeightRecord
        assertEquals(1.78, height.height.inMeters, 1e-9)
        val glucose = WearOsLogEntryImport.record(entry(LogKind.BLOOD_GLUCOSE, 5.5), zone) as BloodGlucoseRecord
        assertEquals(5.5, glucose.level.inMillimolesPerLiter, 1e-9)
        val temperature = WearOsLogEntryImport.record(entry(LogKind.BODY_TEMPERATURE, 36.5), zone) as BodyTemperatureRecord
        assertEquals(36.5, temperature.temperature.inCelsius, 1e-9)
    }

    @Test
    fun `a blood pressure is systolic and diastolic, and one without its diastolic is no record`() {
        val pressure = WearOsLogEntryImport.record(entry(LogKind.BLOOD_PRESSURE, 121.0, 79.0), zone) as BloodPressureRecord
        assertEquals(121.0, pressure.systolic.inMillimetersOfMercury, 1e-9)
        assertEquals(79.0, pressure.diastolic.inMillimetersOfMercury, 1e-9)
        assertNull(WearOsLogEntryImport.record(entry(LogKind.BLOOD_PRESSURE, 121.0, null), zone))
    }

    @Test
    fun `a breathing session lasts its minutes and ends when it was logged`() {
        val session = WearOsLogEntryImport.record(entry(LogKind.MINDFULNESS, 5.0), zone) as MindfulnessSessionRecord
        assertEquals(at, session.endTime)
        assertEquals(at.minusSeconds(300), session.startTime)
        assertEquals(MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_BREATHING, session.mindfulnessSessionType)
    }

    @Test
    fun `a breathing session of no minutes, or of more than a day, is no record`() {
        assertNull(WearOsLogEntryImport.record(entry(LogKind.MINDFULNESS, 0.0), zone))
        assertNull(WearOsLogEntryImport.record(entry(LogKind.MINDFULNESS, 1_441.0), zone))
    }

    @Test
    fun `a value Health Connect refuses on its own checks is dropped, not thrown`() {
        // A hydration above Health Connect's 100 litres is refused by the record's constructor.
        assertNull(WearOsLogEntryImport.record(entry(LogKind.WATER, 200_000_000.0), zone))
    }

    @Test
    fun `caffeine becomes a one second nutrition record with the milligrams as grams`() {
        val record = WearOsLogEntryImport.record(entry(LogKind.CAFFEINE, 95.0), zone) as NutritionRecord

        assertEquals(0.095, record.caffeine!!.inGrams, 1e-9)
        assertEquals(at, record.startTime)
        assertEquals(at.plusSeconds(1), record.endTime)
        assertEquals("wearos_log_caffeine_${at.toEpochMilli()}_95.0", record.metadata.clientRecordId)
        assertNull(record.energy)
        assertEquals(
            HealthPermission.getWritePermission(NutritionRecord::class),
            WearOsLogEntryImport.writePermission(LogKind.CAFFEINE),
        )
    }

    @Test
    fun `the same entry gives the same record id, and another value or time a different one`() {
        val first = WearOsLogEntryImport.clientRecordId(entry(LogKind.WATER, 250.0))
        assertEquals("wearos_log_water_${at.toEpochMilli()}_250.0", first)
        assertEquals(first, WearOsLogEntryImport.clientRecordId(entry(LogKind.WATER, 250.0)))
        assertTrue(first != WearOsLogEntryImport.clientRecordId(entry(LogKind.WATER, 300.0)))
        assertTrue(first != WearOsLogEntryImport.clientRecordId(entry(LogKind.WATER, 250.0, time = at.plusMillis(1))))
        assertTrue(first != WearOsLogEntryImport.clientRecordId(entry(LogKind.WEIGHT, 250.0)))
    }

    @Test
    fun `the record carries the id of its entry, and a pressure's id keeps the diastolic`() {
        val weight = WearOsLogEntryImport.record(entry(LogKind.WEIGHT, 72.4), zone)
        assertEquals(WearOsLogEntryImport.clientRecordId(entry(LogKind.WEIGHT, 72.4)), weight?.metadata?.clientRecordId)
        val pressure = WearOsLogEntryImport.clientRecordId(entry(LogKind.BLOOD_PRESSURE, 121.0, 79.0))
        assertEquals("wearos_log_blood_pressure_${at.toEpochMilli()}_121.0_79.0", pressure)
        assertNotNull(WearOsLogEntryImport.record(entry(LogKind.BLOOD_PRESSURE, 121.0, 79.0), zone)?.metadata)
    }

    @Test
    fun `every kind the phone writes names its write permission`() {
        LogKind.entries.filter { it != LogKind.HEART_RATE }.forEach { kind ->
            assertNotNull("write permission of $kind", WearOsLogEntryImport.writePermission(kind))
        }
    }
}
