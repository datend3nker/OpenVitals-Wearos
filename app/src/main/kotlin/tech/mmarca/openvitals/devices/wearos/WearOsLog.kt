package tech.mmarca.openvitals.devices.wearos

import android.util.Log
import java.io.File
import tech.mmarca.openvitals.BuildConfig
import tech.mmarca.openvitals.wearlink.DebugTraceFile

/**
 * Logging for the Wear OS link. In debug builds it goes to logcat, like `GarminLog`. In any build
 * it also goes to a file while the wearer has switched debug data on ([WearOsFileLog]). Without a
 * sink it is a no-op, which is also what a unit test sees: the JVM `Log` stub throws.
 */
internal object WearOsLog {

    private const val TAG = "OpenVitalsWearOs"

    @Volatile
    private var sink: ((String) -> Unit)? = null

    @Volatile
    private var fileLog: WearOsFileLog? = null

    /** Routes logs to logcat. A no-op in a release build. */
    fun installLogcatSink() {
        if (BuildConfig.DEBUG) sink = { message -> runCatching { Log.d(TAG, message) } }
    }

    /** Routes logs to the debug folder [folder] while [enabled] is true. Not tied to a debug build. */
    fun installFileSink(folder: File?, enabled: () -> Boolean) {
        if (folder == null) return
        fileLog = WearOsFileLog(DebugTraceFile(folder, WearOsFileLog.PREFIX), enabled)
    }

    fun log(message: String) {
        sink?.invoke(message)
        fileLog?.write(message)
    }

    /** One line for a failure: the class of the exception and of its cause, never their text. */
    fun failure(what: String, error: Throwable) = log(failureText(what, error))

    internal fun failureText(what: String, error: Throwable): String {
        val cause = error.cause?.let { ", cause ${it::class.java.simpleName}" }.orEmpty()
        return "$what: ${error::class.java.simpleName}$cause"
    }
}
