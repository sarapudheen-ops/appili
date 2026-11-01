package com.innova.diag.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.innova.diag.adapter.elm.ElmActiveTestChannel
import com.innova.diag.adapter.elm.ElmChannel
import com.innova.diag.domain.active.ActiveTestController
import com.innova.diag.domain.gate.AuditLog
import com.innova.diag.domain.gate.GateRequest
import com.innova.diag.domain.gate.OperationGate
import com.innova.diag.domain.gate.OperationTarget
import com.innova.diag.domain.profile.RiskClass
import com.innova.diag.domain.active.MockEcuSimulator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the bench link and the dead-man switch for [SimulatedActuatorTestScreen].
 *
 * There is no Bluetooth here on purpose: the only transport this view model can create is
 * [MockEcuSimulator], which is not a `PhysicalTransport`, so [ActiveTestController] will start. The
 * hardware lock is not a UI decision that could be flipped by a flag — a physical transport cannot be
 * passed to this screen's controller at all, because nothing in this graph can produce one.
 *
 * The hold is a coroutine: [holdStart] launches [ActiveTestController.hold], [holdEnd] cancels it. The
 * controller's `finally` block sends ReturnControlToECU either way, so a release that arrives while the
 * UI is recomposing still ends the test.
 */
class ActiveTestViewModel : ViewModel() {

    private val simulator = MockEcuSimulator()

    private val controller = ActiveTestController(
        transport = simulator,
        channel = ElmActiveTestChannel(ElmChannel(simulator)),
    )

    private val mutableState = MutableStateFlow(ActiveTestUiState())
    val state: StateFlow<ActiveTestUiState> = mutableState.asStateFlow()

    /**
     * The same gate the driving session uses, pointed at the bench.
     *
     * [ActiveTestController] already refuses to be constructed over a physical transport; asking the
     * gate as well means the rule is expressed once, in one place, for every operation — and that a
     * bench actuation leaves the same audit entry a refused one would.
     */
    private val gate = OperationGate()

    /** Every actuation decision taken on this screen, permit or refusal. */
    val audit: AuditLog get() = gate.audit

    private var holdJob: Job? = null

    init {
        viewModelScope.launch { simulator.connect() }
        viewModelScope.launch {
            simulator.state().collect { link ->
                mutableState.update { ui -> ui.copy(transportState = link) }
            }
        }
        viewModelScope.launch {
            controller.state.collect { test ->
                mutableState.update { ui -> ui.copy(test = test) }
            }
        }
    }

    /** The operator pressed and is holding. */
    fun holdStart() {
        if (holdJob?.isActive == true) return

        val command = mutableState.value.command
        val frame = command.activationFrame()
        val allowed = gate.evaluate(
            GateRequest(
                operationId = "active_test." + frame.lowercase(),
                riskClass = RiskClass.ACTIVE_TEST,
                service = frame.take(2).toIntOrNull(16),
                target = OperationTarget.Bench,
            ),
            simulator,
        ).allowed
        // A refusal means nothing was queued: the controller is never called, so no frame can reach
        // even the simulator.
        if (!allowed) return

        holdJob = viewModelScope.launch {
            try {
                controller.hold(command)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: IllegalStateException) {
                // "already held" is the only refusal this call can raise, and the controller has
                // already declined to transmit. Nothing is latched, so there is nothing to clean up.
            }
        }
    }

    /** The operator released (or the gesture was cancelled). */
    fun holdEnd() {
        holdJob?.cancel()
        holdJob = null
    }

    /** Pulls the plug on the mock, the way a Bluetooth drop would. */
    fun dropLink() {
        simulator.simulateDisconnect()
    }

    /** Brings the bench ECU back so the screen can be used again. */
    fun reconnect() {
        viewModelScope.launch { simulator.connect() }
    }
}
