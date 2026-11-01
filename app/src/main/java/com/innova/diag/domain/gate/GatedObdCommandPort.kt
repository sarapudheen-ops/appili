package com.innova.diag.domain.gate

import com.innova.diag.core.transport.ByteTransport
import com.innova.diag.domain.obd.DiagnosticResult
import com.innova.diag.domain.obd.ObdCommandPort

/**
 * A command port that asks [gate] before it transmits anything.
 *
 * ### Why here
 * `OperationGate` must sit at the lowest layer or it is decoration: a view model that checks the gate
 * and then hands a port to a service has two decision points, and the second one wins. Wrapping the
 * port means **one** place decides, and every caller above it — the generic scan, the live-data loop,
 * the diagnostic-codes service, the debug console — inherits the decision without knowing it exists.
 *
 * ### Adapter commands
 * `AT…` frames configure the interface; they never reach the vehicle bus. They are delegated
 * untouched (and are already constrained by `AtCommandGuard` and the ELM allow-list). Everything else
 * is turned into a [GateRequest] by [GateRequest.forFrame] and refused with [GateDeniedException] if
 * the gate says no. A refusal throws instead of returning a [DiagnosticResult] failure, because a
 * refusal is not a vehicle condition and must never be rendered as one ("Not Supported", "No
 * Response", a reading).
 *
 * @property target what the frames are aimed at. Generic sessions use
 *   [OperationTarget.UniversalGeneric], which is the only shape this app ships today.
 * @property sessionReady read on every call, so a port created before the init sequence finishes
 *   becomes usable the moment the session does.
 */
class GatedObdCommandPort(
    private val delegate: ObdCommandPort,
    private val gate: OperationGate,
    private val transport: ByteTransport,
    private val target: OperationTarget = OperationTarget.UniversalGeneric,
    private val sessionReady: () -> Boolean = { true },
) : ObdCommandPort {

    override suspend fun execute(command: String): DiagnosticResult<String> {
        if (isAdapterCommand(command)) return delegate.execute(command)

        val request = GateRequest.forFrame(command, target, sessionReady())
        return when (val decision = gate.evaluate(request, transport)) {
            is GateDecision.Allowed -> delegate.execute(command)
            is GateDecision.Denied -> throw GateDeniedException(decision)
        }
    }

    /** `AT…` configures the adapter, not the car. */
    private fun isAdapterCommand(command: String): Boolean =
        command.trim().uppercase().startsWith("AT")
}
