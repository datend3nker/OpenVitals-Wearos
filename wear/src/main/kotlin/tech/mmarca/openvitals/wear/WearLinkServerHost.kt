package tech.mmarca.openvitals.wear

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import tech.mmarca.openvitals.wear.features.quicklog.EntryType
import tech.mmarca.openvitals.wear.features.quicklog.LoggedEntry
import tech.mmarca.openvitals.wearlink.Page
import tech.mmarca.openvitals.wearlink.WearLinkConnection
import tech.mmarca.openvitals.wearlink.WearLinkProtocol
import tech.mmarca.openvitals.wearlink.WearLinkRequestHandler
import tech.mmarca.openvitals.wearlink.WearLinkServer

/**
 * Hosts the RFCOMM listener and runs `WearLinkServer` for every phone that
 * connects, one thread per connection. All the protocol lives in
 * `:wearlink`; this class is the Bluetooth around it and the hands the
 * store to its request handler.
 */
class WearLinkServerHost(
    private val adapter: () -> BluetoothAdapter?,
    trust: WearTrustStore,
    store: MetricStore,
    live: LiveHeartRate,
    preferences: WearPreferences,
    /** What the wearer logged on this watch, the entries the phone pulls. */
    logEntries: () -> List<LoggedEntry>,
    private val localName: () -> String,
    private val state: WearLinkState = WearLinkState,
) {
    private val server = WearLinkServer(trust, Handler(store, live, preferences, logEntries, localName))

    @Volatile
    private var serverSocket: BluetoothServerSocket? = null
    private val isListening = AtomicBoolean(false)

    /** Bumped per listener start, so a thread that is shutting down cannot stop its successor. */
    private val generation = AtomicInteger(0)

    fun start() {
        if (isListening.getAndSet(true)) return
        val mine = generation.incrementAndGet()
        Thread({ listen(mine) }, "WearLinkServerHost").start()
    }

    fun stop() {
        isListening.set(false)
        // Unblocks accept(); the listener thread then exits.
        runCatching { serverSocket?.close() }
        state.update { it.copy(listening = false) }
    }

    private fun listen(mine: Int) {
        var socket: BluetoothServerSocket? = null
        try {
            val adapter = adapter()
            if (adapter == null || !adapter.isEnabled) {
                // The state receiver starts it again once Bluetooth is on.
                Log.w(TAG, "Bluetooth unavailable or disabled")
                return
            }
            val server = try {
                adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, WearLinkProtocol.SERVICE_UUID)
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing Bluetooth permission for RFCOMM server", e)
                return
            }
            socket = server
            serverSocket = server
            state.update { it.copy(listening = true) }
            Log.i(TAG, "Listening on ${WearLinkProtocol.SERVICE_UUID}")
            while (isListening.get() && generation.get() == mine) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    if (isListening.get()) Log.e(TAG, "Accept failed", e)
                    break
                }
                serveOnThread(client)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Cannot start the listener", e)
        } finally {
            runCatching { socket?.close() }
            if (serverSocket === socket) serverSocket = null
            if (generation.get() == mine) {
                isListening.set(false)
                state.update { it.copy(listening = false) }
            }
        }
    }

    private fun serveOnThread(socket: BluetoothSocket) {
        Thread({
            val address = runCatching { socket.remoteDevice.address }.getOrNull() ?: "unknown"
            val connection = try {
                WearLinkConnection(socket.inputStream, socket.outputStream, address) { socket.close() }
            } catch (e: IOException) {
                Log.w(TAG, "No streams for the phone link: ${e::class.java.simpleName}")
                runCatching { socket.close() }
                return@Thread
            }
            state.update { it.copy(activeClients = server.activeClients + 1) }
            val outcome = server.serve(connection)
            state.update { it.copy(activeClients = server.activeClients) }
            Log.i(TAG, "Served the phone link: $outcome")
        }, "WearLinkServerHost-client").start()
    }

    private class Handler(
        private val store: MetricStore,
        private val live: LiveHeartRate,
        private val preferences: WearPreferences,
        private val logEntries: () -> List<LoggedEntry>,
        private val localName: () -> String,
    ) : WearLinkRequestHandler {
        override fun localName(): String = localName.invoke()

        /**
         * Only what the wearer tracks is offered, so the phone does not ask for
         * the rest. A switch off withdraws its capability; the stored rows stay.
         */
        override fun capabilities(): Set<String> = LinkedHashSet<String>().apply {
            if (HeartRateSchedule.offered(preferences.heartRate, preferences.sleepRecording, preferences.heartRateDay, preferences.heartRateNight)) {
                add(WearLinkProtocol.CAP_HEART_RATE)
            }
            if (preferences.sleepRecording) add(WearLinkProtocol.CAP_SLEEP_MINUTES)
            if (preferences.steps) add(WearLinkProtocol.CAP_STEPS)
            if (preferences.ascent) add(WearLinkProtocol.CAP_ASCENT)
            if (preferences.heartRate && live.hasSensor) add(WearLinkProtocol.CAP_LIVE_HEART_RATE)
            add(WearLinkProtocol.CAP_PREFERENCES)
            add(WearLinkProtocol.CAP_LOG)
        }

        override fun logEntriesSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.LogEntry> {
            // The log is capped at 500 entries in its own store, so the whole of it is read here.
            val newer = logEntries().filter { it.timeMillis > sinceEpochMillis }.sortedBy { it.timeMillis }
            return Page(
                newer.take(limit).map { WearLinkProtocol.LogEntry(it.timeMillis, it.type.wireKind(), it.value, it.value2) },
                newer.size > limit,
            )
        }

        override fun setPreference(key: String, value: String): Boolean = preferences.applyPreference(key, value)

        override fun heartRateSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.HeartRateSample> =
            page(WearMetrics.HEART_RATE, sinceEpochMillis, limit)

        override fun sleepMinutesSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.SleepMinute> =
            page(WearMetrics.SLEEP_MINUTES, sinceEpochMillis, limit)

        override fun stepsSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.StepMinute> =
            page(WearMetrics.STEPS, sinceEpochMillis, limit)

        override fun ascentSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.AscentMinute> =
            page(WearMetrics.ASCENT, sinceEpochMillis, limit)

        override fun hrvSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.HrvWindow> =
            page(WearMetrics.HRV, sinceEpochMillis, limit)

        override fun liveHeartRate(seconds: Int, emit: (WearLinkProtocol.HeartRateSample) -> Unit): Int =
            live.window(seconds, emit)

        private fun <T> page(metric: WearMetric<T>, sinceEpochMillis: Long, limit: Int): Page<T> {
            // One more than asked tells whether the limit cut the reply.
            val rows = store.since(metric, sinceEpochMillis, limit + 1)
            return Page(rows.take(limit), rows.size > limit)
        }
    }

    private companion object {
        const val TAG = "WearLinkServerHost"
        const val SERVICE_NAME = "OpenVitalsWearApp"
    }
}

/**
 * The wire name of a logged kind. Exhaustive on purpose: a new [EntryType]
 * does not compile until the protocol has its kind.
 */
internal fun EntryType.wireKind(): WearLinkProtocol.LogKind = when (this) {
    EntryType.WATER -> WearLinkProtocol.LogKind.WATER
    EntryType.WEIGHT -> WearLinkProtocol.LogKind.WEIGHT
    EntryType.BLOOD_PRESSURE -> WearLinkProtocol.LogKind.BLOOD_PRESSURE
    EntryType.MINDFULNESS -> WearLinkProtocol.LogKind.MINDFULNESS
    EntryType.HEART_RATE -> WearLinkProtocol.LogKind.HEART_RATE
    EntryType.HRV -> WearLinkProtocol.LogKind.HRV
    EntryType.BODY_FAT -> WearLinkProtocol.LogKind.BODY_FAT
    EntryType.BLOOD_OXYGEN -> WearLinkProtocol.LogKind.BLOOD_OXYGEN
    EntryType.RESPIRATORY_RATE -> WearLinkProtocol.LogKind.RESPIRATORY_RATE
    EntryType.BODY_TEMPERATURE -> WearLinkProtocol.LogKind.BODY_TEMPERATURE
    EntryType.BLOOD_GLUCOSE -> WearLinkProtocol.LogKind.BLOOD_GLUCOSE
    EntryType.HEIGHT -> WearLinkProtocol.LogKind.HEIGHT
}
