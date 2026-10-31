package com.innova.diag.domain.obd

/** The four DTC system letters defined by ISO 15031-6 / SAE J2012. */
enum class DtcSystem(val letter: Char) {
    POWERTRAIN('P'),
    CHASSIS('C'),
    BODY('B'),
    NETWORK('U'),
}

/**
 * A four-character diagnostic trouble code, e.g. `P0133`.
 *
 * Held as a [system] plus four hexadecimal digits rather than a bare string so a caller cannot
 * accidentally treat `"FOO"` as a fault code. [code] renders the familiar form.
 */
data class DiagnosticTroubleCode(
    val system: DtcSystem,
    val number: String,
) {
    init {
        require(number.length == NUMBER_LENGTH && number.all { it in HEX_DIGITS }) {
            "a DTC number is four hex digits, got \"$number\""
        }
    }

    /** Canonical text, e.g. `"P0133"`. */
    val code: String get() = "${system.letter}$number"

    override fun toString(): String = code

    companion object {
        const val NUMBER_LENGTH: Int = 4
        private const val HEX_DIGITS = "0123456789ABCDEF"

        /**
         * Decodes one 2-byte DTC block.
         *
         * The first two bits of [high] choose the system (`00` P, `01` C, `10` B, `11` U), the next
         * two bits are the first digit, the low nibble is the second digit, and [low] supplies the
         * last two digits.
         */
        fun fromBytes(high: Int, low: Int): DiagnosticTroubleCode {
            require(high in 0..0xFF && low in 0..0xFF) {
                "DTC bytes must be in 0..0xFF, got $high and $low"
            }
            val system = when ((high shr 6) and 0x03) {
                0 -> DtcSystem.POWERTRAIN
                1 -> DtcSystem.CHASSIS
                2 -> DtcSystem.BODY
                else -> DtcSystem.NETWORK
            }
            val number = buildString(NUMBER_LENGTH) {
                append(HEX_DIGITS[(high shr 4) and 0x03])
                append(HEX_DIGITS[high and 0x0F])
                append(HEX_DIGITS[(low shr 4) and 0x0F])
                append(HEX_DIGITS[low and 0x0F])
            }
            return DiagnosticTroubleCode(system, number)
        }
    }
}

/**
 * Decoders for mode 03 (stored DTCs) and mode 07 (pending DTCs).
 *
 * `0x0000` blocks are padding, not codes: they are discarded rather than reported as `P0000`.
 *
 * Out of scope and deliberately absent: mode 04 (clear DTCs) — this repository is read-only toward
 * physical vehicles (`docs/PRODUCT_SCOPE.md` §2.2).
 */
object DtcDecoders {

    /** Mode 03: emission-related DTCs the ECU has stored. */
    fun stored(raw: String): DiagnosticResult<List<DiagnosticTroubleCode>> =
        decode(raw, ObdFrames.SERVICE_MODE_03)

    /** Mode 07: DTCs detected in the current or last drive cycle. */
    fun pending(raw: String): DiagnosticResult<List<DiagnosticTroubleCode>> =
        decode(raw, ObdFrames.SERVICE_MODE_07)

    private fun decode(
        raw: String,
        service: Int,
    ): DiagnosticResult<List<DiagnosticTroubleCode>> =
        ObdFrames.dtcPayload(raw, service).mapData { payload ->
            val codes = mutableListOf<DiagnosticTroubleCode>()
            var index = 0
            while (index + 1 < payload.size) {
                val high = payload[index].toInt() and 0xFF
                val low = payload[index + 1].toInt() and 0xFF
                if (high != 0 || low != 0) {
                    codes += DiagnosticTroubleCode.fromBytes(high, low)
                }
                index += 2
            }
            DiagnosticResult.Success(codes.toList(), raw.trim())
        }
}
