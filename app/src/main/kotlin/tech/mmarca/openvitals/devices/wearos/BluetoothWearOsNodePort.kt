package tech.mmarca.openvitals.devices.wearos

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.mmarca.openvitals.domain.model.WearSleepMinute
import tech.mmarca.openvitals.domain.model.WearSleepMinuteMapping
import tech.mmarca.openvitals.wearlink.HeartRateInterval
import tech.mmarca.openvitals.wearlink.WearLinkClient
import tech.mmarca.openvitals.wearlink.WearLinkConnection
import tech.mmarca.openvitals.wearlink.WearLinkOutcome
import tech.mmarca.openvitals.wearlink.WearLinkProtocol
import tech.mmarca.openvitals.wearlink.WearLinkWorkout
import tech.mmarca.openvitals.wearlink.WearLinkSession

/**
 * Bluetooth Classic RFCOMM implementation of [WearOsNodePort], on AOSP APIs
 * only. Finds the bonded watch, opens a socket to the OpenVitals Wear OS
 * app's UUID, and speaks `:wearlink`: a hello carrying this phone's token
 * for that watch, then the requests of one sync. The watch side is
 * `wear/.../WearLinkServerHost`.
 *
 * RFCOMM is Classic, not BLE, so no radio lease is taken; phone-to-phone
 * sync works the same way. Every outcome the watch can give is a
 * [WearOsLinkFailure]; a missing permission stays a `SecurityException`.
 */
@Singleton
class BluetoothWearOsNodePort @Inject constructor(
    @ApplicationContext private val context: Context,
    private val linkStore: WearOsLinkStore,
) : WearOsNodePort {

    @SuppressLint("MissingPermission")
    override suspend fun checkStatus(
        targetAddress: String?,
        targetName: String?,
    ): WearOsCompanionStatus = withContext(Dispatchers.IO) {
        val match = try {
            bondedWatch(targetAddress, targetName)
        } catch (failure: WearOsLinkFailure) {
            return@withContext WearOsCompanionStatus(appStatus = failure.toAppStatus(), lastCheckedAt = Instant.now())
        }
        val status = try {
            withSession(match, targetAddress, PING_TIMEOUT_MS) { session ->
                unwrap(session.ping())
                adoptWatchPreferences(session, targetAddress)
                WearOsAppStatus.APP_RUNNING
            }
        } catch (failure: WearOsLinkFailure) {
            failure.toAppStatus()
        }
        WearOsCompanionStatus(
            isPaired = true,
            connectedNodeName = match.name ?: match.address,
            connectedNodeAddress = match.address,
            appStatus = status,
            lastCheckedAt = Instant.now(),
            watchName = lastWatchName,
        )
    }

    override suspend fun pullHeartRate(
        targetAddress: String?,
        targetName: String?,
        since: Instant,
    ): WearOsHeartRatePage? = withContext(Dispatchers.IO) {
        val match = bondedWatchOrNull(targetAddress, targetName) ?: return@withContext null
        withSession(match, targetAddress, PULL_TIMEOUT_MS) { session ->
            val page = unwrap(session.pullHeartRate(since.toEpochMilli(), WearLinkProtocol.MAX_SAMPLES_PER_REQUEST))
            WearOsHeartRatePage(
                samples = page.items.map { WearOsHeartRateSample(Instant.ofEpochMilli(it.epochMillis), it.bpm) },
                hasMore = page.more,
            )
        }
    }

    override suspend fun pullSleepMinutes(
        targetAddress: String?,
        targetName: String?,
        since: Instant,
    ): WearOsSleepMinutePage? = withContext(Dispatchers.IO) {
        val match = bondedWatchOrNull(targetAddress, targetName) ?: return@withContext null
        withSession(match, targetAddress, PULL_TIMEOUT_MS) { session ->
            // A watch with sleep recording switched off no longer offers the capability.
            if (WearLinkProtocol.CAP_SLEEP_MINUTES !in session.peer.capabilities) return@withSession WearOsSleepMinutePage(emptyList(), false)
            val page = unwrap(session.pullSleepMinutes(since.toEpochMilli(), WearLinkProtocol.MAX_MINUTES_PER_REQUEST))
            WearOsSleepMinutePage(minutes = page.items.map(WearSleepMinuteMapping::toDomain), hasMore = page.more)
        }
    }

    override suspend fun pullSteps(
        targetAddress: String?,
        targetName: String?,
        since: Instant,
    ): WearOsStepPage? = withContext(Dispatchers.IO) {
        val match = bondedWatchOrNull(targetAddress, targetName) ?: return@withContext null
        withSession(match, targetAddress, PULL_TIMEOUT_MS) { session ->
            // A watch app from before steps would answer the request with an error.
            if (WearLinkProtocol.CAP_STEPS !in session.peer.capabilities) return@withSession WearOsStepPage(emptyList(), false)
            val page = unwrap(session.pullSteps(since.toEpochMilli(), WearLinkProtocol.MAX_STEP_MINUTES_PER_REQUEST))
            WearOsStepPage(
                minutes = page.items.map { WearOsStepMinute(Instant.ofEpochMilli(it.epochMillis), it.steps) },
                hasMore = page.more,
            )
        }
    }

    override suspend fun pullAscent(
        targetAddress: String?,
        targetName: String?,
        since: Instant,
    ): WearOsAscentPage? = withContext(Dispatchers.IO) {
        val match = bondedWatchOrNull(targetAddress, targetName) ?: return@withContext null
        withSession(match, targetAddress, PULL_TIMEOUT_MS) { session ->
            // A watch app from before ascent would answer the request with an error.
            if (WearLinkProtocol.CAP_ASCENT !in session.peer.capabilities) return@withSession WearOsAscentPage(emptyList(), false)
            val page = unwrap(session.pullAscent(since.toEpochMilli(), WearLinkProtocol.MAX_ASCENT_MINUTES_PER_REQUEST))
            WearOsAscentPage(
                minutes = page.items.map { WearOsAscentMinute(Instant.ofEpochMilli(it.epochMillis), it.decimetres / 10.0) },
                hasMore = page.more,
            )
        }
    }

    override suspend fun pullWorkouts(
        targetAddress: String?,
        targetName: String?,
        since: Instant,
    ): WearOsWorkoutPage? = withContext(Dispatchers.IO) {
        val match = bondedWatchOrNull(targetAddress, targetName) ?: return@withContext null
        withSession(match, targetAddress, PULL_TIMEOUT_MS) { session ->
            if (WearLinkProtocol.CAP_WORKOUTS !in session.peer.capabilities) return@withSession WearOsWorkoutPage(emptyList(), false)
            val page = unwrap(session.pullWorkouts(since.toEpochMilli(), WearLinkProtocol.MAX_WORKOUTS_PER_REQUEST))
            WearOsWorkoutPage(page.items.map { it.toPortWorkout() }, page.more)
        }
    }

    override suspend fun pullHrv(
        targetAddress: String?,
        targetName: String?,
        since: Instant,
    ): WearOsHrvPage? = withContext(Dispatchers.IO) {
        val match = bondedWatchOrNull(targetAddress, targetName) ?: return@withContext null
        withSession(match, targetAddress, PULL_TIMEOUT_MS) { session ->
            // A watch app from before HRV, or one without the sensor or with it off, would answer with an error.
            if (WearLinkProtocol.CAP_HRV !in session.peer.capabilities) return@withSession WearOsHrvPage(emptyList(), false)
            val page = unwrap(session.pullHrv(since.toEpochMilli(), WearLinkProtocol.MAX_HRV_WINDOWS_PER_REQUEST))
            WearOsHrvPage(
                windows = page.items.map { WearOsHrvWindow(Instant.ofEpochMilli(it.epochMillis), it.rmssdMillis, it.beats) },
                hasMore = page.more,
            )
        }
    }

    override suspend fun pullLogEntries(
        targetAddress: String?,
        targetName: String?,
        since: Instant,
    ): WearOsLogEntryPage? = withContext(Dispatchers.IO) {
        val match = bondedWatchOrNull(targetAddress, targetName) ?: return@withContext null
        withSession(match, targetAddress, PULL_TIMEOUT_MS) { session ->
            // A watch app from before the log would answer the request with an error.
            if (WearLinkProtocol.CAP_LOG !in session.peer.capabilities) return@withSession WearOsLogEntryPage(emptyList(), false)
            val page = unwrap(session.pullLogEntries(since.toEpochMilli(), WearLinkProtocol.MAX_LOG_ENTRIES_PER_REQUEST))
            WearOsLogEntryPage(
                entries = page.items.map { WearOsLogEntry(Instant.ofEpochMilli(it.epochMillis), it.kind, it.value, it.value2) },
                hasMore = page.more,
            )
        }
    }

    override suspend fun liveHeartRate(
        targetAddress: String?,
        targetName: String?,
        onSample: (WearOsHeartRateSample) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        val match = bondedWatchOrNull(targetAddress, targetName) ?: return@withContext false
        withSession(match, targetAddress, LIVE_SESSION_MS + PING_TIMEOUT_MS) { session ->
            if (WearLinkProtocol.CAP_LIVE_HEART_RATE !in session.peer.capabilities) return@withSession false
            val started = System.currentTimeMillis()
            // One window after another on the same connection, each shorter than the watch's idle timeout.
            while (System.currentTimeMillis() - started < LIVE_SESSION_MS) {
                val count = unwrap(
                    session.liveHeartRate(WearLinkProtocol.MAX_LIVE_SECONDS) {
                        onSample(WearOsHeartRateSample(Instant.ofEpochMilli(it.epochMillis), it.bpm))
                    },
                )
                if (count == 0) break
            }
            true
        }
    }

    override suspend fun setWatchSetting(
        targetAddress: String?,
        targetName: String?,
        setting: WearOsWatchSetting,
        enabled: Boolean,
    ): Boolean = withContext(Dispatchers.IO) {
        val match = bondedWatchOrNull(targetAddress, targetName) ?: return@withContext false
        val value = if (enabled) WearLinkProtocol.Preferences.ON else WearLinkProtocol.Preferences.OFF
        val applied = pushPreference(match, targetAddress, setting.protocolKey, value)
        if (applied) linkStore.setWatchSetting(targetAddress, setting.protocolKey, enabled)
        applied
    }

    override suspend fun setHeartRateInterval(
        targetAddress: String?,
        targetName: String?,
        period: WearOsHeartRatePeriod,
        interval: HeartRateInterval,
    ): Boolean = withContext(Dispatchers.IO) {
        val match = bondedWatchOrNull(targetAddress, targetName) ?: return@withContext false
        val applied = pushPreference(match, targetAddress, period.protocolKey, interval.wire)
        if (applied) linkStore.setWatchValue(targetAddress, period.protocolKey, interval.wire)
        applied
    }

    override fun watchSetting(targetAddress: String?, setting: WearOsWatchSetting): Boolean =
        linkStore.watchSetting(targetAddress, setting.protocolKey, default = setting.defaultOn)

    override fun heartRateInterval(targetAddress: String?, period: WearOsHeartRatePeriod): HeartRateInterval =
        HeartRateInterval.fromWire(
            linkStore.watchValue(targetAddress, period.protocolKey, HeartRateInterval.EVERY_10_SECONDS.wire),
        ) ?: HeartRateInterval.EVERY_10_SECONDS

    /**
     * The watch is the truth for its settings: the wearer can change them on the watch itself. Reads
     * them back into the store the phone's switches show. A watch app that does not know the request
     * (older) or a failed read leaves the stored values as they were.
     */
    private fun adoptWatchPreferences(session: WearLinkSession, targetAddress: String?) {
        if (WearLinkProtocol.CAP_PREFERENCES !in session.peer.capabilities) return
        val held = (session.pullPreferences() as? WearLinkOutcome.Ok)?.value ?: return
        for (setting in WearOsWatchSetting.entries) {
            when (held[setting.protocolKey]) {
                WearLinkProtocol.Preferences.ON -> linkStore.setWatchSetting(targetAddress, setting.protocolKey, true)
                WearLinkProtocol.Preferences.OFF -> linkStore.setWatchSetting(targetAddress, setting.protocolKey, false)
            }
        }
        for (period in WearOsHeartRatePeriod.entries) {
            held[period.protocolKey]?.let { value ->
                HeartRateInterval.fromWire(value)?.let { linkStore.setWatchValue(targetAddress, period.protocolKey, it.wire) }
            }
        }
    }

    /** Sends one `SET_PREF`. False for a watch app with no settings channel; the caller tells the wearer to update it. */
    private suspend fun pushPreference(match: BondedWatch, targetAddress: String?, key: String, value: String): Boolean =
        withSession(match, targetAddress, PING_TIMEOUT_MS) { session ->
            // An older watch app has no settings channel; the caller tells the wearer to update.
            if (WearLinkProtocol.CAP_PREFERENCES !in session.peer.capabilities) return@withSession false
            unwrap(session.setPreference(key, value))
            true
        }

    /** The name the watch gave in its last `OK`, for the status card. */
    @Volatile
    private var lastWatchName: String? = null

    /** The bonded entry for the registered watch. Throws [WearOsLinkFailure.BluetoothOff] or [WearOsLinkFailure.NotBonded]. */
    @SuppressLint("MissingPermission")
    private fun bondedWatch(targetAddress: String?, targetName: String?): BondedWatch {
        val adapter = adapter()
        if (adapter == null || !adapter.isEnabled) throw WearOsLinkFailure.BluetoothOff()
        // A missing BLUETOOTH_CONNECT propagates: the screen shows the grant affordance.
        val bonded = adapter.bondedDevices.orEmpty().map { BondedWatch(it.address, it.name) }
        return WearOsBondMatcher.pick(bonded, targetAddress, targetName, linkAddress = linkStore.linkAddress(targetAddress))
            ?: throw WearOsLinkFailure.NotBonded()
    }

    /** Null when no bonded watch matches, as the pulls promise; Bluetooth off is still a failure. */
    private fun bondedWatchOrNull(targetAddress: String?, targetName: String?): BondedWatch? =
        try {
            bondedWatch(targetAddress, targetName)
        } catch (_: WearOsLinkFailure.NotBonded) {
            null
        }

    private fun adapter(): BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    /**
     * Connects, says hello, runs [block] over the session and closes the
     * socket. `connect()` and the reads block and ignore cancellation, so a
     * watchdog closes the socket after [timeoutMs] or when the caller is
     * cancelled, whichever comes first. The block runs inside the scope that
     * owns the watchdog: a session must never outlive it, since the scope
     * only returns once the watchdog has run or been cancelled.
     */
    @SuppressLint("MissingPermission")
    private suspend fun <T> withSession(
        match: BondedWatch,
        registeredAddress: String?,
        timeoutMs: Long,
        block: (WearLinkSession) -> T,
    ): T = coroutineScope {
        val adapter = adapter() ?: throw WearOsLinkFailure.BluetoothOff()
        val device = adapter.getRemoteDevice(match.address)
        val socket = try {
            device.createRfcommSocketToServiceRecord(WearLinkProtocol.SERVICE_UUID)
        } catch (e: IOException) {
            Log.d(TAG, "No RFCOMM socket for ${device.address}: ${e.message}")
            throw WearOsLinkFailure.NoAnswer(e)
        }
        val watchdog = launch {
            try {
                delay(timeoutMs)
            } finally {
                runCatching { socket.close() }
            }
        }
        try {
            // A running discovery slows the connect down. Cancelling it needs
            // BLUETOOTH_SCAN, which the exchange itself does not.
            runCatching { adapter.cancelDiscovery() }
            try {
                socket.connect()
            } catch (e: IOException) {
                // Off, out of range, app not listening, or the watchdog closed the socket.
                Log.d(TAG, "No answer from ${device.address}: ${e.message}")
                throw WearOsLinkFailure.NoAnswer(e)
            }
            val connection = WearLinkConnection(socket.inputStream, socket.outputStream, match.address) {
                runCatching { socket.close() }
            }
            val hello = WearLinkProtocol.Hello(
                version = WearLinkProtocol.VERSION,
                capabilities = setOf(WearLinkProtocol.CAP_HEART_RATE, WearLinkProtocol.CAP_SLEEP_MINUTES, WearLinkProtocol.CAP_STEPS),
                token = linkStore.tokenFor(registeredAddress, match.address),
                name = localName(adapter),
            )
            val session = when (val outcome = WearLinkClient.hello(connection, hello)) {
                is WearLinkOutcome.Ok -> outcome.value
                else -> throw failureOf(outcome)
            }
            lastWatchName = session.peer.name
            // The bond's Classic address is the surest way to find this watch again.
            linkStore.setLinkAddress(registeredAddress, match.address)
            block(session)
        } finally {
            // The watch waits for this close; the watchdog is only a guard.
            watchdog.cancel()
            runCatching { socket.close() }
        }
    }

    private fun <T> unwrap(outcome: WearLinkOutcome<T>): T = when (outcome) {
        is WearLinkOutcome.Ok -> outcome.value
        else -> throw failureOf(outcome)
    }

    private fun failureOf(outcome: WearLinkOutcome<*>): WearOsLinkFailure = when (outcome) {
        is WearLinkOutcome.Ok -> error("not a failure")
        WearLinkOutcome.Pending -> WearOsLinkFailure.PendingConfirmation()
        is WearLinkOutcome.Unauthorized -> WearOsLinkFailure.Unauthorized(outcome.reason)
        is WearLinkOutcome.VersionMismatch -> WearOsLinkFailure.VersionMismatch(outcome.min, outcome.max)
        is WearLinkOutcome.Refused -> WearOsLinkFailure.Refused(outcome.code)
        is WearLinkOutcome.ProtocolError -> WearOsLinkFailure.Protocol(outcome.detail)
        WearLinkOutcome.Closed -> WearOsLinkFailure.NoAnswer()
    }

    @SuppressLint("MissingPermission")
    private fun localName(adapter: BluetoothAdapter): String =
        runCatching { adapter.name }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL

    companion object {
        private const val TAG = "BluetoothWearOsNodePort"

        /** Connect (paging plus service lookup), the hello and the ping together. */
        private const val PING_TIMEOUT_MS = 10_000L

        /** A full page over RFCOMM is well under a second; the connect dominates. */
        private const val PULL_TIMEOUT_MS = 30_000L

        /** One live connection: well inside the watch's 10-minute limit on a connection. */
        private const val LIVE_SESSION_MS = 8L * 60 * 1000
    }
}

private fun WearLinkWorkout.toPortWorkout() = WearOsWorkout(
    start = Instant.ofEpochMilli(startMillis),
    end = Instant.ofEpochMilli(endMillis),
    exerciseType = exerciseType,
    steps = steps,
    distanceMetres = distanceMetres,
    ascentMetres = ascentDecimetres?.let { it / 10.0 },
    pauses = pauses.map { Instant.ofEpochMilli(it.startMillis)..Instant.ofEpochMilli(it.endMillis) },
    route = route.map {
        WearOsWorkoutPoint(
            time = Instant.ofEpochMilli(it.epochMillis),
            latitude = it.latitudeE7 / 1e7,
            longitude = it.longitudeE7 / 1e7,
            altitudeMetres = it.altitudeDecimetres?.let { d -> d / 10.0 },
            accuracyMetres = it.accuracyDecimetres?.let { d -> d / 10.0 },
        )
    },
)
