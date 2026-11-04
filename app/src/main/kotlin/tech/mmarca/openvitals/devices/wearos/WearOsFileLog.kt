package tech.mmarca.openvitals.devices.wearos

import java.util.concurrent.Executors
import tech.mmarca.openvitals.wearlink.DebugTraceFile

/**
 * The phone's side of the debug switch: every line [WearOsLog] gets is also written to
 * `files/debug/wear-sync-YYYYMMDD.log`, while [enabled] says the wearer switched debug data on.
 * The format and the limits (7 days, 5 MB a file) are those of the watch's trace.
 *
 * A line holds a sync's counts, types and reasons: which pulls ran, how many rows, which
 * exception class stopped a link. It is meant for `adb pull` and for nothing else. It is never
 * attached to a report, a crash draft or the diagnostics log (see `docs/features/watches.md`).
 *
 * Off, [write] returns at once. Writing is on a thread of its own.
 */
internal class WearOsFileLog(
    private val file: DebugTraceFile,
    private val enabled: () -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val async: Boolean = true,
) {
    private val executor by lazy {
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "WearOsFileLog").apply { isDaemon = true } }
    }

    fun write(message: String) {
        if (!enabled()) return
        val at = clock()
        val line = DebugTraceFile.line(at, SOURCE, "log", listOf("msg" to message))
        if (async) executor.execute { file.append(at, line) } else file.append(at, line)
    }

    /** Waits until the lines handed over so far are written. For tests. */
    fun flush() {
        if (async) executor.submit {}.get()
    }

    companion object {
        const val PREFIX = "wear-sync"
        const val FOLDER = "debug"
        private const val SOURCE = "WearSync"
    }
}
