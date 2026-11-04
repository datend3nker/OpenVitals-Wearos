package tech.mmarca.openvitals.wear.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openvitals.wear.MetricSource
import tech.mmarca.openvitals.wear.MetricTile
import tech.mmarca.openvitals.wear.features.measure.Measurement
import tech.mmarca.openvitals.wear.features.recording.ActivityType
import tech.mmarca.openvitals.wear.features.recording.WorkoutField

class CapabilityProbeTest {

    private val everything = WatchSensors(
        heartRate = true,
        heartBeat = true,
        stepCounter = true,
        barometer = true,
        gps = true,
        vendorSpo2 = true,
    )
    private val nothing = WatchSensors(
        heartRate = false,
        heartBeat = false,
        stepCounter = false,
        barometer = false,
        gps = false,
        vendorSpo2 = false,
    )

    @Test
    fun `a full watch offers every sensor metric except VO2 max`() {
        val capabilities = capabilitiesOf(everything)
        assertTrue(capabilities.probed)
        val sensorTiles = MetricTile.entries.filter { it.source == MetricSource.SENSOR }.toSet()
        assertEquals(sensorTiles - MetricTile.VO2_MAX, capabilities.sensorMetrics)
        assertEquals(Measurement.entries.toSet(), capabilities.measurements)
    }

    @Test
    fun `a bare watch offers no sensor metric, measurement or workout`() {
        val capabilities = capabilitiesOf(nothing)
        assertTrue(capabilities.sensorMetrics.isEmpty())
        assertTrue(capabilities.measurements.isEmpty())
        assertTrue(capabilities.workouts.isEmpty())
        // Phone and logged metrics stay on offer.
        assertTrue(MetricTile.SLEEP in capabilities.availableMetrics)
    }

    @Test
    fun `workout fields follow the activity and the sensors`() {
        val workouts = capabilitiesOf(everything).workouts
        assertEquals(
            setOf(WorkoutField.HEART_RATE, WorkoutField.DISTANCE, WorkoutField.PACE, WorkoutField.ELEVATION, WorkoutField.STEPS),
            workouts[ActivityType.RUN],
        )
        assertEquals(
            setOf(WorkoutField.HEART_RATE, WorkoutField.DISTANCE, WorkoutField.SPEED, WorkoutField.ELEVATION),
            workouts[ActivityType.CYCLE],
        )
        // Indoors: no position, no height.
        assertEquals(setOf(WorkoutField.HEART_RATE), workouts[ActivityType.WORKOUT])
        assertFalse(workouts.values.any { WorkoutField.CALORIES in it })
    }

    @Test
    fun `HRV is offered with single beats or with the raw pulse signal, not with neither`() {
        val noBeats = everything.copy(heartBeat = false)
        assertTrue(Measurement.HRV in capabilitiesOf(noBeats.copy(rawPpg = true)).measurements)
        assertTrue(Measurement.HRV in capabilitiesOf(noBeats.copy(heartBeat = true)).measurements)
        assertFalse(Measurement.HRV in capabilitiesOf(noBeats).measurements)
    }

    @Test
    fun `indoor sports show only what the sensors give indoors`() {
        val workouts = capabilitiesOf(everything).workouts
        assertEquals(setOf(WorkoutField.HEART_RATE, WorkoutField.STEPS), workouts[ActivityType.TREADMILL])
        for (sport in listOf(ActivityType.STATIONARY_BIKE, ActivityType.ROWING_MACHINE, ActivityType.STRENGTH, ActivityType.HIIT, ActivityType.YOGA, ActivityType.VOLLEYBALL)) {
            assertEquals(sport.name, setOf(WorkoutField.HEART_RATE), workouts[sport])
        }
    }

    @Test
    fun `a watch without a heart rate sensor still offers a sport that has steps, not one that shows nothing`() {
        val workouts = capabilitiesOf(everything.copy(heartRate = false)).workouts
        assertEquals(setOf(WorkoutField.STEPS), workouts[ActivityType.TREADMILL])
        assertFalse(ActivityType.YOGA in workouts)
    }

    @Test
    fun `without GPS a run still has heart rate, steps and the barometer's height`() {
        val run = capabilitiesOf(everything.copy(gps = false)).workouts[ActivityType.RUN]
        assertEquals(setOf(WorkoutField.HEART_RATE, WorkoutField.ELEVATION, WorkoutField.STEPS), run)
    }
}
