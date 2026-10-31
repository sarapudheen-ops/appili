package com.innova.diag.domain.active

import com.innova.diag.domain.obd.DiagnosticResult

/**
 * How an active test ended. Every value except [RELEASED], [CUT_OFF] and [ABORTED_ON_DISCONNECT] means
 * the test never ran.
 */
enum class ActiveTestOutcome {

    /** The operator let go of the dead-man switch; control was returned. */
    RELEASED,

    /** The hard ceiling was reached and the controller cut the test off by itself. */
    CUT_OFF,

    /** The link left `CONNECTED`, so the test was aborted and the stop routine ran. */
    ABORTED_ON_DISCONNECT,

    /** Refused: the transport was not `CONNECTED`, so nothing was transmitted. */
    REFUSED_NOT_CONNECTED,

    /** Refused: the ECU answered the activation with a negative response; nothing was ever latched. */
    REFUSED_BY_ECU,
}

/**
 * The controller's observable state.
 *
 * [isSafe] is the question the UI and the tests actually ask: is anything possibly still actuated? Only
 * [Running] is unsafe, and the controller leaves [Running] through exactly one `finally` block that
 * sends ReturnControlToECU.
 */
sealed interface ActiveTestState {

    /** Nothing has run. Safe. */
    data object Idle : ActiveTestState

    /**
     * The test is latched and the dead-man switch is held.
     *
     * @param remainingMillis countdown to the automatic cut-off, for the UI.
     */
    data class Running(
        val command: ActiveTestCommand,
        val remainingMillis: Long,
        val durationMillis: Long,
    ) : ActiveTestState {

        /** Fraction of the ceiling already used, for a progress bar. Never outside 0..1. */
        val fractionElapsed: Float
            get() = if (durationMillis <= 0L) {
                1f
            } else {
                ((durationMillis - remainingMillis).toFloat() / durationMillis).coerceIn(0f, 1f)
            }
    }

    /**
     * The test is over.
     *
     * @param returnControl what the ReturnControlToECU exchange produced — `null` when nothing was ever
     *   latched. This is deliberately kept: on a link drop the frame cannot be delivered, and the UI
     *   must be able to say "not acknowledged" instead of claiming control was returned.
     */
    data class Finished(
        val command: ActiveTestCommand,
        val outcome: ActiveTestOutcome,
        val returnControl: DiagnosticResult<String>? = null,
    ) : ActiveTestState {

        /** True when the ECU acknowledged ReturnControlToECU. */
        val controlReturned: Boolean get() = returnControl?.dataOrNull != null
    }

    /** True unless the test is currently latched. */
    val isSafe: Boolean get() = this !is Running

    /** True while an actuator may be actuated. */
    val isRunning: Boolean get() = this is Running
}
