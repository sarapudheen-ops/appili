package com.innova.diag.domain.obd

/**
 * The result of asking a vehicle for one datum.
 *
 * Five outcomes, and they are **not** interchangeable. This type exists to make the dishonest
 * shortcut — "we didn't get a number, so call it `0`" — impossible to express. `AGENTS.md` Rule 4
 * requires that `TIMEOUT`, `NO_RESPONSE`, `UNSUPPORTED`, `MALFORMED_DATA` and a genuine numeric `0`
 * stay distinguishable all the way to the UI, and [Success] with `data = 0` is the *only* way to say
 * "the car really reported zero".
 *
 * [rawHex] is retained on both [Success] and [Malformed] so a technician (or a bug report) can see
 * exactly which bytes produced the verdict. For [Malformed] it is what we could not parse; for
 * [Success] it is the response that was parsed, unmodified.
 *
 * Not to be confused with `TransportState.ERROR` (the link failed) or `RESULT_UNKNOWN`
 * (`docs/SAFETY_POLICY.md` §4 — a write may or may not have applied). This type describes *readings*.
 */
sealed interface DiagnosticResult<out T> {

    /** The vehicle answered with a value that parsed cleanly. `data` may legitimately be zero. */
    data class Success<T>(
        val data: T,
        val rawHex: String,
    ) : DiagnosticResult<T>

    /**
     * The vehicle answered, but this PID/service is not supported: either the supported-PID bitmask
     * excludes it, or the ECU returned a negative response with NRC `0x12`
     * (subFunctionNotSupported).
     */
    data object Unsupported : DiagnosticResult<Nothing>

    /**
     * Nothing came back that we can attribute to the vehicle: adapter reported `NO DATA`, or the
     * bus answered with `UNABLE TO CONNECT` / `BUS ERROR` / `CAN ERROR` / `BUFFER FULL` / `STOPPED`.
     * There is a link, and there is no reading.
     */
    data object NoResponse : DiagnosticResult<Nothing>

    /**
     * The prompt never arrived inside the command budget, so no answer exists.
     *
     * Produced by the transport/channel layer (an `ElmException.Timeout`), never inferred from
     * silence by a decoder: silence and a slow adapter are different facts.
     */
    data object Timeout : DiagnosticResult<Nothing>

    /**
     * Bytes arrived but they are not a valid answer to this request: bad header, PID echo mismatch,
     * non-hex text, wrong payload length, or a negative response with an NRC we cannot interpret as
     * "unsupported".
     */
    data class Malformed(val rawHex: String) : DiagnosticResult<Nothing>
}

/**
 * Applies [transform] to a [DiagnosticResult.Success] payload and passes every failure through
 * untouched, so a failure can never be silently upgraded to a value.
 */
internal inline fun <T, R> DiagnosticResult<T>.mapData(
    transform: (T) -> DiagnosticResult<R>,
): DiagnosticResult<R> = when (this) {
    is DiagnosticResult.Success -> transform(data)
    is DiagnosticResult.Malformed -> this
    DiagnosticResult.NoResponse -> DiagnosticResult.NoResponse
    DiagnosticResult.Timeout -> DiagnosticResult.Timeout
    DiagnosticResult.Unsupported -> DiagnosticResult.Unsupported
}
