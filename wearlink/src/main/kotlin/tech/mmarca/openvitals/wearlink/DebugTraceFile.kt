package tech.mmarca.openvitals.wearlink

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The text files of the debug switch, on the watch and on the phone: one
 * file per local day, named `<prefix>-YYYYMMDD.log`, in [folder].
 *
 * A line is `epochMillis;source;event;key=value;...`. Each file stops at
 * [maxBytes]; only the newest [maxDays] files are kept. The files hold
 * values on purpose, because the wearer switches them on and pulls them
 * with `adb` himself. They must never be attached to a report.
 *
 * Plain Kotlin on the JDK, so the watch and the phone write the same
 * format. [append] is synchronized.
 */
class DebugTraceFile(
    private val folder: File,
    private val prefix: String,
    private val maxDays: Int = MAX_DAYS,
    private val maxBytes: Long = MAX_BYTES,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {

    /** Adds one line to the file of the day [epochMillis] falls on. A full file takes nothing more. */
    @Synchronized
    fun append(epochMillis: Long, line: String) {
        runCatching {
            folder.mkdirs()
            val file = File(folder, nameOf(epochMillis))
            val isNew = !file.exists()
            if (!isNew && file.length() >= maxBytes) return
            file.appendText(line + "\n")
            if (isNew) dropOld()
        }
    }

    /** The files of this writer, oldest first. */
    fun files(): List<File> =
        folder.listFiles { file -> NAME.matches(file.name) && file.name.startsWith("$prefix-") }
            ?.sortedBy { it.name }
            .orEmpty()

    private fun dropOld() {
        val all = files()
        for (old in all.take((all.size - maxDays).coerceAtLeast(0))) old.delete()
    }

    private fun nameOf(epochMillis: Long): String =
        "$prefix-${DAY.format(Instant.ofEpochMilli(epochMillis).atZone(zone()))}.log"

    companion object {
        const val MAX_DAYS = 7
        const val MAX_BYTES = 5L * 1024 * 1024
        private val DAY = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val NAME = Regex("[a-z-]+-\\d{8}\\.log")

        /**
         * One line: the time, the source and the event, then `key=value` pairs. A `;`, `=` or line
         * break inside a value would break the line, so each becomes a space.
         */
        fun line(epochMillis: Long, source: String, event: String, fields: List<Pair<String, Any?>> = emptyList()): String =
            buildString {
                append(epochMillis).append(';').append(clean(source)).append(';').append(clean(event))
                for ((key, value) in fields) append(';').append(clean(key)).append('=').append(clean(value.toString()))
            }

        private fun clean(text: String): String = text.map { if (it == ';' || it == '=' || it == '\n' || it == '\r') ' ' else it }.joinToString("")
    }
}
