package com.innova.diag.adapter.elm

import com.innova.diag.domain.session.SessionLog

/**
 * Decorator that writes every exchange to a [SessionLog] while staying out of the way.
 *
 * It is placed *above* [ElmChannel] and *below* everything else, so one wrapper captures the adapter
 * configuration commands, the identity/protocol reads and every PID/DTC request without each caller
 * having to remember to log.
 *
 * ### What it does and does not record
 * - **Records** a command once it has been handed to the channel and has either answered or failed
 *   with an [ElmException]. The log holds the normalized response text or a stable failure token.
 * - **Does not record** a command the channel refused before writing (blank or containing a line
 *   terminator, which throws [IllegalArgumentException]). Nothing reached the adapter, so nothing
 *   is logged — a log that shows commands that were never transmitted is worse than no log.
 * - **Does not record** a cancelled exchange: cancellation is not a vehicle condition, and the
 *   outcome is unknown rather than negative or positive.
 */
class RecordingCommandChannel(
    private val delegate: ElmCommandChannel,
    private val log: SessionLog,
    private val clock: () -> Long = System::currentTimeMillis,
) : ElmCommandChannel {

    override suspend fun execute(
        command: String,
        timeoutMillis: Long?,
        normalization: ElmResponseNormalization,
    ): String {
        val sentAtMillis = clock()
        return try {
            val response = delegate.execute(command, timeoutMillis, normalization)
            log.record(
                command = command,
                sentAtMillis = sentAtMillis,
                response = response,
                failure = null,
                receivedAtMillis = clock(),
            )
            response
        } catch (failure: ElmException) {
            log.record(
                command = command,
                sentAtMillis = sentAtMillis,
                response = null,
                failure = failure.token(),
                receivedAtMillis = clock(),
            )
            throw failure
        }
    }
}

/**
 * Stable, machine-readable token for an adapter failure.
 *
 * Upper snake case rather than prose so that exports can be diffed and grepped; the human-facing
 * screens use their own wording (`DiagnosticResult` variants).
 */
internal fun ElmException.token(): String = when (this) {
    is ElmException.Timeout -> "TIMEOUT"
    is ElmException.NoData -> "NO_DATA"
    is ElmException.UnableToConnect -> "UNABLE_TO_CONNECT"
    is ElmException.BusError -> "BUS_ERROR"
    is ElmException.CanError -> "CAN_ERROR"
    is ElmException.BufferFull -> "BUFFER_FULL"
    is ElmException.Stopped -> "STOPPED"
    is ElmException.LinkClosed -> "LINK_CLOSED"
    is ElmException.UnknownCommand -> "REJECTED"
}
