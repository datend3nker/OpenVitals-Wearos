package tech.mmarca.openvitals.wearlink

import java.util.UUID

/**
 * The line protocol between OpenVitals on the phone and the OpenVitals Wear OS
 * app, over an RFCOMM socket per exchange. Every line is UTF-8, space-separated
 * fields, `\n`-terminated. The phone sends a request line; the watch answers
 * with zero or more data lines and one terminating line. The phone may send
 * the next request on the same connection (a live heart rate window, the
 * `PROFILE` and `TOTALS` after a sync), and closes it when done.
 *
 * One source for both apps: `:wearlink` is compiled into `:app` and `:wear`.
 *
 * Every connection opens with one hello from the phone and one reply from the
 * watch; requests follow only after `OK`:
 * - `HELLO <version> <caps> <token> <name...>` — the phone's protocol version,
 *   its capabilities (`hr,sm` or `-`), the secret it holds for this watch
 *   (`WearLinkToken`) and its Bluetooth name, the rest of the line.
 * - `OK <version> <caps> <name...>` — the watch knows that token for this phone.
 * - `PENDING` — the watch does not hold this token for this phone and is asking
 *   the wearer; the phone tries again later. That is a new phone, or a known
 *   one presenting another token (a reinstalled or reset app, or an impostor:
 *   the wearer tells them apart).
 * - `UNAUTHORIZED <mismatch|blocked>` — the phone is blocked. `mismatch` is
 *   what an older watch app answers to another token; the phone still reads it.
 * - `VERSION <min> <max>` — the phone's version is outside what the watch speaks.
 * - `ERROR <bad_hello|bad_request|busy|internal>` — the watch could not serve.
 *
 * Requests:
 * - `PING` → `PONG`. Liveness only.
 * - `HR_SINCE <epochMillis> <limit>` → up to `limit` `HR` lines, oldest first,
 *   each newer than `epochMillis` (exclusive), then `END <count> <more>`.
 * - `SM_SINCE <epochMillis> <limit>` → up to `limit` `SM` lines, oldest first,
 *   each newer than `epochMillis` (exclusive), then `END <count> <more>`.
 * - `LG_SINCE <epochMillis> <limit>` → up to `limit` `LG` lines, oldest first,
 *   each newer than `epochMillis` (exclusive), then `END <count> <more>`. The
 *   entries the wearer logged on the watch; only a watch announcing `lg`
 *   serves it.
 * - `HV_SINCE <epochMillis> <limit>` → up to `limit` `HV` lines, oldest first,
 *   each newer than `epochMillis` (exclusive), then `END <count> <more>`. The
 *   heart rate variability the watch measured at night; only a watch
 *   announcing `hv` serves it.
 * - `WO_SINCE <epochMillis> <limit>` → up to `limit` workouts, oldest first, each
 *   started after `epochMillis` (exclusive): its `WO` header, then its `WP`
 *   pauses and `WR` route points (see [WearLinkWorkout]), then one `END
 *   <count> <more>` that counts workouts. Only a watch announcing `wo` serves it.
 * - `HR_LIVE <seconds>` → for up to `seconds` (at most [MAX_LIVE_SECONDS],
 *   less than the watch's idle timeout), one `HR` line as each reading
 *   arrives, then `END <count> 0`. Only a watch announcing `hl` serves it; a
 *   watch off the wrist answers `END 0 0` at once. Nothing is stored for it.
 * - `PROFILE ...` and `TOTALS ...` → `END 0 0`. The phone's settings and
 *   Health Connect's totals for today, for the watch's tiles
 *   ([WearLinkProfile]). Only a watch announcing `pf` is sent them.
 *
 * Reply lines:
 * - `HR <epochMillis> <bpm>` — one heart rate sample.
 * - `SM <epochMillis> <kind> <offsetSeconds> <flags> <n> <mv10> <bpm|-> <hsd10|->
 *   <hn> <mx> <my> <mz> <sx> <sy> <sz> <zmin|-> <zmax|-> <zd10|->` — one sleep
 *   minute, the sleep pipeline's input on the phone, every field an integer
 *   and `-` meaning none. `docs/engineering/sleep-minute-features.md` defines
 *   each: `kind` is `R` (raw), `A` (awake) or `U` (unmeasurable); `flags` the
 *   bit field below; `n` the accelerometer samples; `mv10` the movement count
 *   times ten; `bpm`, `hsd10` and `hn` the minute's heart rate mean, standard
 *   deviation times ten and sample count; `mx..sz` the per-axis mean and
 *   standard deviation in milli-g; `zmin`, `zmax` the five-second z-angle
 *   extremes in degrees and `zd10` its mean change times ten.
 * - `LG <epochMillis> <KIND> <value> <value2|->` — one logged entry: the
 *   kind's unit is the metric one (`LogKind`), `value2` only for a blood
 *   pressure's diastolic. A kind this side does not know is skipped.
 * - `HV <epochMillis> <rmssd10> <beats>` — one night-time window: its start,
 *   the RMSSD in tenths of a millisecond and the clean beats it rests on.
 * - `END <count> <more>` — `count` data lines were sent; `more` is `1` when the
 *   limit cut the reply short and another request from the last line's time
 *   would return more.
 *
 * A line the receiver cannot parse is skipped, never fatal: an older peer may
 * send something newer.
 */
object WearLinkProtocol {

    /** Bumped when a change is not backward compatible; the hello negotiates it. */
    const val VERSION: Int = 4

    /** The oldest version this side still speaks. */
    const val MIN_VERSION: Int = 4

    /** The RFCOMM service record the watch listens on and the phone connects to. */
    val SERVICE_UUID: UUID = UUID.fromString("4838d728-6e5a-4b95-a29d-a60032338301")

    const val HELLO: String = "HELLO"
    const val OK: String = "OK"
    const val PENDING: String = "PENDING"
    const val UNAUTHORIZED: String = "UNAUTHORIZED"
    const val VERSION_WORD: String = "VERSION"
    const val ERROR: String = "ERROR"
    const val PING: String = "PING"
    const val PONG: String = "PONG"
    const val HEART_RATE_SINCE: String = "HR_SINCE"
    const val HEART_RATE: String = "HR"
    const val SLEEP_MINUTES_SINCE: String = "SM_SINCE"
    const val SLEEP_MINUTE: String = "SM"
    const val STEPS_SINCE: String = "ST_SINCE"
    const val STEP_MINUTE: String = "ST"
    const val ASCENT_SINCE: String = "AS_SINCE"
    const val ASCENT_MINUTE: String = "AS"
    const val HRV_SINCE: String = "HV_SINCE"
    const val HRV_WINDOW: String = "HV"
    const val WORKOUTS_SINCE: String = "WO_SINCE"
    const val HEART_RATE_LIVE: String = "HR_LIVE"
    const val LOG_SINCE: String = "LG_SINCE"
    const val LOG_ENTRY: String = "LG"
    const val SET_PREFERENCE: String = "SET_PREF"
    const val PREFERENCE_OK: String = "PREF_OK"
    const val PUT_DAY: String = "PUT_DAY"
    const val DAY_OK: String = "DAY_OK"

    /** Asks the watch for every preference it holds; the reply is one [PREFERENCE] line each, then `END`. */
    const val GET_PREFERENCES: String = "GET_PREF"
    const val PREFERENCE: String = "PREF"
    const val PROFILE: String = "PROFILE"
    const val TOTALS: String = "TOTALS"
    const val END: String = "END"

    /** Longer than this a line is a protocol violation; the reader closes rather than buffers. */
    const val MAX_LINE_LENGTH: Int = 512

    /** A device name longer than this is clipped on the wire. */
    const val MAX_NAME_LENGTH: Int = 48

    const val CAP_HEART_RATE: String = "hr"
    const val CAP_SLEEP_MINUTES: String = "sm"
    const val CAP_STEPS: String = "st"
    const val CAP_ASCENT: String = "as"
    const val CAP_LIVE_HEART_RATE: String = "hl"

    /** The watch measures heart rate variability at night and serves `HV_SINCE`. */
    const val CAP_HRV: String = "hv"

    /** The watch records workouts and serves `WO_SINCE`. */
    const val CAP_WORKOUTS: String = "wo"

    /** The watch serves `LG_SINCE`: the entries the wearer logged on it. */
    const val CAP_LOG: String = "lg"

    /** The watch accepts `SET_PREF`: the phone is its settings screen. */
    const val CAP_PREFERENCES: String = "pref"

    /** The watch accepts `PUT_DAY`: the phone sends it a day's value the watch cannot know. */
    const val CAP_DAY_VALUES: String = "dv"
    const val CAP_PROFILE: String = "pf"
    const val NO_CAPABILITIES: String = "-"

    /** The days of values the phone sends are the last seven; a day further back is refused. */
    const val DAY_VALUE_DAYS: Int = 7

    /**
     * What a `PUT_DAY` carries. The value is in tenths of the unit: kilograms
     * for [WEIGHT], millilitres for [HYDRATION].
     */
    enum class DayKind(val word: String) {
        WEIGHT("WEIGHT"),
        HYDRATION("HYDRATION"),
        ;

        companion object {
            fun fromWord(word: String): DayKind? = entries.firstOrNull { it.word == word }
        }
    }

    /**
     * Preference keys the phone may push with `SET_PREF`. Opaque strings on
     * the wire, like capabilities; the watch decides what an unknown key or
     * value does (it refuses with `ERROR bad_request`).
     */
    object Preferences {
        /** Whether the watch records sleep minutes. Values [OFF] / [ON]. */
        const val SLEEP_RECORDING: String = "sleep"

        /** Whether the watch measures heart rate. Values [OFF] / [ON]; off also withdraws the `hr` capability. */
        const val HEART_RATE: String = "heart"

        /** Whether the watch counts steps. Values [OFF] / [ON]; off withdraws the `st` capability. */
        const val STEPS: String = "steps"

        /** Whether the watch records height climbed. Values [OFF] / [ON]; off withdraws the `as` capability. */
        const val ASCENT: String = "ascent"

        /** The heart rate interval by day. Values are [HeartRateInterval.wire]. */
        const val HEART_RATE_DAY: String = "hr_day"

        /** The heart rate interval in bedtime mode, when sleep recording is off. Values are [HeartRateInterval.wire]. */
        const val HEART_RATE_NIGHT: String = "hr_night"

        /** The wearer's weight in tenths of a kilogram, for the watch's calorie estimate. Not sent when unknown. */
        const val BODY_WEIGHT: String = "body_weight"

        /** The wearer's step length in centimetres, for the watch's distance estimate. */
        const val STRIDE_CM: String = "stride_cm"

        /**
         * Whether the watch and the phone keep debug files the wearer pulls with `adb`. Values [OFF] / [ON];
         * off by default, unlike the other switches. See `DebugTraceFile`.
         */
        const val DEBUG_DATA: String = "debug_data"

        const val OFF: String = "0"
        const val ON: String = "1"
    }

    /** A preference key or value: the capability charset, so one line parses the same way. */
    private fun isPreferenceToken(token: String): Boolean =
        token.isNotEmpty() && token.all { it.isLetterOrDigit() || it == '_' }

    /** The phone's opening line. */
    data class Hello(val version: Int, val capabilities: Set<String>, val token: String, val name: String)

    enum class UnauthorizedReason(val word: String) {
        MISMATCH("mismatch"),
        BLOCKED("blocked"),
        ;

        companion object {
            fun fromWord(word: String): UnauthorizedReason? = entries.firstOrNull { it.word == word }
        }
    }

    enum class ErrorCode(val word: String) {
        BAD_HELLO("bad_hello"),
        BAD_REQUEST("bad_request"),
        BUSY("busy"),
        INTERNAL("internal"),
        ;

        companion object {
            fun fromWord(word: String): ErrorCode? = entries.firstOrNull { it.word == word }
        }
    }

    /** The watch's answer to a hello. */
    sealed class HelloReply {
        data class Ok(val version: Int, val capabilities: Set<String>, val name: String) : HelloReply()
        data object Pending : HelloReply()
        data class Unauthorized(val reason: UnauthorizedReason) : HelloReply()
        data class VersionMismatch(val min: Int, val max: Int) : HelloReply()
        data class Error(val code: ErrorCode) : HelloReply()
    }

    /** A request after the hello. */
    sealed class Request {
        data object Ping : Request()
        data class HeartRateSince(val sinceEpochMillis: Long, val limit: Int) : Request()
        data class SleepMinutesSince(val sinceEpochMillis: Long, val limit: Int) : Request()
        data class StepsSince(val sinceEpochMillis: Long, val limit: Int) : Request()
        data class AscentSince(val sinceEpochMillis: Long, val limit: Int) : Request()
        data class LogSince(val sinceEpochMillis: Long, val limit: Int) : Request()
        data class HrvSince(val sinceEpochMillis: Long, val limit: Int) : Request()
        data class WorkoutsSince(val sinceEpochMillis: Long, val limit: Int) : Request()
        data class LiveHeartRate(val seconds: Int) : Request()
        data class SetPreference(val key: String, val value: String) : Request()
        data class PutDay(val kind: DayKind, val epochDay: Long, val tenths: Int) : Request()
        data object GetPreferences : Request()
        data class Profile(val profile: WearLinkProfile.Profile) : Request()
        data class Totals(val totals: WearLinkProfile.Totals) : Request()
    }

    fun formatHello(hello: Hello): String =
        "$HELLO ${hello.version} ${formatCapabilities(hello.capabilities)} ${hello.token} ${cleanName(hello.name)}"

    /** Null unless [line] is a well-formed hello with a well-formed token. */
    fun parseHello(line: String): Hello? {
        val fields = fields(line)
        if (fields.size < 4 || fields[0] != HELLO) return null
        val version = fields[1].toIntOrNull() ?: return null
        val capabilities = parseCapabilities(fields[2]) ?: return null
        val token = fields[3]
        if (version < 1 || !WearLinkToken.isWellFormed(token)) return null
        return Hello(version, capabilities, token, cleanName(fields.drop(4).joinToString(" ")))
    }

    fun formatHelloReply(reply: HelloReply): String = when (reply) {
        is HelloReply.Ok -> "$OK ${reply.version} ${formatCapabilities(reply.capabilities)} ${cleanName(reply.name)}"
        HelloReply.Pending -> PENDING
        is HelloReply.Unauthorized -> "$UNAUTHORIZED ${reply.reason.word}"
        is HelloReply.VersionMismatch -> "$VERSION_WORD ${reply.min} ${reply.max}"
        is HelloReply.Error -> "$ERROR ${reply.code.word}"
    }

    /** Null unless [line] is a well-formed hello reply. */
    fun parseHelloReply(line: String): HelloReply? {
        val fields = fields(line)
        if (fields.isEmpty()) return null
        return when (fields[0]) {
            OK -> {
                if (fields.size < 3) return null
                val version = fields[1].toIntOrNull() ?: return null
                val capabilities = parseCapabilities(fields[2]) ?: return null
                HelloReply.Ok(version, capabilities, cleanName(fields.drop(3).joinToString(" ")))
            }
            PENDING -> if (fields.size == 1) HelloReply.Pending else null
            UNAUTHORIZED -> if (fields.size == 2) UnauthorizedReason.fromWord(fields[1])?.let(HelloReply::Unauthorized) else null
            VERSION_WORD -> {
                if (fields.size != 3) return null
                val min = fields[1].toIntOrNull() ?: return null
                val max = fields[2].toIntOrNull() ?: return null
                if (min < 1 || max < min) null else HelloReply.VersionMismatch(min, max)
            }
            ERROR -> if (fields.size == 2) ErrorCode.fromWord(fields[1])?.let(HelloReply::Error) else null
            else -> null
        }
    }

    fun formatError(code: ErrorCode): String = "$ERROR ${code.word}"

    fun formatRequest(request: Request): String = when (request) {
        Request.Ping -> PING
        is Request.HeartRateSince -> formatHeartRateRequest(request.sinceEpochMillis, request.limit)
        is Request.SleepMinutesSince -> formatSleepMinutesRequest(request.sinceEpochMillis, request.limit)
        is Request.StepsSince -> formatStepsRequest(request.sinceEpochMillis, request.limit)
        is Request.AscentSince -> formatAscentRequest(request.sinceEpochMillis, request.limit)
        is Request.LogSince -> formatLogRequest(request.sinceEpochMillis, request.limit)
        is Request.HrvSince -> formatHrvRequest(request.sinceEpochMillis, request.limit)
        is Request.WorkoutsSince -> "$WORKOUTS_SINCE ${request.sinceEpochMillis} ${request.limit.coerceIn(1, MAX_WORKOUTS_PER_REQUEST)}"
        is Request.LiveHeartRate -> "$HEART_RATE_LIVE ${request.seconds.coerceIn(1, MAX_LIVE_SECONDS)}"
        is Request.SetPreference -> "$SET_PREFERENCE ${request.key} ${request.value}"
        is Request.PutDay -> "$PUT_DAY ${request.kind.word} ${request.epochDay} ${request.tenths}"
        Request.GetPreferences -> GET_PREFERENCES
        is Request.Profile -> WearLinkProfile.formatProfile(request.profile)
        is Request.Totals -> WearLinkProfile.formatTotals(request.totals)
    }

    /** Null unless [line] is a well-formed request. */
    fun parseRequest(line: String): Request? {
        if (isPing(line)) return Request.Ping
        parseHeartRateRequest(line)?.let { return Request.HeartRateSince(it.sinceEpochMillis, it.limit) }
        parseSleepMinutesRequest(line)?.let { return Request.SleepMinutesSince(it.sinceEpochMillis, it.limit) }
        parseSinceRequest(line, STEPS_SINCE, MAX_STEP_MINUTES_PER_REQUEST)?.let { (since, limit) ->
            return Request.StepsSince(since, limit)
        }
        parseSinceRequest(line, ASCENT_SINCE, MAX_ASCENT_MINUTES_PER_REQUEST)?.let { (since, limit) ->
            return Request.AscentSince(since, limit)
        }
        parseSinceRequest(line, LOG_SINCE, MAX_LOG_ENTRIES_PER_REQUEST)?.let { (since, limit) ->
            return Request.LogSince(since, limit)
        }
        parseSinceRequest(line, HRV_SINCE, MAX_HRV_WINDOWS_PER_REQUEST)?.let { (since, limit) ->
            return Request.HrvSince(since, limit)
        }
        parseSinceRequest(line, WORKOUTS_SINCE, MAX_WORKOUTS_PER_REQUEST)?.let { (since, limit) ->
            return Request.WorkoutsSince(since, limit)
        }
        parseLiveRequest(line)?.let { return Request.LiveHeartRate(it) }
        parsePreferenceRequest(line)?.let { return it }
        parsePutDayRequest(line)?.let { return it }
        if (fields(line) == listOf(GET_PREFERENCES)) return Request.GetPreferences
        WearLinkProfile.parseProfile(line)?.let { return Request.Profile(it) }
        WearLinkProfile.parseTotals(line)?.let { return Request.Totals(it) }
        return null
    }

    private fun parsePutDayRequest(line: String): Request.PutDay? {
        val fields = fields(line)
        if (fields.size != 4 || fields[0] != PUT_DAY) return null
        val kind = DayKind.fromWord(fields[1]) ?: return null
        val epochDay = fields[2].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val tenths = fields[3].toIntOrNull()?.takeIf { it > 0 } ?: return null
        return Request.PutDay(kind, epochDay, tenths)
    }

    /** One preference the watch holds, as the reply to [GET_PREFERENCES] carries it. */
    fun formatPreference(key: String, value: String): String = "$PREFERENCE $key $value"

    /** Null unless [line] is a well-formed `PREF` line; the key and the value are the same tokens `SET_PREF` takes. */
    fun parsePreference(line: String): Pair<String, String>? {
        val fields = fields(line)
        if (fields.size != 3 || fields[0] != PREFERENCE) return null
        if (!isPreferenceToken(fields[1]) || !isPreferenceToken(fields[2])) return null
        return fields[1] to fields[2]
    }

    private fun parsePreferenceRequest(line: String): Request.SetPreference? {
        val fields = fields(line)
        if (fields.size != 3 || fields[0] != SET_PREFERENCE) return null
        if (!isPreferenceToken(fields[1]) || !isPreferenceToken(fields[2])) return null
        return Request.SetPreference(fields[1], fields[2])
    }

    private fun parseLiveRequest(line: String): Int? {
        val fields = fields(line)
        if (fields.size != 2 || fields[0] != HEART_RATE_LIVE) return null
        val seconds = fields[1].toIntOrNull() ?: return null
        return if (seconds < 1) null else seconds.coerceAtMost(MAX_LIVE_SECONDS)
    }

    private fun formatCapabilities(capabilities: Set<String>): String =
        if (capabilities.isEmpty()) NO_CAPABILITIES else capabilities.sorted().joinToString(",")

    private fun parseCapabilities(field: String): Set<String>? {
        if (field == NO_CAPABILITIES) return emptySet()
        val words = field.split(',')
        if (words.any { word -> word.isEmpty() || word.any { !it.isLetterOrDigit() && it != '_' } }) return null
        return words.toSet()
    }

    /** Printable characters only, every run of whitespace or control characters one space, at most [MAX_NAME_LENGTH]. */
    fun cleanName(name: String): String =
        name.map { if (it.isWhitespace() || it.code < 0x20 || it.code == 0x7f) ' ' else it }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .take(MAX_NAME_LENGTH)

    /** The most samples one `HR_SINCE` reply carries. The phone pages past it. */
    const val MAX_SAMPLES_PER_REQUEST: Int = 2000

    /** The most minutes one `SM_SINCE` reply carries: a day and a half. The phone pages past it. */
    const val MAX_MINUTES_PER_REQUEST: Int = 2000

    /** The most step minutes one `ST_SINCE` reply carries. The phone pages past it. */
    const val MAX_STEP_MINUTES_PER_REQUEST: Int = 2000

    /** The longest one `HR_LIVE` runs: below the watch's 15-second idle timeout. The phone asks again. */
    const val MAX_LIVE_SECONDS: Int = 10

    /** The most ascent minutes one `AS_SINCE` reply carries. The phone pages past it. */
    const val MAX_ASCENT_MINUTES_PER_REQUEST: Int = 2000

    /** The most HRV windows one `HV_SINCE` reply carries: a week at 2 a night is far below it. */
    const val MAX_HRV_WINDOWS_PER_REQUEST: Int = 500

    /** The most workouts one `WO_SINCE` reply carries; each brings its own route, so the phone pages by workout. */
    const val MAX_WORKOUTS_PER_REQUEST: Int = 5

    /** The most logged entries one `LG_SINCE` reply carries. The watch keeps 500 at most, so one page is the whole log. */
    const val MAX_LOG_ENTRIES_PER_REQUEST: Int = 500

    /**
     * What the wearer logged on the watch, by kind. The names are the watch's
     * own `EntryType` names, so a kind reads the same on the wire and in the
     * watch's storage; the watch maps each of its kinds to one of these by an
     * exhaustive `when`. Every value is metric: the unit is named beside each.
     */
    enum class LogKind {
        /** Millilitres. */
        WATER,

        /** Kilograms. */
        WEIGHT,

        /** Systolic mmHg; [LogEntry.value2] is the diastolic. */
        BLOOD_PRESSURE,

        /** Minutes of a breathing session, logged when it ends. */
        MINDFULNESS,

        /** Beats per minute from a spot measurement. */
        HEART_RATE,

        /** Milliseconds RMSSD from a spot measurement. */
        HRV,

        /** Percent of body mass. */
        BODY_FAT,

        /** Percent of haemoglobin carrying oxygen. */
        BLOOD_OXYGEN,

        /** Breaths per minute. */
        RESPIRATORY_RATE,

        /** Degrees Celsius. */
        BODY_TEMPERATURE,

        /** Millimoles per litre. */
        BLOOD_GLUCOSE,

        /** Centimetres. */
        HEIGHT,

        /** Milligrams of caffeine in one drink. */
        CAFFEINE,
        ;

        companion object {
            fun fromWord(word: String): LogKind? = entries.firstOrNull { it.name == word }
        }
    }

    /** One logged entry. [value2] is the diastolic of a blood pressure, else null; see [LogKind] for the units. */
    data class LogEntry(val epochMillis: Long, val kind: LogKind, val value: Double, val value2: Double?)

    /**
     * One night-time heart rate variability window, keyed on its start: the
     * RMSSD in tenths of a millisecond (integers on the wire) and the clean
     * beats it rests on.
     */
    data class HrvWindow(val epochMillis: Long, val rmssd10: Int, val beats: Int) {
        val rmssdMillis: Double get() = rmssd10 / 10.0
    }

    data class HeartRateRequest(val sinceEpochMillis: Long, val limit: Int)

    data class HeartRateSample(val epochMillis: Long, val bpm: Int)

    data class SleepMinutesRequest(val sinceEpochMillis: Long, val limit: Int)

    /**
     * The steps the watch counted in one clock minute, keyed on the minute's
     * start. The watch rewrites a minute while late steps arrive. The count
     * only grows.
     */
    data class StepMinute(val epochMillis: Long, val steps: Int)

    /**
     * The height the watch's barometer climbed in one clock minute, in
     * decimetres, keyed on the minute's start. Elevators count too; the
     * phone keeps only minutes with steps and derives the floors from the
     * metres. The watch rewrites a minute while late readings arrive. The
     * count only grows.
     */
    data class AscentMinute(val epochMillis: Long, val decimetres: Int)

    /** What the watch said about one minute. The code is what goes on the wire. */
    enum class MinuteKind(val code: String) {
        RAW("R"),
        AWAKE("A"),
        UNMEASURABLE("U"),
        ;

        companion object {
            fun fromCode(code: String): MinuteKind? = entries.firstOrNull { it.code == code }
        }
    }

    /** Bits of the `flags` field. */
    const val FLAG_CHARGING: Int = 1
    const val FLAG_OFF_BODY: Int = 2
    const val FLAG_SCREEN_ON: Int = 4
    const val FLAG_HR_NO_CONTACT: Int = 8
    const val FLAG_HR_RECORDING: Int = 16
    const val FLAG_SPARSE: Int = 32

    /**
     * One minute of sleep input, as the wire carries it: integers, with the
     * tenths kept in the name. Null is `-`. [meanMilliG] and [sdMilliG] hold
     * x, y, z.
     */
    data class SleepMinute(
        val epochMillis: Long,
        val kind: MinuteKind,
        val offsetSeconds: Int,
        val flags: Int,
        val sampleCount: Int,
        val movement10: Int,
        val bpm: Int?,
        val heartRateSd10: Int?,
        val heartRateSamples: Int,
        val meanMilliG: IntArray,
        val sdMilliG: IntArray,
        val zAngleMin: Int?,
        val zAngleMax: Int?,
        val zAngleDelta10: Int?,
    ) {
        val movement: Float get() = movement10 / 10f
        val heartRateSd: Float? get() = heartRateSd10?.let { it / 10f }
        val zAngleDelta: Float? get() = zAngleDelta10?.let { it / 10f }

        fun hasFlag(flag: Int): Boolean = flags and flag != 0

        override fun equals(other: Any?): Boolean = other is SleepMinute && formatSleepMinute(this) == formatSleepMinute(other)

        override fun hashCode(): Int = formatSleepMinute(this).hashCode()
    }

    data class End(val count: Int, val more: Boolean)

    fun formatHeartRateRequest(sinceEpochMillis: Long, limit: Int): String =
        "$HEART_RATE_SINCE $sinceEpochMillis ${limit.coerceIn(1, MAX_SAMPLES_PER_REQUEST)}"

    /** Null unless [line] is a well-formed `HR_SINCE` request. The limit is clamped. */
    fun parseHeartRateRequest(line: String): HeartRateRequest? =
        parseSinceRequest(line, HEART_RATE_SINCE, MAX_SAMPLES_PER_REQUEST)?.let { (since, limit) ->
            HeartRateRequest(since, limit)
        }

    fun formatSleepMinutesRequest(sinceEpochMillis: Long, limit: Int): String =
        "$SLEEP_MINUTES_SINCE $sinceEpochMillis ${limit.coerceIn(1, MAX_MINUTES_PER_REQUEST)}"

    /** Null unless [line] is a well-formed `SM_SINCE` request. The limit is clamped. */
    fun parseSleepMinutesRequest(line: String): SleepMinutesRequest? =
        parseSinceRequest(line, SLEEP_MINUTES_SINCE, MAX_MINUTES_PER_REQUEST)?.let { (since, limit) ->
            SleepMinutesRequest(since, limit)
        }

    fun formatStepsRequest(sinceEpochMillis: Long, limit: Int): String =
        "$STEPS_SINCE $sinceEpochMillis ${limit.coerceIn(1, MAX_STEP_MINUTES_PER_REQUEST)}"

    fun formatStepMinute(minute: StepMinute): String = "$STEP_MINUTE ${minute.epochMillis} ${minute.steps}"

    /** Null unless [line] is a well-formed `ST` line: a minute start and a plausible count. */
    fun parseStepMinute(line: String): StepMinute? {
        val fields = fields(line)
        if (fields.size != 3 || fields[0] != STEP_MINUTE) return null
        val at = fields[1].toLongOrNull() ?: return null
        val steps = fields[2].toIntOrNull() ?: return null
        if (at <= 0 || at % MINUTE_MILLIS != 0L || steps !in 1..MAX_STEPS_PER_MINUTE) return null
        return StepMinute(at, steps)
    }

    fun formatAscentRequest(sinceEpochMillis: Long, limit: Int): String =
        "$ASCENT_SINCE $sinceEpochMillis ${limit.coerceIn(1, MAX_ASCENT_MINUTES_PER_REQUEST)}"

    fun formatAscentMinute(minute: AscentMinute): String = "$ASCENT_MINUTE ${minute.epochMillis} ${minute.decimetres}"

    /** Null unless [line] is a well-formed `AS` line: a minute start and a plausible ascent. */
    fun parseAscentMinute(line: String): AscentMinute? {
        val fields = fields(line)
        if (fields.size != 3 || fields[0] != ASCENT_MINUTE) return null
        val at = fields[1].toLongOrNull() ?: return null
        val decimetres = fields[2].toIntOrNull() ?: return null
        if (at <= 0 || at % MINUTE_MILLIS != 0L || decimetres !in 1..MAX_ASCENT_DECIMETRES_PER_MINUTE) return null
        return AscentMinute(at, decimetres)
    }

    fun formatHrvRequest(sinceEpochMillis: Long, limit: Int): String =
        "$HRV_SINCE $sinceEpochMillis ${limit.coerceIn(1, MAX_HRV_WINDOWS_PER_REQUEST)}"

    fun formatHrvWindow(window: HrvWindow): String = "$HRV_WINDOW ${window.epochMillis} ${window.rmssd10} ${window.beats}"

    /** Null unless [line] is a well-formed `HV` line: a time, a plausible RMSSD and a plausible beat count. */
    fun parseHrvWindow(line: String): HrvWindow? {
        val fields = fields(line)
        if (fields.size != 4 || fields[0] != HRV_WINDOW) return null
        val at = fields[1].toLongOrNull() ?: return null
        val rmssd10 = fields[2].toIntOrNull() ?: return null
        val beats = fields[3].toIntOrNull() ?: return null
        if (at <= 0 || rmssd10 !in MIN_RMSSD10..MAX_RMSSD10 || beats !in 1..MAX_HRV_BEATS) return null
        return HrvWindow(at, rmssd10, beats)
    }

    fun formatLogRequest(sinceEpochMillis: Long, limit: Int): String =
        "$LOG_SINCE $sinceEpochMillis ${limit.coerceIn(1, MAX_LOG_ENTRIES_PER_REQUEST)}"

    /**
     * `LG <epochMillis> <KIND> <value> <value2|->`. The value is written as
     * Kotlin prints a double, which parses back to the same double, so the
     * phone's record id from it is the same on every sync.
     */
    fun formatLogEntry(entry: LogEntry): String =
        "$LOG_ENTRY ${entry.epochMillis} ${entry.kind.name} ${entry.value} ${entry.value2 ?: NO_VALUE}"

    /** Null unless [line] is a well-formed `LG` line: a time, a kind this side knows, and finite values. */
    fun parseLogEntry(line: String): LogEntry? {
        val fields = fields(line)
        if (fields.size != 5 || fields[0] != LOG_ENTRY) return null
        val at = fields[1].toLongOrNull() ?: return null
        val kind = LogKind.fromWord(fields[2]) ?: return null
        val value = fields[3].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        val value2 = if (fields[4] == NO_VALUE) {
            null
        } else {
            fields[4].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
        }
        if (at <= 0) return null
        return LogEntry(at, kind, value, value2)
    }

    fun formatSample(epochMillis: Long, bpm: Int): String = "$HEART_RATE $epochMillis $bpm"

    /** Null unless [line] is a well-formed `HR` line with a plausible rate. */
    fun parseSample(line: String): HeartRateSample? {
        val fields = fields(line)
        if (fields.size != 3 || fields[0] != HEART_RATE) return null
        val at = fields[1].toLongOrNull() ?: return null
        val bpm = fields[2].toIntOrNull() ?: return null
        if (at <= 0 || bpm !in MIN_BPM..MAX_BPM) return null
        return HeartRateSample(at, bpm)
    }

    fun formatSleepMinute(m: SleepMinute): String = buildString(96) {
        append(SLEEP_MINUTE).append(' ').append(m.epochMillis).append(' ').append(m.kind.code).append(' ')
        append(m.offsetSeconds).append(' ').append(m.flags).append(' ').append(m.sampleCount).append(' ')
        append(m.movement10).append(' ').append(m.bpm ?: NO_VALUE).append(' ').append(m.heartRateSd10 ?: NO_VALUE).append(' ')
        append(m.heartRateSamples)
        for (axis in 0 until 3) append(' ').append(m.meanMilliG[axis])
        for (axis in 0 until 3) append(' ').append(m.sdMilliG[axis])
        append(' ').append(m.zAngleMin ?: NO_VALUE).append(' ').append(m.zAngleMax ?: NO_VALUE)
        append(' ').append(m.zAngleDelta10 ?: NO_VALUE)
    }

    /** Null unless [line] is a well-formed `SM` line. A rate outside the plausible range reads as none. */
    fun parseSleepMinute(line: String): SleepMinute? {
        val f = fields(line)
        if (f.size != SLEEP_MINUTE_FIELDS || f[0] != SLEEP_MINUTE) return null
        val at = f[1].toLongOrNull() ?: return null
        val kind = MinuteKind.fromCode(f[2]) ?: return null
        val offset = f[3].toIntOrNull() ?: return null
        val flags = f[4].toIntOrNull() ?: return null
        val n = f[5].toIntOrNull() ?: return null
        val movement10 = f[6].toIntOrNull() ?: return null
        val bpm = optional(f[7]) ?: return null
        val heartRateSd10 = optional(f[8]) ?: return null
        val hn = f[9].toIntOrNull() ?: return null
        val mean = IntArray(3)
        val sd = IntArray(3)
        for (axis in 0 until 3) {
            mean[axis] = f[10 + axis].toIntOrNull() ?: return null
            sd[axis] = f[13 + axis].toIntOrNull() ?: return null
        }
        val zMin = optional(f[16]) ?: return null
        val zMax = optional(f[17]) ?: return null
        val zDelta10 = optional(f[18]) ?: return null
        if (at <= 0 || offset !in -MAX_OFFSET_SECONDS..MAX_OFFSET_SECONDS || flags < 0 || n < 0 || movement10 < 0 || hn < 0) return null
        return SleepMinute(
            epochMillis = at,
            kind = kind,
            offsetSeconds = offset,
            flags = flags,
            sampleCount = n,
            movement10 = movement10,
            bpm = bpm.value?.takeIf { it in MIN_BPM..MAX_BPM },
            heartRateSd10 = heartRateSd10.value,
            heartRateSamples = hn,
            meanMilliG = mean,
            sdMilliG = sd,
            zAngleMin = zMin.value,
            zAngleMax = zMax.value,
            zAngleDelta10 = zDelta10.value,
        )
    }

    /** An integer or `-`. Null for anything else, so the caller can reject the line. */
    private class Optional(val value: Int?)

    private fun optional(field: String): Optional? =
        if (field == NO_VALUE) Optional(null) else field.toIntOrNull()?.let { Optional(it) }

    private const val SLEEP_MINUTE_FIELDS = 19

    fun formatEnd(count: Int, more: Boolean): String = "$END $count ${if (more) 1 else 0}"

    /** Null unless [line] is a well-formed `END` line. */
    fun parseEnd(line: String): End? {
        val fields = fields(line)
        if (fields.size != 3 || fields[0] != END) return null
        val count = fields[1].toIntOrNull() ?: return null
        val more = when (fields[2]) {
            "0" -> false
            "1" -> true
            else -> return null
        }
        if (count < 0) return null
        return End(count, more)
    }

    /** True when [line] is the ping request. */
    fun isPing(line: String): Boolean = line.trim() == PING

    private fun parseSinceRequest(line: String, word: String, maxLimit: Int): Pair<Long, Int>? {
        val fields = fields(line)
        if (fields.size != 3 || fields[0] != word) return null
        val since = fields[1].toLongOrNull() ?: return null
        val limit = fields[2].toIntOrNull() ?: return null
        if (since < 0 || limit < 1) return null
        return since to limit.coerceAtMost(maxLimit)
    }

    private fun fields(line: String): List<String> = line.trim().split(' ').filter { it.isNotEmpty() }

    private const val NO_VALUE = "-"

    /** Eighteen hours: the widest UTC offset there is. */
    private const val MAX_OFFSET_SECONDS = 18 * 3600

    private const val MINUTE_MILLIS = 60_000L

    /** Sprinting is about 200 steps a minute. More means the counter jumped. */
    const val MAX_STEPS_PER_MINUTE: Int = 400

    /** A fast elevator climbs about 600 m a minute. More means the barometer jumped. */
    const val MAX_ASCENT_DECIMETRES_PER_MINUTE: Int = 9000

    /** An RMSSD of 0.1 to 200 ms, in tenths (Health Connect's range). Outside it the detector failed, not a heart. */
    const val MIN_RMSSD10: Int = 1
    const val MAX_RMSSD10: Int = 2000

    /** A window of a few minutes holds under a thousand beats. */
    const val MAX_HRV_BEATS: Int = 1000

    /** Outside this range the sensor is reporting noise, not a heart. */
    const val MIN_BPM: Int = 25
    const val MAX_BPM: Int = 250
}
