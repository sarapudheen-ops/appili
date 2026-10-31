package com.innova.diag.core.transport

/**
 * Lifecycle state of a [ByteTransport] connection.
 *
 * This is a *connection* state, not a data-quality value. It must never be confused with the
 * honest data model required by `AGENTS.md` Rule 4 (`TIMEOUT`, `NO_RESPONSE`, `UNSUPPORTED`,
 * `MALFORMED_DATA`, numeric `0`) and it must never be mapped onto `RESULT_UNKNOWN`, which is a
 * *transaction* outcome defined in `docs/SAFETY_POLICY.md` §4.
 *
 * Declared as a sealed interface rather than an enum because [ERROR] carries its cause: callers
 * must be able to inspect why a link failed, not just that it did.
 */
sealed interface TransportState {

    /** No link. Nothing has been opened, or the link was closed or lost. */
    data object DISCONNECTED : TransportState

    /** A connection attempt is in progress. The transport is not yet usable. */
    data object CONNECTING : TransportState

    /** The link is open and reads/writes may be attempted. */
    data object CONNECTED : TransportState

    /**
     * The link failed or was terminated abnormally.
     *
     * [throwable] is the original cause, preserved by reference so callers can log it without
     * re-wrapping and lose the stack trace. Recovering from `ERROR` requires an explicit
     * [ByteTransport.connect]; it never happens implicitly.
     */
    data class ERROR(val throwable: Throwable) : TransportState
}
