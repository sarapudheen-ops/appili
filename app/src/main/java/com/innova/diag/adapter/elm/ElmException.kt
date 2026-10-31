package com.innova.diag.adapter.elm

/**
 * Typed failures produced by [ElmChannel].
 *
 * Every adapter-level failure gets its own type. Collapsing them into one exception (or into an
 * empty string) would destroy the distinction the data model depends on — see `AGENTS.md` Rule 4:
 * "no data" is not an empty reading, and "adapter did not understand the command" is not a vehicle
 * fault. Nothing here is a [com.innova.diag.core.transport.TransportState]; a working link can
 * still return `NO DATA`.
 */
sealed class ElmException(
    message: String,
    /** The command that produced this failure, without its terminator. */
    val command: String,
) : Exception(message) {

    /** Adapter replied `?`: it does not recognise the command. */
    class UnknownCommand(
        command: String,
        val rawResponse: String,
    ) : ElmException(
        "Adapter rejected \"$command\" with a question mark; raw response was \"${rawResponse.trim()}\".",
        command,
    )

    /** Adapter replied `NO DATA`: the request was valid but the ECU said nothing. */
    class NoData(command: String) : ElmException(
        "Adapter reported NO DATA for \"$command\".",
        command,
    )

    /** Adapter replied `UNABLE TO CONNECT`: it could not establish a link to the vehicle bus. */
    class UnableToConnect(command: String) : ElmException(
        "Adapter reported UNABLE TO CONNECT for \"$command\".",
        command,
    )

    /** Adapter replied `BUS ERROR`. */
    class BusError(command: String) : ElmException(
        "Adapter reported BUS ERROR for \"$command\".",
        command,
    )

    /** Adapter replied `CAN ERROR`. */
    class CanError(command: String) : ElmException(
        "Adapter reported CAN ERROR for \"$command\".",
        command,
    )

    /** Adapter replied `BUFFER FULL`. */
    class BufferFull(command: String) : ElmException(
        "Adapter reported BUFFER FULL for \"$command\".",
        command,
    )

    /** Adapter replied `STOPPED` (the command was interrupted). */
    class Stopped(command: String) : ElmException(
        "Adapter reported STOPPED for \"$command\".",
        command,
    )

    /**
     * The byte stream ended before the prompt arrived (the link was torn down mid-exchange).
     *
     * Distinct from [Timeout]: nothing is being waited for any more. The exchange is inconclusive,
     * which is precisely the condition `docs/SAFETY_POLICY.md` §4 treats as unknown — so it must
     * never be reported as an empty response.
     */
    class LinkClosed(command: String) : ElmException(
        "The byte stream ended before the prompt arrived for \"$command\"; the exchange is inconclusive.",
        command,
    )

    /** The prompt character never arrived, so the exchange is incomplete and unusable. */
    class Timeout(
        command: String,
        val timeoutMillis: Long,
    ) : ElmException(
        "No prompt received for \"$command\" within ${timeoutMillis}ms.",
        command,
    )
}
