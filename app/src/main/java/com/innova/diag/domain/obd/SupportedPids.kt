package com.innova.diag.domain.obd

/**
 * One 32-bit bank of supported PIDs, as reported by PID `0x00`, `0x20`, `0x40` or `0x60`.
 *
 * The bitmask is ordered **most-significant-bit first**: bit 31 of [mask] corresponds to
 * `basePid + 1`, bit 0 to `basePid + 32`. That last bit is the "ask me for the next bank" flag, so
 * `basePid = 0x00` with bit 0 set means `0x20` must be requested next.
 */
data class SupportedPids(
    val basePid: Int,
    val mask: Long,
) {
    init {
        require(basePid in BASE_PIDS) {
            "supported-PID banks exist only at ${BASE_PIDS.joinToString { hex(it) }}; got ${hex(basePid)}"
        }
        require(mask in 0L..0xFFFFFFFFL) { "mask must be a 32-bit unsigned value, got $mask" }
    }

    /** Every PID this bank reports as supported, in ascending order. */
    val pids: Set<Int> = (1..PID_BANK_SIZE)
        .filter { offset -> isSet(offset) }
        .map { offset -> basePid + offset }
        .toSet()

    /** True when [pid] falls inside this bank and its bit is set. */
    fun supports(pid: Int): Boolean {
        val offset = pid - basePid
        return offset in 1..PID_BANK_SIZE && isSet(offset)
    }

    /** True when the next 32-PID bank should be requested as well. */
    val continuesToNextBank: Boolean get() = isSet(PID_BANK_SIZE)

    private fun isSet(offset: Int): Boolean = ((mask ushr (PID_BANK_SIZE - offset)) and 1L) == 1L

    companion object {
        /** The only PIDs that return a supported-PID bitmask. */
        val BASE_PIDS: List<Int> = listOf(0x00, 0x20, 0x40, 0x60)

        const val PID_BANK_SIZE: Int = 32

        /** Builds the 32-bit mask from the four data bytes, most significant first. */
        fun maskOf(data: ByteArray): Long {
            require(data.size == 4) { "a supported-PID response carries exactly 4 data bytes, got ${data.size}" }
            return ((data[0].toLong() and 0xFF) shl 24) or
                ((data[1].toLong() and 0xFF) shl 16) or
                ((data[2].toLong() and 0xFF) shl 8) or
                (data[3].toLong() and 0xFF)
        }
    }
}

private fun hex(value: Int): String = "0x" + value.toString(16).uppercase().padStart(2, '0')
