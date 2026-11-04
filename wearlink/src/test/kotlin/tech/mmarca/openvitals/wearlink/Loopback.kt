package tech.mmarca.openvitals.wearlink

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Client and server in one JVM over piped streams: the server runs on its
 * own thread, the test drives the client. Buffers are large enough for a
 * full page; closing either end's connection closes both pipes.
 */
class Loopback(
    val trust: MemoryTrustStore = MemoryTrustStore(),
    val handler: MemoryHandler = MemoryHandler(),
    options: WearLinkServer.Options = WearLinkServer.Options(),
    val phoneAddress: String = "AA:BB:CC:DD:EE:01",
) {
    val server = WearLinkServer(trust, handler, options)

    /** One connection: a server thread serving it, and the client's end. */
    inner class Link {
        private val toServer = PipedOutputStream()
        private val fromServer = PipedOutputStream()
        private val serverInput = PipedInputStream(toServer, BUFFER)
        private val clientInput = PipedInputStream(fromServer, BUFFER)
        private val outcome = AtomicReference<WearLinkServer.Outcome?>()
        private val served = CountDownLatch(1)

        /**
         * Closing either end closes all four pipe ends: a closed Bluetooth
         * socket unblocks both sides' reads, and a piped reader only wakes
         * when its writer closes.
         */
        private fun closeAll() {
            runCatching { toServer.close() }
            runCatching { fromServer.close() }
            runCatching { clientInput.close() }
            runCatching { serverInput.close() }
        }

        val clientConnection = WearLinkConnection(clientInput, toServer, "watch") { closeAll() }
        private val serverConnection = WearLinkConnection(serverInput, fromServer, phoneAddress) { closeAll() }

        val serverThread = Thread({
            outcome.set(server.serve(serverConnection))
            served.countDown()
        }, "Loopback-server").apply { start() }

        /** The server's outcome once it returned, within [seconds]. */
        fun serverOutcome(seconds: Long = 5): WearLinkServer.Outcome? {
            served.await(seconds, TimeUnit.SECONDS)
            return outcome.get()
        }

        fun serverReturned(): Boolean = served.count == 0L
    }

    fun connect(): Link = Link()

    private companion object {
        const val BUFFER = 256 * 1024
    }
}

class MemoryTrustStore : WearLinkTrustStore {
    val tokens = HashMap<String, String>()
    val blocked = HashSet<String>()
    val pending = ArrayList<Triple<String, String, String>>()

    override fun tokenFor(peerAddress: String): String? = tokens[peerAddress]
    override fun isBlocked(peerAddress: String): Boolean = peerAddress in blocked
    override fun notePending(peerAddress: String, token: String, peerName: String) {
        pending += Triple(peerAddress, token, peerName)
    }
}

class MemoryHandler(
    var heartRate: List<WearLinkProtocol.HeartRateSample> = emptyList(),
    var minutes: List<WearLinkProtocol.SleepMinute> = emptyList(),
    var steps: List<WearLinkProtocol.StepMinute> = emptyList(),
    var ascent: List<WearLinkProtocol.AscentMinute> = emptyList(),
    var hrv: List<WearLinkProtocol.HrvWindow> = emptyList(),
    var logEntries: List<WearLinkProtocol.LogEntry> = emptyList(),
    var workouts: List<WearLinkWorkout> = emptyList(),
    /** What one `HR_LIVE` window hands out; empty is a watch off the wrist. */
    var live: List<WearLinkProtocol.HeartRateSample> = emptyList(),
    var name: String = "Galaxy Watch8 (89FZ)",
    /** Keys this fake watch accepts; a pushed key outside it is refused. */
    var preferenceKeys: Set<String> = setOf(WearLinkProtocol.Preferences.SLEEP_RECORDING),
) : WearLinkRequestHandler {
    /** Preferences the phone pushed, in the order they arrived. */
    val preferences = mutableListOf<Pair<String, String>>()

    override fun localName(): String = name
    override fun capabilities(): Set<String> =
        setOf(WearLinkProtocol.CAP_HEART_RATE, WearLinkProtocol.CAP_SLEEP_MINUTES, WearLinkProtocol.CAP_STEPS, WearLinkProtocol.CAP_ASCENT, WearLinkProtocol.CAP_HRV, WearLinkProtocol.CAP_PREFERENCES)
    override fun heartRateSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.HeartRateSample> {
        val newer = heartRate.filter { it.epochMillis > sinceEpochMillis }
        return Page(newer.take(limit), newer.size > limit)
    }
    override fun sleepMinutesSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.SleepMinute> {
        val newer = minutes.filter { it.epochMillis > sinceEpochMillis }
        return Page(newer.take(limit), newer.size > limit)
    }
    override fun stepsSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.StepMinute> {
        val newer = steps.filter { it.epochMillis > sinceEpochMillis }
        return Page(newer.take(limit), newer.size > limit)
    }
    override fun hrvSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.HrvWindow> {
        val newer = hrv.filter { it.epochMillis > sinceEpochMillis }
        return Page(newer.take(limit), newer.size > limit)
    }
    override fun ascentSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.AscentMinute> {
        val newer = ascent.filter { it.epochMillis > sinceEpochMillis }
        return Page(newer.take(limit), newer.size > limit)
    }
    override fun workoutsSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkWorkout> {
        val newer = workouts.filter { it.startMillis > sinceEpochMillis }.sortedBy { it.startMillis }
        return Page(newer.take(limit), newer.size > limit)
    }
    override fun logEntriesSince(sinceEpochMillis: Long, limit: Int): Page<WearLinkProtocol.LogEntry> {
        val newer = logEntries.filter { it.epochMillis > sinceEpochMillis }.sortedBy { it.epochMillis }
        return Page(newer.take(limit), newer.size > limit)
    }
    override fun liveHeartRate(seconds: Int, emit: (WearLinkProtocol.HeartRateSample) -> Unit): Int {
        live.forEach(emit)
        return live.size
    }
    val days = mutableListOf<Triple<WearLinkProtocol.DayKind, Long, Int>>()
    override fun putDay(kind: WearLinkProtocol.DayKind, epochDay: Long, tenths: Int): Boolean {
        if (epochDay < 0) return false
        days += Triple(kind, epochDay, tenths)
        return true
    }
    override fun setPreference(key: String, value: String): Boolean {
        if (key !in preferenceKeys) return false
        preferences += key to value
        return true
    }
}
