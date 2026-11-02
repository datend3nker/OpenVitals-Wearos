package tech.mmarca.openvitals.wearlink

/**
 * How often the watch measures heart rate in one part of the day. [wire] is
 * the value a `SET_PREF` carries and the watch stores; [seconds] is the
 * spacing of the stored samples, 0 for off.
 *
 * Shared by both apps, so the phone's choices and the watch's behaviour are
 * one list.
 */
enum class HeartRateInterval(val wire: String, val seconds: Int) {
    OFF("0", 0),
    EVERY_10_SECONDS("10", 10),
    EVERY_MINUTE("60", 60),
    ;

    companion object {
        /** Null for a value this build does not know. */
        fun fromWire(value: String): HeartRateInterval? = entries.firstOrNull { it.wire == value }
    }
}
