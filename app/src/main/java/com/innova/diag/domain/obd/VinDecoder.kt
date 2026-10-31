package com.innova.diag.domain.obd

/**
 * A 17-character Vehicle Identification Number.
 *
 * Validated on construction: SAE J1979 excludes `I`, `O` and `Q` to avoid confusion with `1` and
 * `0`, so a string containing them is not a VIN no matter how confident the ECU sounded.
 */
data class Vin(val value: String) {

    init {
        require(isValid(value)) {
            "not a valid VIN: \"$value\" (expected $LENGTH characters from $ALLOWED_CHARACTERS)"
        }
    }

    override fun toString(): String = value

    companion object {
        const val LENGTH: Int = 17

        private const val ALLOWED_CHARACTERS = "ABCDEFGHJKLMNPRSTUVWXYZ0123456789"

        /** True when [value] is exactly 17 characters and uses only permitted VIN characters. */
        fun isValid(value: String): Boolean =
            value.length == LENGTH && value.all { it in ALLOWED_CHARACTERS }
    }
}

/**
 * Decoder for mode 09 PID `0x02`, the VIN.
 *
 * The response arrives as ASCII bytes inside the hex payload, optionally prefixed with the "number
 * of data items" byte that ISO 15765 puts first. Rather than trusting a fixed offset, the decoder
 * locates the printable run and then validates it as a VIN — an ECU that pads or omits the prefix
 * cannot shift the result into a plausible-looking wrong string.
 */
object VinDecoder {

    /** Mode 09 PID `0x02`. */
    const val PID_VIN: Int = 0x02

    private val PRINTABLE = ' '..'~'

    fun decode(raw: String): DiagnosticResult<Vin> =
        ObdFrames.modeResponse(raw, ObdFrames.SERVICE_MODE_09, PID_VIN).mapData { data ->
            val payload = ObdFrames.stripNumberOfDataItems(data)
            val characters = payload.map { byte -> (byte.toInt() and 0xFF).toChar() }
            val start = characters.indexOfFirst { it in PRINTABLE }

            if (start < 0) {
                DiagnosticResult.Malformed(raw.trim())
            } else {
                val text = characters.drop(start).takeWhile { it in PRINTABLE }.joinToString("")
                val candidate = text.take(Vin.LENGTH)
                if (Vin.isValid(candidate)) {
                    DiagnosticResult.Success(Vin(candidate), raw.trim())
                } else {
                    DiagnosticResult.Malformed(raw.trim())
                }
            }
        }
}
