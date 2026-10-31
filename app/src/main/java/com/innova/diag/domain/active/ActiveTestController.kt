package com.innova.diag.domain.active

import com.innova.diag.core.transport.ByteTransport
import com.innova.diag.core.transport.PhysicalTransport
import com.innova.diag.core.transport.TransportState
import com.innova.diag.domain.obd.DiagnosticResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The safety state machine for a bidirectional active test — **bench only**.
 *
 * ### Dead-man switch
 * There is no `start()` and no `stop()`. There is one suspending [hold] call that runs while the
 * caller's coroutine is alive: the operator's finger is the coroutine. Release the button, cancel the
 * job, and the test ends — the same gesture cannot be forgotten, which is exactly why the control is
 * modelled this way instead of as a boolean the UI could leave `true`.
 *
 * ### Three ways out, one exit
 * Every exit path — release, ceiling, link loss — leaves through the single `finally` block that sends
 * `ReturnControlToECU` under [NonCancellable]. A cancelled coroutine still runs `finally`, and
 * [NonCancellable] means the "hand control back" frame is not itself cancelled half-way through. That
 * combination is the whole safety argument, so it must not be restructured into `onStop` callbacks.
 *
 * 1. **Release** — the caller cancels the job; `finally` runs, outcome `RELEASED`.
 * 2. **Ceiling** — a countdown reaches [maxDurationMillis] (hard maximum [MAX_DURATION_MILLIS] = 10 s,
 *    enforced in the constructor); outcome `CUT_OFF`.
 * 3. **Link loss** — [ByteTransport.state] leaves `CONNECTED` for any reason; outcome
 *    `ABORTED_ON_DISCONNECT`. The abort is immediate, not on the next tick.
 *
 * ### HARDWARE LOCK
 * Constructing a controller over a [PhysicalTransport] throws
 * `IllegalStateException("Physical execution disabled")`. The check is at construction rather than at
 * [hold] because a controller over real hardware should not exist at all — there is no mode of this
 * class that is safe on a vehicle (`AGENTS.md` Rule 2, `docs/PRODUCT_SCOPE.md` §4.1).
 *
 * The domain knows a transport is physical through the [PhysicalTransport] marker, not by importing
 * `platform.bluetooth`, so `BluetoothClassicTransport` is locked out without this package depending on
 * Android (A27).
 *
 * @param transport the link to watch for state changes; also what the hardware lock inspects.
 * @param channel the transmission seam; `ElmActiveTestChannel` in production.
 * @param maxDurationMillis the hard ceiling, at most [MAX_DURATION_MILLIS]. Shorter is allowed (a test
 *   may be extra cautious); longer is not.
 */
class ActiveTestController(
    private val transport: ByteTransport,
    private val channel: ActiveTestChannel,
    private val maxDurationMillis: Long = MAX_DURATION_MILLIS,
) {

    init {
        if (transport is PhysicalTransport) {
            throw IllegalStateException("Physical execution disabled")
        }
        require(maxDurationMillis in 1..MAX_DURATION_MILLIS) {
            "an active test may not run longer than $MAX_DURATION_MILLIS ms " +
                "(asked for $maxDurationMillis); the ceiling is not configurable upward"
        }
    }

    private val mutableState = MutableStateFlow<ActiveTestState>(ActiveTestState.Idle)

    /** Observable state, for the UI and for tests. Always has a value. */
    val state: StateFlow<ActiveTestState> = mutableState.asStateFlow()

    /**
     * Holds the actuator while this coroutine lives, then returns control to the ECU.
     *
     * Send the activation, supervise the hold, and — whatever happens next — send
     * `ReturnControlToECU` in a `finally` block.
     *
     * @return why the test ended, when it ends normally ([ActiveTestOutcome.CUT_OFF],
     *   [ActiveTestOutcome.ABORTED_ON_DISCONNECT], or a refusal).
     * @throws kotlinx.coroutines.CancellationException when the caller cancels (the dead-man release).
     *   The stop routine has already run by then; read [state] for the recorded outcome.
     * @throws IllegalStateException when a test is already being held.
     */
    suspend fun hold(command: ActiveTestCommand): ActiveTestOutcome {
        if (mutableState.value.isRunning) {
            throw IllegalStateException("an active test is already held; release it before starting another")
        }
        if (transport.state().value != TransportState.CONNECTED) {
            // Nothing is latched, so there is nothing to give back — and nothing was transmitted.
            mutableState.value = ActiveTestState.Finished(
                command = command,
                outcome = ActiveTestOutcome.REFUSED_NOT_CONNECTED,
            )
            return ActiveTestOutcome.REFUSED_NOT_CONNECTED
        }

        val activation = exchangeOrFailure(command.activationFrame())
        if (!activationAccepted(command, activation)) {
            // The ECU never accepted the request, so nothing should be latched. Send the release frame
            // anyway: one benign exchange is cheaper than any doubt about the ECU's state.
            val returned = exchangeOrFailure(command.returnControlFrame())
            mutableState.value = ActiveTestState.Finished(
                command = command,
                outcome = ActiveTestOutcome.REFUSED_BY_ECU,
                returnControl = returned,
            )
            return ActiveTestOutcome.REFUSED_BY_ECU
        }

        mutableState.value = ActiveTestState.Running(
            command = command,
            remainingMillis = maxDurationMillis,
            durationMillis = maxDurationMillis,
        )
        var outcome = ActiveTestOutcome.RELEASED
        try {
            outcome = supervise(command)
        } finally {
            // The one exit. NonCancellable keeps the frame going out even when we got here by being
            // cancelled, and the state update needs no suspension so it cannot be skipped either.
            withContext(NonCancellable) {
                val returned = exchangeOrFailure(command.returnControlFrame())
                mutableState.value = ActiveTestState.Finished(
                    command = command,
                    outcome = outcome,
                    returnControl = returned,
                )
            }
        }
        return outcome
    }

    /**
     * Suspends until the hold ends, and says why.
     *
     * Two children race: a countdown that reaches the ceiling, and a watcher on the transport state. The
     * first to finish decides the outcome; both are cancelled on the way out so neither outlives the
     * hold. Cancellation from *outside* (a release) is not caught here — it propagates to [hold]'s
     * `finally`, which is where the stop routine lives.
     */
    private suspend fun supervise(command: ActiveTestCommand): ActiveTestOutcome = coroutineScope {
        val decision = CompletableDeferred<ActiveTestOutcome>()

        val countdown = launch {
            var remaining = maxDurationMillis
            while (remaining > 0L) {
                val step = minOf(TICK_MILLIS, remaining)
                delay(step)
                remaining -= step
                mutableState.value = ActiveTestState.Running(
                    command = command,
                    remainingMillis = remaining,
                    durationMillis = maxDurationMillis,
                )
            }
            decision.complete(ActiveTestOutcome.CUT_OFF)
        }

        val linkWatcher = launch {
            transport.state().collect { linkState ->
                if (linkState != TransportState.CONNECTED) {
                    decision.complete(ActiveTestOutcome.ABORTED_ON_DISCONNECT)
                }
            }
        }

        try {
            decision.await()
        } finally {
            countdown.cancel()
            linkWatcher.cancel()
        }
    }

    /**
     * True only when the ECU answered the activation with its **positive** response.
     *
     * A negative response (`7F <SID> <NRC>`), silence, a timeout or an unusable answer all mean the same
     * thing here: nothing is latched, so there is nothing to hold and nothing to time out. Accepting
     * "any non-empty answer" would treat `7F 2F 31` (requestOutOfRange) as a successful actuation,
     * which is the kind of optimism this repository exists to avoid.
     */
    private fun activationAccepted(
        command: ActiveTestCommand,
        response: DiagnosticResult<String>,
    ): Boolean {
        val text = response.dataOrNull ?: return false
        val hex = text.filterNot { character -> character.isWhitespace() }.uppercase()
        val service = command.activationFrame().substring(0, 2).toInt(16)
        return hex.startsWith(hex8(service + 0x40))
    }

    /**
     * Sends one frame, turning anything that is not a cancellation into a result.
     *
     * A dead link makes transports throw rather than accept bytes, and an exception escaping the stop
     * routine would replace the real outcome with a stack trace. Cancellation is rethrown: it is the
     * dead-man release, not a vehicle condition.
     */
    private suspend fun exchangeOrFailure(frame: String): DiagnosticResult<String> = try {
        channel.exchange(frame)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failed: Throwable) {
        DiagnosticResult.Malformed("${failed::class.java.simpleName}: ${failed.message ?: "exchange failed"}")
    }

    companion object {

        /** The hard ceiling: an active test may never run longer than ten seconds. */
        const val MAX_DURATION_MILLIS: Long = 10_000L

        /** Countdown granularity; the UI shows one decimal, so 100 ms is a tenth of a second. */
        const val TICK_MILLIS: Long = 100L
    }
}
