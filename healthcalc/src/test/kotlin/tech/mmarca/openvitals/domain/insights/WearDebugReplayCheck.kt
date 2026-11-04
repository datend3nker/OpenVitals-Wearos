package tech.mmarca.openvitals.domain.insights

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import tech.mmarca.openvitals.domain.model.WearSleepMinute
import tech.mmarca.openvitals.domain.model.WearSleepMinuteMapping
import tech.mmarca.openvitals.wearlink.WearLinkProtocol

/**
 * A check run by hand, skipped in CI. OPENVITALS_WEAR_DEBUG_DIR names a folder pulled from the
 * watch (`scripts/wear-debug-pull.sh`) that holds `minutes.csv`. It runs the watch's own
 * calculation again and prints the nights and naps with the numbers behind them, and the
 * worn minutes and median pulse per day. It fails only when no minute can be read.
 *
 *     env OPENVITALS_WEAR_DEBUG_DIR=<folder> ./gradlew :healthcalc:test --tests '*WearDebugReplayCheck*' --rerun -i
 */
class WearDebugReplayCheck {

    @Test
    fun `the minutes of a debug pull give the nights and naps the watch showed`() {
        val folder = System.getenv("OPENVITALS_WEAR_DEBUG_DIR")?.let(::File)?.takeIf { it.isDirectory }
        assumeTrue(folder != null)
        val file = File(folder, "minutes.csv")
        assumeTrue(file.isFile)
        val rows = file.readLines().mapNotNull { WearLinkProtocol.parseSleepMinute(it) }.map(WearSleepMinuteMapping::toDomain)
        println("minutes.csv: ${rows.size} readable minutes")
        assertTrue("no minute could be read", rows.isNotEmpty())
        val offset = rows.last().zoneOffset
        print(rows, offset)
    }

    private fun print(rows: List<WearSleepMinute>, offset: ZoneOffset) {
        val first = rows.minOf { it.time }.atOffset(offset).toLocalDate()
        val last = rows.maxOf { it.time }.atOffset(offset).toLocalDate()
        println("range: ${rows.minOf { it.time }} to ${rows.maxOf { it.time }}")
        var date = first
        while (!date.isAfter(last.plusDays(1))) {
            val night = WearNight.estimate(rows, date, offset)
            if (night == null) {
                println("night ending $date: none found")
            } else {
                println("night ending $date: " + WearNight.reasons(night).joinToString(" ") { "${it.first}=${it.second}" })
                println("  ${night.summary()}")
                WearNight.restingHeartRate(rows, night)?.let { println("  resting heart rate $it") }
            }
            date = date.plusDays(1)
        }
        val report = WearNaps.detectAllWithReasons(rows, offset)
        println("nap baseline: level=${report.wakingLevel} from ${report.baselineMinutes} day minutes (needs ${WearNaps.Config().minBaselineMinutes})")
        for (reason in report.naps) {
            val nap = reason.nap
            println(
                "nap ${nap.onset} to ${nap.end}: sleep=${nap.session.sleepMinutes}m level=${reason.wakingLevel} " +
                    "requiredDrop=${reason.requiredDrop} daytime=${reason.daytime} medianHr=${reason.medianHeartRate} " +
                    "sleepShare=${reason.sleepShare} movement=${reason.meanMovement} lookback=${reason.onsetLookbackMinutes}m",
            )
        }
        if (report.naps.isEmpty()) println("no naps")
        val zone: ZoneId = offset
        rows.groupBy { it.time.atZone(zone).toLocalDate() }.toSortedMap().forEach { (day, list) ->
            val worn = list.count { it.heartRate != null }
            val pulses = list.mapNotNull { it.heartRate }.sorted()
            println("day $day: minutes=${list.size} withPulse=$worn medianHr=${pulses.getOrNull(pulses.size / 2)}")
        }
        println("done at ${Instant.now()}")
    }
}
