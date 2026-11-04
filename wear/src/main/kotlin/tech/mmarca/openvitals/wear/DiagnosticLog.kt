package tech.mmarca.openvitals.wear

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/**
 * One reading of the watch's state, taken every [DiagnosticsSampler.INTERVAL_MILLIS]
 * while the link service runs. It holds the battery and which recorders run,
 * never a heart rate or a step count.
 */
internal data class DiagnosticSample(
    val epochMillis: Long,
    val batteryPercent: Int,
    val charging: Boolean,
    val heartRateOn: Boolean,
    val stepsOn: Boolean,
    val bedtime: BedtimeHold,
)

internal fun DiagnosticSample.toLine(): String = listOf(
    epochMillis,
    batteryPercent,
    if (charging) 1 else 0,
    if (heartRateOn) 1 else 0,
    if (stepsOn) 1 else 0,
    bedtime.name,
).joinToString(";")

/** Null for a line that does not parse, such as one cut short by a write. */
internal fun diagnosticSampleOf(line: String): DiagnosticSample? {
    val fields = line.split(";")
    if (fields.size != 6) return null
    return DiagnosticSample(
        epochMillis = fields[0].toLongOrNull() ?: return null,
        batteryPercent = fields[1].toIntOrNull()?.takeIf { it in 0..100 } ?: return null,
        charging = fields[2] == "1",
        heartRateOn = fields[3] == "1",
        stepsOn = fields[4] == "1",
        bedtime = runCatching { BedtimeHold.valueOf(fields[5]) }.getOrNull() ?: return null,
    )
}

private const val MillisPerHour = 3_600_000.0
private const val MinRunHours = 0.5

/**
 * What the status screen shows. [drainPercentPerHour] comes from the newest
 * unplugged run of readings, and is null while that run is shorter than half
 * an hour. [heartRateShare] is the fraction of readings with heart rate on.
 */
internal data class DiagnosticSummary(
    val samples: Int,
    val spanHours: Double,
    val drainPercentPerHour: Double?,
    val heartRateShare: Double?,
)

internal fun summarizeDiagnostics(samples: List<DiagnosticSample>): DiagnosticSummary {
    val share = if (samples.isEmpty()) null else samples.count { it.heartRateOn }.toDouble() / samples.size
    // The newest run without charging; empty when the last reading was taken on the charger.
    val run = samples.takeLastWhile { !it.charging }
    val drain = if (run.size < 2) {
        null
    } else {
        val hours = (run.last().epochMillis - run.first().epochMillis) / MillisPerHour
        (run.first().batteryPercent - run.last().batteryPercent)
            .takeIf { hours >= MinRunHours }
            ?.let { it / hours }
    }
    return DiagnosticSummary(
        samples = samples.size,
        spanHours = if (samples.size < 2) 0.0 else (samples.last().epochMillis - samples.first().epochMillis) / MillisPerHour,
        drainPercentPerHour = drain,
        heartRateShare = share,
    )
}

/**
 * Where the log lives: the app's folder on shared storage, so `adb pull` reads it from a build
 * that is not debuggable. The readings hold a battery level and which recorders ran, never a
 * value. A log kept in the private folder by an earlier build is moved once.
 */
internal fun diagnosticLogFile(context: Context): File {
    val old = File(context.filesDir, DiagnosticLog.FILE_NAME)
    val folder = context.getExternalFilesDir(null) ?: return old
    val target = File(folder, DiagnosticLog.FILE_NAME)
    if (!target.exists() && old.exists()) old.copyTo(target)
    old.delete()
    return target
}

/**
 * The readings kept on the watch, one line each, newest last. The file is
 * replaced whole through a temporary file, so a reader never sees a half
 * written file; the oldest readings go once [maxSamples] is reached.
 */
internal class DiagnosticLog(private val file: File, private val maxSamples: Int = MaxSamples) {

    @Synchronized
    fun append(sample: DiagnosticSample) {
        val lines = (readLines() + sample.toLine()).takeLast(maxSamples)
        val temporary = File(file.path + ".tmp")
        temporary.writeText(lines.joinToString("\n", postfix = "\n"))
        check(temporary.renameTo(file)) { "Cannot replace the diagnostics log" }
    }

    fun samples(): List<DiagnosticSample> = readLines().mapNotNull(::diagnosticSampleOf)

    private fun readLines(): List<String> =
        if (file.exists()) file.readLines().filter { it.isNotBlank() } else emptyList()

    companion object {
        const val FILE_NAME = "diagnostics.csv"
        /** Two weeks at one reading every 15 minutes. */
        const val MaxSamples = 14 * 24 * 4
    }
}

/**
 * Takes a reading now, then every [INTERVAL_MILLIS], and once more when it is
 * stopped, so a gap in the log is a gap in the watch's day. Writes happen off
 * the main thread.
 */
internal class DiagnosticsSampler(
    private val log: DiagnosticLog,
    private val now: () -> DiagnosticSample,
    /** Runs on the writer thread after each reading; the debug snapshot uses it. It decides itself whether it is on. */
    private val onTick: () -> Unit = {},
) {
    private val handler = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    private val tick = object : Runnable {
        override fun run() {
            record()
            handler.postDelayed(this, INTERVAL_MILLIS)
        }
    }

    fun start() {
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun stop() {
        handler.removeCallbacks(tick)
        record()
        writer.shutdown()
    }

    private fun record() {
        val sample = now()
        // A battery reading the system could not give is left out, not logged as zero.
        if (sample.batteryPercent !in 0..100) return
        writer.execute {
            log.append(sample)
            runCatching(onTick)
        }
    }

    /** Runs [task] on the writer thread, after the readings already queued. Ignored once stopped. */
    fun runNow(task: () -> Unit) {
        runCatching { writer.execute { runCatching(task) } }
    }

    companion object {
        const val INTERVAL_MILLIS = 15 * 60 * 1000L
    }
}

/** The battery now, read from the sticky broadcast: no receiver, no wake-up. */
internal fun batteryReading(context: Context): Pair<Int, Boolean> {
    val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
    val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    return percent to charging
}
