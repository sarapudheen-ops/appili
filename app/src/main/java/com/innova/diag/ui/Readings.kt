package com.innova.diag.ui

import com.innova.diag.domain.obd.DiagnosticResult
import com.innova.diag.domain.obd.DiagnosticTroubleCode
import com.innova.diag.domain.obd.PidDefinition
import com.innova.diag.domain.obd.PidReading
import com.innova.diag.domain.obd.SupportedPids
import com.innova.diag.domain.obd.Vin

/**
 * How prominently a reading should be drawn.
 *
 * Kept separate from Compose so the mapping can be tested on the JVM: the colour a value gets is a
 * *claim about the vehicle*, and a test asserting "orange" is really asserting "this is not data".
 */
enum class ReadingTone {

    /** A real measurement from the vehicle. Includes a genuine zero. */
    VALUE,

    /** Not a measurement, and not a fault: "Not Supported", "Not read yet", "None reported". */
    MUTED,

    /** Not a measurement, and worth noticing: "No Response", "Timeout", "Unreadable data". */
    WARNING,
}

/**
 * What the screen shows for one value, with the honesty baked in.
 *
 * There is deliberately **no** representation for "missing, so show 0". A failed read is one of
 * [NotSupported], [NoResponse], [Timeout] or [Malformed], each with its own words and tone. The only
 * way to render `0` is a successful reading whose value really is zero.
 */
sealed interface ReadingView {

    val tone: ReadingTone

    /** The one line a technician reads. */
    val text: String

    /** Optional second line: the raw bytes, or why there is no value. */
    val detail: String?

    /** A value that arrived. [unit] may be empty for non-numeric readings such as a VIN. */
    data class Measured(
        val value: String,
        val unit: String,
        val rawHex: String?,
    ) : ReadingView {
        override val tone: ReadingTone get() = ReadingTone.VALUE
        override val text: String get() = if (unit.isEmpty()) value else "$value $unit"
        override val detail: String? get() = rawHex?.takeIf { it.isNotBlank() }?.let { "raw: $it" }
    }

    /** A list of codes; an empty list is a real answer ("this ECU has no codes"), not a failure. */
    data class Codes(
        val codes: List<String>,
        val rawHex: String?,
    ) : ReadingView {
        override val tone: ReadingTone get() = if (codes.isEmpty()) ReadingTone.MUTED else ReadingTone.VALUE
        override val text: String get() = if (codes.isEmpty()) "None reported" else codes.joinToString(", ")
        override val detail: String? get() = rawHex?.takeIf { it.isNotBlank() }?.let { "raw: $it" }
    }

    /** The vehicle (or its bitmask) says this PID does not exist here. */
    data object NotSupported : ReadingView {
        override val tone: ReadingTone get() = ReadingTone.MUTED
        override val text: String get() = "Not Supported"
        override val detail: String? get() = null
    }

    /** The request was valid and the vehicle said nothing. */
    data object NoResponse : ReadingView {
        override val tone: ReadingTone get() = ReadingTone.WARNING
        override val text: String get() = "No Response"
        override val detail: String get() = "The adapter returned NO DATA. This is not a zero."
    }

    /** No reply arrived inside the command budget. */
    data object Timeout : ReadingView {
        override val tone: ReadingTone get() = ReadingTone.WARNING
        override val text: String get() = "Timeout"
        override val detail: String get() = "No reply arrived in time. This is not a zero."
    }

    /** Something arrived, but it is not a valid answer to this request. */
    data class Malformed(val rawHex: String) : ReadingView {
        override val tone: ReadingTone get() = ReadingTone.WARNING
        override val text: String get() = "Unreadable data"
        override val detail: String? get() = rawHex.takeIf { it.isNotBlank() }
    }

    /** Nothing has been asked yet. Distinct from every failure above. */
    data object NotReadYet : ReadingView {
        override val tone: ReadingTone get() = ReadingTone.MUTED
        override val text: String get() = "Not read yet"
        override val detail: String? get() = null
    }
}

/**
 * Maps domain results onto [ReadingView].
 *
 * One function per shape of answer, all total: every [`DiagnosticResult`] variant has a rendering, so
 * no code path can fall through to a default number.
 */
object Readings {

    fun pid(reading: PidReading): ReadingView = pid(reading.definition, reading.result)

    fun pid(definition: PidDefinition, result: DiagnosticResult<Double>): ReadingView = result.fold(
        onSuccess = { value, rawHex ->
            ReadingView.Measured(definition.format(value), definition.unit, rawHex)
        },
        onUnsupported = { ReadingView.NotSupported },
        onNoResponse = { ReadingView.NoResponse },
        onTimeout = { ReadingView.Timeout },
        onMalformed = { rawHex -> ReadingView.Malformed(rawHex) },
    )

    fun dtcs(result: DiagnosticResult<List<DiagnosticTroubleCode>>): ReadingView = result.fold(
        onSuccess = { codes, rawHex -> ReadingView.Codes(codes.map { code -> code.code }, rawHex) },
        onUnsupported = { ReadingView.NotSupported },
        onNoResponse = { ReadingView.NoResponse },
        onTimeout = { ReadingView.Timeout },
        onMalformed = { rawHex -> ReadingView.Malformed(rawHex) },
    )

    fun vin(result: DiagnosticResult<Vin>): ReadingView = result.fold(
        onSuccess = { vin, rawHex -> ReadingView.Measured(vin.value, unit = "", rawHex = rawHex) },
        onUnsupported = { ReadingView.NotSupported },
        onNoResponse = { ReadingView.NoResponse },
        onTimeout = { ReadingView.Timeout },
        onMalformed = { rawHex -> ReadingView.Malformed(rawHex) },
    )

    /** For free-text answers such as `ATI` / `ATDP` and the debug console. */
    fun text(result: DiagnosticResult<String>): ReadingView = result.fold(
        onSuccess = { value, rawHex ->
            if (value.isBlank()) {
                ReadingView.NoResponse
            } else {
                ReadingView.Measured(value, unit = "", rawHex = rawHex)
            }
        },
        onUnsupported = { ReadingView.NotSupported },
        onNoResponse = { ReadingView.NoResponse },
        onTimeout = { ReadingView.Timeout },
        onMalformed = { rawHex -> ReadingView.Malformed(rawHex) },
    )

    /** One supported-PID bank. A bank that never answered is `No Response`, never "nothing supported". */
    fun supportedBank(result: DiagnosticResult<SupportedPids>): ReadingView = result.fold(
        onSuccess = { supported, rawHex ->
            ReadingView.Codes(supported.pids.sorted().map(::hexPid), rawHex)
        },
        onUnsupported = { ReadingView.NotSupported },
        onNoResponse = { ReadingView.NoResponse },
        onTimeout = { ReadingView.Timeout },
        onMalformed = { rawHex -> ReadingView.Malformed(rawHex) },
    )

    /** `0x0C`-style label for a PID. */
    fun hexPid(pid: Int): String = "0x" + pid.toString(16).uppercase().padStart(2, '0')
}
