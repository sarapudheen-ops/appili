package com.innova.diag.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.innova.diag.adapter.elm.ElmActiveTestChannel
import com.innova.diag.adapter.elm.ElmChannel
import com.innova.diag.domain.active.ActiveTestController
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
        holdJob = viewModelScope.launch {
            try {
                controller.hold(mutableState.value.command)
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
