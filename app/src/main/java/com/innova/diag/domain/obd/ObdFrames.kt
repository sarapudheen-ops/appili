package com.innova.diag.domain.obd

/**
 * Byte-level validation shared by every decoder: is this text an answer at all, and is it the answer
 * to the request we made?
 *
 * Everything here is deliberately strict. A response is accepted only when its service byte and its
 * PID echo match the request; anything else becomes [DiagnosticResult.Malformed] rather than being
 * reinterpreted as some other PID's data.
 */
internal object ObdFrames {

    const val SERVICE_MODE_01: Int = 0x41
    const val SERVICE_MODE_03: Int = 0x43
    const val SERVICE_MODE_07: Int = 0x47
    const val SERVICE_MODE_09: Int = 0x49

    private const val NEGATIVE_RESPONSE: Int = 0x7F
    private const val NRC_SUB_FUNCTION_NOT_SUPPORTED_OR_INVALID_FORMAT: Int = 0x12
    private const val NODI_PREFIX: Int = 0x01

    /**
     * Recognises the text an ELM-style adapter prints *instead of* vehicle data.
     *
     * These are real wire-level answers (`NO DATA` is what the adapter sends when the ECU stays
     * silent), which is why they are parsed here rather than only inside the adapter layer: a
     * replayed transcript must produce exactly the same verdict as a live link.
     *
     * @return the terminal result, or `null` when the text looks like hex and should be parsed.
     */
    fun <T> adapterFailure(raw: String): DiagnosticResult<T>? {
        val text = raw.trim()
        if (text.isEmpty()) return DiagnosticResult.NoResponse

        val upper = text.uppercase()
        return when {
            upper.startsWith("NO DATA") -> DiagnosticResult.NoResponse
            upper.startsWith("UNABLE TO CONNECT") -> DiagnosticResult.NoResponse
            upper.startsWith("BUS ERROR") -> DiagnosticResult.NoResponse
            upper.startsWith("CAN ERROR") -> DiagnosticResult.NoResponse
            upper.startsWith("BUFFER FULL") -> DiagnosticResult.NoResponse
            upper.startsWith("STOPPED") -> DiagnosticResult.NoResponse
            upper.startsWith("SEARCHING") -> DiagnosticResult.NoResponse
            upper.startsWith("?") -> DiagnosticResult.Malformed(text)
            else -> null
        }
    }

    /** Decodes hex text (spaces tolerated, case-insensitive) or returns `null` if it is not hex. */
    fun hexBytes(raw: String): ByteArray? {
        val compact = raw.filterNot { it.isWhitespace() }
        if (compact.isEmpty() || compact.length % 2 != 0) return null

        val bytes = ByteArray(compact.length / 2)
        for (index in bytes.indices) {
            val high = hexDigit(compact[index * 2]) ?: return null
            val low = hexDigit(compact[index * 2 + 1]) ?: return null
            bytes[index] = ((high shl 4) or low).toByte()
        }
        return bytes
    }

    /**
     * Validates a mode 01 / mode 09 response and returns its data bytes.
     *
     * @param service the positive response service byte (`0x41` for mode 01, `0x49` for mode 09).
     * @param pid the requested PID, which must be echoed back as the second byte.
     */
    fun modeResponse(raw: String, service: Int, pid: Int): DiagnosticResult<ByteArray> {
        adapterFailure<ByteArray>(raw)?.let { return it }

        val bytes = hexBytes(raw) ?: return DiagnosticResult.Malformed(raw.trim())
        if (bytes.isEmpty()) return DiagnosticResult.Malformed(raw.trim())

        val first = bytes[0].toInt() and 0xFF
        if (first == NEGATIVE_RESPONSE) return negativeResponse(raw, bytes)
        if (first != service) return DiagnosticResult.Malformed(raw.trim())
        if (bytes.size < 2) return DiagnosticResult.Malformed(raw.trim())
        if ((bytes[1].toInt() and 0xFF) != pid) return DiagnosticResult.Malformed(raw.trim())

        return DiagnosticResult.Success(bytes.copyOfRange(2, bytes.size), raw.trim())
    }

    /**
     * Validates a mode 03 / mode 07 response and returns the DTC payload (whole 2-byte blocks).
     *
     * ISO 15765 (CAN) prefixes the payload with a DTC count; ISO 9141-2 / ISO 14230 (K-line) do not.
     * Rather than guess from the protocol, the payload's **parity** decides: an even number of bytes
     * after the service byte can only be whole DTC blocks, an odd number means the first byte is the
     * count. A wrong interpretation would misalign every code, so this is checked, not hoped.
     */
    fun dtcPayload(raw: String, service: Int): DiagnosticResult<ByteArray> {
        adapterFailure<ByteArray>(raw)?.let { return it }

        val bytes = hexBytes(raw) ?: return DiagnosticResult.Malformed(raw.trim())
        if (bytes.isEmpty()) return DiagnosticResult.Malformed(raw.trim())

        val first = bytes[0].toInt() and 0xFF
        if (first == NEGATIVE_RESPONSE) return negativeResponse(raw, bytes)
        if (first != service) return DiagnosticResult.Malformed(raw.trim())

        var payload = bytes.copyOfRange(1, bytes.size)
        if (payload.size % 2 != 0) {
            // Odd length: the leading byte is the DTC count (CAN). Its value is not used to truncate
            // the payload — adapters pad with 0x0000 blocks — but a count larger than the number of
            // blocks actually present is contradictory data, not a list of codes.
            val announcedCount = payload[0].toInt() and 0xFF
            val availableBlocks = (payload.size - 1) / 2
            if (announcedCount > availableBlocks) return DiagnosticResult.Malformed(raw.trim())
            payload = payload.copyOfRange(1, payload.size)
        }
        return DiagnosticResult.Success(payload, raw.trim())
    }

    /** Strips the ISO-TP / mode 09 "number of data items" prefix when present. */
    fun stripNumberOfDataItems(data: ByteArray): ByteArray =
        if (data.isNotEmpty() && (data[0].toInt() and 0xFF) == NODI_PREFIX) {
            data.copyOfRange(1, data.size)
        } else {
            data
        }

    private fun <T> negativeResponse(raw: String, bytes: ByteArray): DiagnosticResult<T> {
        val nrc = if (bytes.size >= 3) bytes[2].toInt() and 0xFF else -1
        return if (nrc == NRC_SUB_FUNCTION_NOT_SUPPORTED_OR_INVALID_FORMAT) {
            DiagnosticResult.Unsupported
        } else {
            DiagnosticResult.Malformed(raw.trim())
        }
    }

    private fun hexDigit(character: Char): Int? = when (character) {
        in '0'..'9' -> character - '0'
        in 'a'..'f' -> character - 'a' + 10
        in 'A'..'F' -> character - 'A' + 10
        else -> null
    }
}
