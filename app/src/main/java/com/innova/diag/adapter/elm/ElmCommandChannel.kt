package com.innova.diag.adapter.elm

/**
 * How a response's whitespace is handled before it reaches the caller.
 *
 * The adapter's own spacing is cosmetic for hex payloads (`"41 0C 1A 20"`) but *is* the content for
 * text answers (`"ELM327 v1.5"`, `"AUTO, ISO 15765-4 (CAN 11/500)"`). Collapsing one and not the
 * other keeps both readable without a second parsing path.
 */
enum class ElmResponseNormalization {

    /** Strip all whitespace: `"41 0C 1A 20"` → `"410C1A20"`. Correct for PIDs and DTC payloads. */
    HEX,

    /** Keep inner spacing, one line per line: `"ELM327 v1.5"` stays readable. */
    TEXT,
}

/**
 * The one operation the layers above need from an adapter: send a command, get its normalized
 * response text.
 *
 * Extracted from [ElmChannel] so that behaviour can be *wrapped* without subclassing — the session
 * recorder sits between the channel and its callers and must not be able to bypass arbitration:
 * every wrapper still delegates to a single channel with a single in-flight command.
 */
interface ElmCommandChannel {

    /**
     * Sends [command] (without its terminator) and returns the normalized response.
     *
     * @param timeoutMillis overrides the channel default for this call only.
     * @param normalization whether whitespace is stripped ([ElmResponseNormalization.HEX]) or kept
     *   ([ElmResponseNormalization.TEXT]).
     * @throws ElmException when the adapter reports an error token or the prompt never arrives.
     * @throws IllegalArgumentException when [command] is blank or contains a line terminator.
     */
    suspend fun execute(
        command: String,
        timeoutMillis: Long? = null,
        normalization: ElmResponseNormalization = ElmResponseNormalization.HEX,
    ): String
}
