package tech.mmarca.openvitals.wear

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import tech.mmarca.openvitals.wearlink.DebugTraceFile

/**
 * The decision log of the debug switch ([WearPreferences.debugData]). Each call to
 * [event] writes one line `epochMillis;source;event;key=value;...` to the file of the day in
 * `files/debug/` on shared storage, so `adb pull` reads it from a build that is not debuggable.
 *
 * Off is the default. While off, [event] returns at once: no file, no thread, no line built.
 * The lines hold values (pulse, movement, times) on purpose: the wearer switches this on and
 * pulls the files himself. Nothing here may be sent anywhere or attached to a report.
 *
 * Writing happens on one thread of its own, so a caller never waits for the disk.
 */
internal class DebugTrace(
    private val file: DebugTraceFile?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val async: Boolean = true,
    /** The battery percent and whether it charges, or null when unknown. Read with every line. */
    private val battery: (() -> Pair<Int, Boolean>?)? = null,
) {
    @Volatile
    var enabled: Boolean = false

    private val executor by lazy {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "DebugTrace").apply { isDaemon = true } }
    }
    private val lastSeen = HashMap<String, String>()
    private var lastBattery: Pair<Int, Boolean>? = null

    /** Writes one line. Does nothing while the switch is off. */
    fun record(source: String, event: String, vararg fields: Pair<String, Any?>) {
        if (!enabled) return
        val target = file ?: return
        val at = clock()
        noteBattery(target, at)
        val line = DebugTraceFile.line(at, source, event, fields.toList())
        write(target, at, line)
    }

    /**
     * Writes a `Power;battery` line when the level or the charging state differs from the last
     * one written. Every line of the trace is a wake-up of the watch, so the level is read as
     * often as the watch runs, with no timer of its own: the drain per state comes from these.
     */
    private fun noteBattery(target: DebugTraceFile, at: Long) {
        val reading = battery?.invoke()?.takeIf { it.first in 0..100 } ?: return
        synchronized(this) {
            if (reading == lastBattery) return
            lastBattery = reading
        }
        write(target, at, DebugTraceFile.line(at, "Power", "battery", listOf("level" to reading.first, "charging" to reading.second)))
    }

    private fun write(target: DebugTraceFile, at: Long, line: String) {
        if (async) executor.execute { target.append(at, line) } else target.append(at, line)
    }

    /**
     * Writes the line only when [signature] differs from the one last written under [key].
     * For results that are worked out every 30 seconds but should be logged when they change.
     */
    fun recordIfChanged(key: String, signature: String, source: String, event: String, vararg fields: Pair<String, Any?>) {
        if (markChanged(key, signature)) record(source, event, *fields)
    }

    /** True, and remembered, when [signature] differs from the one last seen under [key]. False while off. */
    fun markChanged(key: String, signature: String): Boolean {
        if (!enabled) return false
        synchronized(lastSeen) {
            if (lastSeen[key] == signature) return false
            lastSeen[key] = signature
            return true
        }
    }

    /** Forgets what [recordIfChanged] saw, so the next result is written again. */
    fun forgetChanges() = synchronized(lastSeen) { lastSeen.clear() }

    /** Waits until the lines handed over so far are written. For tests and for switching off. */
    fun flush() {
        if (async) executor.submit {}.get()
    }

    companion object {
        const val FOLDER = "debug"
        const val PREFIX = "trace"

        @Volatile
        var shared: DebugTrace = DebugTrace(null)
            private set

        /** The folder the debug files live in, or null when the watch has no shared storage. */
        fun folderOf(context: Context): File? = context.getExternalFilesDir(null)?.let { File(it, FOLDER) }

        /** Makes the shared instance write into the app's debug folder. Call once at startup. */
        fun install(context: Context) {
            val folder = folderOf(context) ?: return
            shared = DebugTrace(DebugTraceFile(folder, PREFIX), battery = { batteryReading(context).takeIf { it.first >= 0 } })
        }

        /** A line on the shared instance. */
        fun event(source: String, event: String, vararg fields: Pair<String, Any?>) {
            val trace = shared
            if (trace.enabled) trace.record(source, event, *fields)
        }

        /** [recordIfChanged] on the shared instance. */
        fun eventIfChanged(key: String, signature: String, source: String, event: String, vararg fields: Pair<String, Any?>) {
            val trace = shared
            if (trace.enabled) trace.recordIfChanged(key, signature, source, event, *fields)
        }

        /** [markChanged] on the shared instance. */
        fun changed(key: String, signature: String): Boolean = shared.markChanged(key, signature)

        val isEnabled: Boolean get() = shared.enabled
    }
}
