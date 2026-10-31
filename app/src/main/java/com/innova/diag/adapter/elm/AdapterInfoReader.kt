package com.innova.diag.adapter.elm

import com.innova.diag.domain.obd.DiagnosticResult

/**
 * What the adapter says about itself: `ATI` (identity) and `ATDP` (the protocol it detected).
 *
 * Both are *adapter* facts, not vehicle facts — `ATI` reads a chip, `ATDP` reads what auto-detection
 * concluded. Neither is a vehicle request, so asking for them does not breach the "no vehicle
 * requests until the matrix is verified" rule; treating `ATDP`'s answer as a verified protocol
 * *would*, which is why the UI shows it as the adapter's claim rather than as a matrix field.
 */
data class AdapterInfo(
    val identity: DiagnosticResult<String>,
    val protocol: DiagnosticResult<String>,
)

/**
 * Reads [AdapterInfo] with `TEXT` normalization.
 *
 * Whitespace matters here — `"AUTO, ISO 15765-4 (CAN 11/500)"` is unreadable without it — so these
 * two commands use [ElmResponseNormalization.TEXT] while every PID request keeps `HEX`.
 *
 * A failure is reported, never invented: an unanswered `ATI` is [DiagnosticResult.NoResponse] and
 * the screen says so instead of showing a guessed chip name.
 */
class AdapterInfoReader(private val channel: ElmCommandChannel) {

    suspend fun read(): AdapterInfo = AdapterInfo(
        identity = readText(IDENTITY_COMMAND, AdapterInfoText::identity),
        protocol = readText(PROTOCOL_COMMAND, AdapterInfoText::protocol),
    )

    private suspend fun readText(command: String, parse: (String) -> String?): DiagnosticResult<String> {
        val raw = try {
            channel.execute(command, normalization = ElmResponseNormalization.TEXT)
        } catch (failure: ElmException) {
            return failure.toDiagnosticResult()
        }
        if (raw.isEmpty()) return DiagnosticResult.NoResponse
        val parsed = parse(raw) ?: return DiagnosticResult.Malformed(raw)
        return DiagnosticResult.Success(parsed, raw)
    }

    companion object {
        /** `ATI` — adapter identification. */
        const val IDENTITY_COMMAND: String = "ATI"

        /** `ATDP` — "describe protocol", as auto-detected by the adapter. */
        const val PROTOCOL_COMMAND: String = "ATDP"
    }
}

/** Turns raw `ATI` / `ATDP` text into something a technician can read. */
object AdapterInfoText {

    /**
     * First meaningful line, with the `SEARCHING...` banner dropped and inner whitespace collapsed.
     *
     * @return `null` when the adapter said nothing usable, so the caller can report `Malformed`.
     */
    fun identity(raw: String): String? = firstMeaningfulLine(raw)

    /**
     * The protocol line, e.g. `"AUTO, ISO 15765-4 (CAN 11/500)"`.
     *
     * Returned verbatim (trimmed): rewording the adapter's own report would be interpretation, and
     * this string is evidence, not a decision.
     */
    fun protocol(raw: String): String? = firstMeaningfulLine(raw)

    /** True when the adapter reports that it is auto-detecting rather than pinned to a protocol. */
    fun isAutoDetected(protocol: String): Boolean =
        protocol.trim().uppercase().startsWith("AUTO")

    private fun firstMeaningfulLine(raw: String): String? = raw
        .lines()
        .map { line -> line.trim() }
        .map { line -> line.split(WHITESPACE_RUN).filter { it.isNotEmpty() }.joinToString(" ") }
        .firstOrNull { line -> line.isNotEmpty() && !line.uppercase().startsWith(SEARCHING) }

    private val WHITESPACE_RUN = Regex("\\s+")
    private const val SEARCHING = "SEARCHING"
}
