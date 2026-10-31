package com.innova.diag.adapter.elm

import com.innova.diag.domain.obd.DiagnosticResult
import com.innova.diag.domain.obd.ObdCommandPort

/**
 * Binds the domain's [ObdCommandPort] to a live [ElmChannel] (or to anything else implementing
 * [ElmCommandChannel], such as the recording wrapper).
 *
 * This is the seam where an adapter-level failure becomes a domain-level result, and it is the only
 * place in the codebase that knows both vocabularies. The mapping is deliberately many-to-one in one
 * direction and never loses the distinction that matters:
 *
 * | [ElmException] | [DiagnosticResult] |
 * | --- | --- |
 * | `Timeout` | `Timeout` |
 * | `NoData`, `UnableToConnect`, `BusError`, `CanError`, `BufferFull`, `Stopped`, `LinkClosed` | `NoResponse` |
 * | `UnknownCommand` | `Malformed` (we sent something the adapter does not accept — our bug, not the car's) |
 *
 * An empty answer (the adapter printed only its prompt) is also `NoResponse`: a prompt is not data.
 * Cancellation is **not** caught — a cancelled coroutine is not a vehicle condition, and turning it
 * into a result would let callers treat an interrupted exchange as a reading.
 */
class ElmObdCommandPort(private val channel: ElmCommandChannel) : ObdCommandPort {

    override suspend fun execute(command: String): DiagnosticResult<String> {
        val text = try {
            channel.execute(command)
        } catch (failure: ElmException) {
            return failure.toDiagnosticResult()
        }
        return if (text.isEmpty()) {
            DiagnosticResult.NoResponse
        } else {
            DiagnosticResult.Success(text, text)
        }
    }
}

/** Maps an adapter failure onto the domain's five-valued result; see [ElmObdCommandPort]. */
internal fun ElmException.toDiagnosticResult(): DiagnosticResult<Nothing> = when (this) {
    is ElmException.Timeout -> DiagnosticResult.Timeout
    is ElmException.UnknownCommand -> DiagnosticResult.Malformed(rawResponse.trim())
    is ElmException.NoData,
    is ElmException.UnableToConnect,
    is ElmException.BusError,
    is ElmException.CanError,
    is ElmException.BufferFull,
    is ElmException.Stopped,
    is ElmException.LinkClosed -> DiagnosticResult.NoResponse
}
