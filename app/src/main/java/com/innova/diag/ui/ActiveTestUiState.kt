package com.innova.diag.ui

import com.innova.diag.core.transport.TransportState
import com.innova.diag.domain.active.ActiveTestCommand
import com.innova.diag.domain.active.ActiveTestOutcome
import com.innova.diag.domain.active.ActiveTestState

/**
 * What the Simulated Actuator Test screen renders.
 *
 * Kept as plain data plus the pure functions in [ActiveTestPresentation] so the wording — including the
 * distinction between "control returned" and "not acknowledged" — is unit-testable without Compose.
 */
data class ActiveTestUiState(
    val command: ActiveTestCommand = ActiveTestCommand.RADIATOR_FAN_RELAY,
    val transportState: TransportState = TransportState.DISCONNECTED,
    val test: ActiveTestState = ActiveTestState.Idle,
) {
    val connected: Boolean get() = transportState == TransportState.CONNECTED

    val running: Boolean get() = test.isRunning

    /** The hold only makes sense on a live bench link; the controller refuses otherwise anyway. */
    val canHold: Boolean get() = connected

    /** `"7.4 s"` while running, `null` otherwise. */
    val countdown: String?
        get() = (test as? ActiveTestState.Running)?.let { runningState ->
            ActiveTestPresentation.remainingText(runningState.remainingMillis)
        }

    /** 0..1 for the progress bar; 0 when idle. */
    val progress: Float
        get() = (test as? ActiveTestState.Running)?.fractionElapsed ?: 0f

    val statusLine: String get() = ActiveTestPresentation.statusText(test, connected)
}

/**
 * The screen's fixed wording, in one place.
 *
 * The banner is a constant, not a string built at runtime: it is a promise to the operator, and a test
 * asserts it character for character.
 */
object ActiveTestPresentation {

    /** Required verbatim on the screen. */
    const val BANNER: String = "SIMULATOR MODE — NO PHYSICAL COMMANDS"

    const val SUBTITLE: String =
        "Everything here talks to an in-process mock ECU (UDS 2F / 31). No Bluetooth socket is opened, " +
            "and the controller refuses a physical link outright."

    /** The hold instruction, spelled out so nobody looks for a start/stop pair. */
    const val HOLD_INSTRUCTION: String = "Press and hold to actuate · release to return control"

    /** The automatic cut-off, as shown next to the button. */
    const val CEILING_LABEL: String = "Hard cut-off at 10.0 s"

    /** `"9.3 s"` — one decimal, locale-free, never negative. */
    fun remainingText(remainingMillis: Long): String {
        val tenths = ((remainingMillis.coerceAtLeast(0L) + 50L) / 100L)
        return "${tenths / 10}.${tenths % 10} s"
    }

    /** `"Auto-cutoff in 9.3 s"`. */
    fun countdownText(remainingMillis: Long): String =
        "Auto-cutoff in ${remainingText(remainingMillis)}"

    /**
     * One honest sentence per state.
     *
     * The interesting cases are the two where the ECU did **not** acknowledge ReturnControlToECU: the
     * screen says so rather than reporting a tidy "stopped", because a latched relay with an
     * unacknowledged release is precisely what the operator needs to know about.
     */
    fun statusText(state: ActiveTestState, connected: Boolean): String = when (state) {
        ActiveTestState.Idle -> if (connected) {
            "Ready — press and hold to take control of the actuator."
        } else {
            "Not connected — press Reconnect before holding the button."
        }

        is ActiveTestState.Running -> "Holding — release the button to return control to the ECU."

        is ActiveTestState.Finished -> when (state.outcome) {
            ActiveTestOutcome.RELEASED -> if (state.controlReturned) {
                "Released — the ECU acknowledged ReturnControlToECU."
            } else {
                "Released — the ECU did not acknowledge ReturnControlToECU."
            }

            ActiveTestOutcome.CUT_OFF -> if (state.controlReturned) {
                "Auto-cutoff at 10.0 s — the ECU acknowledged ReturnControlToECU."
            } else {
                "Auto-cutoff at 10.0 s — the return-control frame was not acknowledged."
            }

            ActiveTestOutcome.ABORTED_ON_DISCONNECT -> if (state.controlReturned) {
                "Link lost — test aborted, control returned to the ECU."
            } else {
                "Link lost — test aborted; the return-control frame could not be delivered."
            }

            ActiveTestOutcome.REFUSED_NOT_CONNECTED ->
                "Refused — the simulated link was not connected, so nothing was transmitted."

            ActiveTestOutcome.REFUSED_BY_ECU ->
                "Refused — the simulated ECU did not accept the activation."
        }
    }
}
