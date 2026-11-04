package tech.mmarca.openvitals.wear.features.recording

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Hiking
import androidx.compose.material.icons.outlined.PedalBike
import androidx.compose.material.icons.outlined.Rowing
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.ui.graphics.vector.ImageVector
import tech.mmarca.openvitals.domain.workout.WorkoutSport
import tech.mmarca.openvitals.wear.R

enum class ActivityType(@param:StringRes val label: Int, val icon: ImageVector, val sport: WorkoutSport) {
    WALK(R.string.activity_walk, Icons.AutoMirrored.Outlined.DirectionsWalk, WorkoutSport.WALKING),
    RUN(R.string.activity_run, Icons.AutoMirrored.Outlined.DirectionsRun, WorkoutSport.RUNNING),
    CYCLE(R.string.activity_cycle, Icons.AutoMirrored.Outlined.DirectionsBike, WorkoutSport.BIKING),
    HIKE(R.string.activity_hike, Icons.Outlined.Hiking, WorkoutSport.HIKING),

    // Indoors: no position. The watch counts what its sensors give (steps on a treadmill) and the
    // heart rate; the phone derives a distance afterwards where the sport has one.
    TREADMILL(R.string.activity_treadmill, Icons.AutoMirrored.Outlined.DirectionsRun, WorkoutSport.TREADMILL),
    STATIONARY_BIKE(R.string.activity_stationary_bike, Icons.Outlined.PedalBike, WorkoutSport.STATIONARY_BIKE),
    ROWING_MACHINE(R.string.activity_rowing_machine, Icons.Outlined.Rowing, WorkoutSport.ROWING_MACHINE),
    STRENGTH(R.string.activity_strength, Icons.Outlined.FitnessCenter, WorkoutSport.STRENGTH_TRAINING),
    HIIT(R.string.activity_hiit, Icons.Outlined.Bolt, WorkoutSport.HIIT),
    YOGA(R.string.activity_yoga, Icons.Outlined.SelfImprovement, WorkoutSport.YOGA),
    WORKOUT(R.string.activity_workout, Icons.Outlined.FitnessCenter, WorkoutSport.OTHER_WORKOUT),
}
