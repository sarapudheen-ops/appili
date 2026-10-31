package com.innova.diag.domain.session

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Supported export shapes. */
enum class SessionExportFormat(val fileExtension: String, val mimeType: String) {
    JSON("json", "application/json"),
    CSV("csv", "text/csv"),
}

/**
 * Removes things a log should not carry off the device.
 *
 * A diagnostic log is meant to be shareable with a technician, and a VIN identifies a specific
 * vehicle. Bluetooth hardware addresses identify a user's phone-peripheral pairing. Both are
 * redacted; the *shape* of the exchange — which PID, which DTC, which failure token — survives, so
 * the log stays useful.
 *
 * Redaction covers the VIN as text and as the ASCII-hex form an adapter actually prints
 * (`49 02 01 …` carries the VIN as hex), which is why the sanitizer wants the VIN itself, not only a
 * pattern.
 */
class SessionSanitizer(knownVin: String? = null) {

    private val literals: List<String> = buildList {
        val vin = knownVin?.trim().orEmpty()
        if (vin.isNotEmpty()) {
            add(vin)
            add(hexOf(vin))
        }
    }.filter { it.isNotEmpty() }

    /** Returns [text] with vehicle-identifying values replaced by placeholders. */
    fun sanitize(text: String): String {
        var result = text
        for (literal in literals) {
            result = result.replace(literal, VIN_PLACEHOLDER, ignoreCase = true)
        }
        result = VIN_PATTERN.replace(result, VIN_PLACEHOLDER)
        result = MAC_PATTERN.replace(result, MAC_PLACEHOLDER)
        return result
    }

    companion object {
        const val VIN_PLACEHOLDER: String = "[VIN REDACTED]"
        const val MAC_PLACEHOLDER: String = "[MAC REDACTED]"

        /**
         * A 17-character VIN: SAE J1979 excludes `I`, `O` and `Q`. Word boundaries keep this from
         * chewing a bite out of a longer hex payload, where it is not a VIN at all.
         */
        private val VIN_PATTERN: Regex = Regex("\\b[A-HJ-NPR-Z0-9]{17}\\b", RegexOption.IGNORE_CASE)

        /** `AA:BB:CC:DD:EE:FF` or `AA-BB-CC-DD-EE-FF`. */
        private val MAC_PATTERN: Regex = Regex("\\b(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}\\b")

        private fun hexOf(text: String): String = buildString(text.length * 2) {
            for (byte in text.toByteArray(Charsets.US_ASCII)) {
                val value = byte.toInt() and 0xFF
                append(HEX_DIGITS[value ushr 4])
                append(HEX_DIGITS[value and 0x0F])
            }
        }

        private const val HEX_DIGITS = "0123456789ABCDEF"
    }
}

/**
 * Renders a [SessionSnapshot] as JSON or CSV, in pure Kotlin.
 *
 * No serialization library is used on purpose: the shape is fixed, small, and worth being able to
 * read in a diff, and a hand-rolled writer cannot silently change its output when a dependency is
 * upgraded. Escaping is implemented explicitly and covered by tests.
 *
 * The file states its own honesty flags — `readOnly: true` and `sanitized: true` — so a log can
 * never be mistaken for something that wrote to a vehicle or for one that still contains a VIN.
 */
object SessionExporter {

    /** Schema identifier written into JSON exports; bump if the shape changes. */
    const val SCHEMA: String = "appili.session.v1"

    private val FILE_TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    /** File name for the exported log, e.g. `appili-session-20261101-101500.csv`. */
    fun fileName(snapshot: SessionSnapshot, format: SessionExportFormat): String =
        "appili-session-${FILE_TIMESTAMP.format(Instant.ofEpochMilli(snapshot.exportedAtMillis))}" +
            ".${format.fileExtension}"

    /** Renders the whole session in [format], sanitizing every field that is written. */
    fun export(
        snapshot: SessionSnapshot,
        format: SessionExportFormat,
        sanitizer: SessionSanitizer,
    ): String = when (format) {
        SessionExportFormat.JSON -> toJson(snapshot, sanitizer)
        SessionExportFormat.CSV -> toCsv(snapshot, sanitizer)
    }

    private fun toJson(snapshot: SessionSnapshot, sanitizer: SessionSanitizer): String = buildString {
        append("{\n")
        append("  \"schema\": ${quoted(SCHEMA)},\n")
        append("  \"readOnly\": true,\n")
        append("  \"sanitized\": true,\n")
        append("  \"startedAt\": ${quoted(isoTimestamp(snapshot.startedAtMillis))},\n")
        append("  \"exportedAt\": ${quoted(isoTimestamp(snapshot.exportedAtMillis))},\n")
        append("  \"adapter\": {\n")
        append("    \"identity\": ${quoted(snapshot.adapterIdentity?.let(sanitizer::sanitize))},\n")
        append("    \"protocol\": ${quoted(snapshot.protocol?.let(sanitizer::sanitize))}\n")
        append("  },\n")
        append("  \"entryCount\": ${snapshot.entries.size},\n")
        append("  \"entries\": [\n")
        snapshot.entries.forEachIndexed { position, entry ->
            append("    {\n")
            append("      \"index\": ${entry.index},\n")
            append("      \"sentAt\": ${quoted(isoTimestamp(entry.sentAtMillis))},\n")
            append("      \"receivedAt\": ${quoted(entry.receivedAtMillis?.let(::isoTimestamp))},\n")
            append("      \"command\": ${quoted(sanitizer.sanitize(entry.command))},\n")
            append("      \"response\": ${quoted(entry.response?.let(sanitizer::sanitize))},\n")
            append("      \"failure\": ${quoted(entry.failure)}\n")
            append("    }")
            if (position < snapshot.entries.lastIndex) append(",")
            append("\n")
        }
        append("  ]\n")
        append("}\n")
    }

    /**
     * A single rectangular table: the session's own facts are repeated on every row rather than
     * emitted as `#` comment lines, because a `#` line is not CSV and every spreadsheet and parser
     * would have to special-case it.
     */
    private fun toCsv(snapshot: SessionSnapshot, sanitizer: SessionSanitizer): String = buildString {
        append("sessionStartedAt,adapterIdentity,protocol,readOnly,sanitized,")
        append("index,sentAt,receivedAt,command,response,failure\n")
        for (entry in snapshot.entries) {
            append(csvField(snapshot.startedAtMillis.let(::isoTimestamp))).append(',')
            append(csvField(snapshot.adapterIdentity?.let(sanitizer::sanitize))).append(',')
            append(csvField(snapshot.protocol?.let(sanitizer::sanitize))).append(',')
            append("true,true,")
            append(entry.index).append(',')
            append(isoTimestamp(entry.sentAtMillis)).append(',')
            append(entry.receivedAtMillis?.let(::isoTimestamp).orEmpty()).append(',')
            append(csvField(sanitizer.sanitize(entry.command))).append(',')
            append(csvField(entry.response?.let(sanitizer::sanitize))).append(',')
            append(csvField(entry.failure)).append('\n')
        }
    }

    private fun isoTimestamp(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

    private fun csvField(value: String?): String {
        if (value == null) return ""
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return value
        return buildString {
            append('"')
            for (character in value) {
                if (character == '"') append("\"\"") else append(character)
            }
            append('"')
        }
    }

    private fun quoted(value: String?): String {
        if (value == null) return "null"
        return buildString {
            append('"')
            for (character in value) {
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character < ' ') {
                        append("\\u").append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
            append('"')
        }
    }
}
