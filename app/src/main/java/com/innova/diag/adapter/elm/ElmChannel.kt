package com.innova.diag.adapter.elm

import com.innova.diag.core.transport.ByteTransport
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Command/response channel for an ELM327-style adapter, layered above [ByteTransport].
 *
 * The transport delivers an undelimited byte stream; this class turns it into discrete exchanges:
 * write a command, accumulate bytes until the `>` prompt, then hand back a normalized response.
 *
 * ### Responsibilities
 * - **One command in flight.** A [Mutex] serializes [execute], so a second caller waits instead of
 *   interleaving bytes with the first exchange.
 * - **Reassembly.** Fragments are accumulated across transport chunk boundaries; a response split
 *   awkwardly (e.g. `"41 00"` / `" BE 3F"` / `"\r\r>"`) still yields one string.
 * - **Normalization.** The echoed command, line endings, blank lines and all whitespace are
 *   removed, so `"41 00 BE 3F"` becomes `"4100BE3F"`. The `SEARCHING...` banner is noise, not data.
 * - **Typed failures.** Adapter error tokens become [ElmException] subtypes; a missing prompt is a
 *   [ElmException.Timeout] (or [ElmException.LinkClosed] if the stream itself ended), never an empty
 *   success.
 *
 * ### What this class must not do
 * - **No vehicle requests of its own.** It transmits exactly the string it is given. It never
 *   decides to send a PID, a mode, or an init sequence; that is [ElmInitializer]'s job and, later,
 *   the OBD engine's — and only once `docs/VEHICLE_MATRIX.md` is verified.
 * - **No header guessing.** It has no idea what `ATSH` is and never emits one.
 *
 * ### Known limits
 * Bytes buffered *between* commands are not drained, so unsolicited adapter chatter would be
 * interpreted as the prefix of the next response. Acceptable while the engine only runs known AT
 * commands; revisit when the OBD layer starts polling.
 *
 * @param timeoutMillis default per-command budget for the prompt to arrive.
 */
class ElmChannel(
    private val transport: ByteTransport,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {

    private val gate = Mutex()

    /**
     * Sends [command] and returns its normalized response.
     *
     * @param timeoutMillis overrides the channel default for this call only (e.g. the long reset).
     * @return the response with echo, line endings and whitespace removed; may be empty when the
     *   adapter answers with only a prompt.
     * @throws ElmException if the adapter reports an error token or the prompt never arrives.
     * @throws IllegalArgumentException if [command] is blank or contains a line terminator (which
     *   would inject a second command).
     */
    suspend fun execute(command: String, timeoutMillis: Long? = null): String {
        val normalizedCommand = command.trim()
        require(normalizedCommand.isNotEmpty()) { "ELM command must not be blank" }
        require(normalizedCommand.none { it == '\r' || it == '\n' }) {
            "ELM command must not contain a line terminator: \"$command\""
        }
        val effectiveTimeout = timeoutMillis ?: this.timeoutMillis

        return gate.withLock {
            transport.write((normalizedCommand + TERMINATOR).encodeToByteArray())
            interpret(normalizedCommand, awaitResponse(normalizedCommand, effectiveTimeout))
        }
    }

    /**
     * Collects fragments until the prompt appears, and returns everything before that prompt.
     *
     * A timeout discards the partial response on purpose: half a reply is not a reply, and returning
     * it would invite callers to parse it anyway.
     */
    private suspend fun awaitResponse(command: String, timeoutMillis: Long): String {
        val raw = StringBuilder()
        try {
            withTimeout(timeoutMillis) {
                transport.incomingBytes().first { fragment ->
                    raw.append(fragment.toString(Charsets.ISO_8859_1))
                    raw.indexOf(PROMPT) >= 0
                }
            }
        } catch (timedOut: TimeoutCancellationException) {
            throw ElmException.Timeout(command, timeoutMillis)
        } catch (ended: NoSuchElementException) {
            // The flow completed without ever producing a prompt: the link went away, not the clock.
            throw ElmException.LinkClosed(command)
        }

        val promptAt = raw.indexOf(PROMPT)
        return if (promptAt >= 0) raw.substring(0, promptAt) else raw.toString()
    }

    private fun interpret(command: String, raw: String): String {
        val payload = StringBuilder()
        var echoStillPossible = true

        for (line in raw.split('\r', '\n')) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            // The adapter echoes the command when echo is on (before ATE0 lands).
            if (echoStillPossible && trimmed.equals(command, ignoreCase = true)) {
                echoStillPossible = false
                continue
            }
            echoStillPossible = false

            val upper = trimmed.uppercase()
            errorFor(upper, command, raw)?.let { throw it }

            // Informational banner, not a result.
            if (upper.startsWith(SEARCHING)) continue

            payload.append(trimmed.filterNot { it.isWhitespace() })
        }

        return payload.toString()
    }

    private fun errorFor(line: String, command: String, raw: String): ElmException? = when {
        line == QUESTION_MARK -> ElmException.UnknownCommand(command, raw)
        line.startsWith("NO DATA") -> ElmException.NoData(command)
        line.startsWith("UNABLE TO CONNECT") -> ElmException.UnableToConnect(command)
        line.startsWith("BUS ERROR") -> ElmException.BusError(command)
        line.startsWith("CAN ERROR") -> ElmException.CanError(command)
        line.startsWith("BUFFER FULL") -> ElmException.BufferFull(command)
        line.startsWith("STOPPED") -> ElmException.Stopped(command)
        else -> null
    }

    companion object {
        /** Default budget for the adapter's prompt to arrive. */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 2_000L

        /** Terminates every ELM command. */
        const val TERMINATOR: Char = '\r'

        /** The character an ELM327 prints when it is ready for the next command. */
        const val PROMPT: Char = '>'

        private const val QUESTION_MARK = "?"
        private const val SEARCHING = "SEARCHING"
    }
}
