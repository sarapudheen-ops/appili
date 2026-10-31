package com.innova.diag.domain.obd

/**
 * The only thing the diagnostic engine needs from below it: send one command, get text back or a
 * typed failure.
 *
 * This is a port, not an implementation. `com.innova.diag.adapter.elm.ElmObdCommandPort` implements
 * it on top of [com.innova.diag.adapter.elm.ElmChannel]; nothing in `domain` imports the adapter, so
 * the decoders stay pure Kotlin and unit-testable without a socket, a Bluetooth stack or Android.
 *
 * Failures are returned as [DiagnosticResult], not thrown:
 * - an adapter timeout becomes [DiagnosticResult.Timeout];
 * - `NO DATA`, a refused connection, a bus/CAN error, a full buffer or a closed link become
 *   [DiagnosticResult.NoResponse];
 * - a command the adapter rejects (that is, a bug in this layer) becomes [DiagnosticResult.Malformed].
 *
 * A successful answer carries the adapter's normalized text (e.g. `"410C1A20"`) as
 * [DiagnosticResult.Success.data]; the same text is also available as `rawHex`. The port never
 * interprets that text — deciding what a response means is the decoders' job.
 */
interface ObdCommandPort {

    /**
     * Sends [command] (without a terminator; the channel appends it) and returns its response.
     *
     * @throws kotlinx.coroutines.CancellationException if the caller is cancelled — cancellation is
     *   not a vehicle condition and must not be converted into a result.
     */
    suspend fun execute(command: String): DiagnosticResult<String>
}
