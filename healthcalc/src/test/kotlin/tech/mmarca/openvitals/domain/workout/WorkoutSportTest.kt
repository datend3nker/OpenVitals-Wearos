package tech.mmarca.openvitals.domain.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutSportTest {

    @Test fun `ids and exercise types are unique`() {
        assertEquals(WorkoutSport.entries.size, WorkoutSport.entries.map { it.id }.toSet().size)
        assertEquals(WorkoutSport.entries.size, WorkoutSport.entries.map { it.exerciseType }.toSet().size)
    }

    @Test fun `a route needs a distance`() {
        for (sport in WorkoutSport.entries) assertTrue(sport.id, !sport.gps || sport.distance)
    }

    @Test fun `floors are counted from steps and the barometer`() {
        for (sport in WorkoutSport.entries.filter { it.floors }) {
            assertTrue(sport.id, sport.steps && sport.elevation)
        }
    }

    @Test fun `pace is for outdoor sports on foot`() {
        assertTrue(WorkoutSport.RUNNING.pace)
        assertTrue(WorkoutSport.HIKING.pace)
        assertFalse(WorkoutSport.BIKING.pace)
        assertFalse(WorkoutSport.TREADMILL.pace)
    }

    @Test fun `lookup by type and id`() {
        assertSame(WorkoutSport.RUNNING, WorkoutSport.fromExerciseType(56))
        assertSame(WorkoutSport.TREADMILL, WorkoutSport.fromId("treadmill"))
        assertNull(WorkoutSport.fromExerciseType(999))
        assertNull(WorkoutSport.fromId(null))
    }
}
