package com.innova.diag.ui

import com.innova.diag.domain.active.ActiveTestCommand
import com.innova.diag.domain.active.ActiveTestController
import com.innova.diag.domain.active.ActiveTestOutcome
import com.innova.diag.domain.active.ActiveTestState
import com.innova.diag.domain.obd.DiagnosticResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screen's wording is part of its safety argument, so it is tested like behaviour.
 *
 * The case that matters is the honest one: when the ECU did not acknowledge ReturnControlToECU, the
 * text must say so. A screen that prints "control returned" after a link drop would be a lie with a
 * relay attached to it.
 */
class ActiveTestPresentationTest {

    @Test
    fun `the simulator banner is exactly as specified`() {
        assertEquals("SIMULATOR MODE — NO PHYSICAL COMMANDS", ActiveTestPresentation.BANNER)
    }

    @Test
    fun `the required command is the radiator fan relay`() {
        assertEquals("Radiator Fan Relay Test", ActiveTestCommand.RADIATOR_FAN_RELAY.label)
        assertEquals("2F20010301", ActiveTestCommand.RADIATOR_FAN_RELAY.activationFrame())
        assertEquals("2F200100", ActiveTestCommand.RADIATOR_FAN_RELAY.returnControlFrame())
    }

    @Test
    fun `the countdown is one decimal and never negative`() {
        assertEquals("10.0 s", ActiveTestPresentation.remainingText(10_000L))
        assertEquals("6.4 s", ActiveTestPresentation.remainingText(6_400L))
        assertEquals("0.1 s", ActiveTestPresentation.remainingText(100L))
        assertEquals("0.0 s", ActiveTestPresentation.remainingText(0L))
        assertEquals("0.0 s", ActiveTestPresentation.remainingText(-500L))
        assertEquals("Auto-cutoff in 6.4 s", ActiveTestPresentation.countdownText(6_400L))
    }

    @Test
    fun `the ceiling label states the hard maximum`() {
        assertTrue(ActiveTestPresentation.CEILING_LABEL.contains("10.0"))
        assertEquals(10_000L, ActiveTestController.MAX_DURATION_MILLIS)
    }

    @Test
    fun `an idle connected screen invites the hold`() {
        val text = ActiveTestPresentation.statusText(ActiveTestState.Idle, connected = true)
        assertTrue(text.contains("press and hold"))
    }

    @Test
    fun `an idle disconnected screen says so instead`() {
        val text = ActiveTestPresentation.statusText(ActiveTestState.Idle, connected = false)
        assertTrue(text.contains("Not connected"))
        assertFalse(text.contains("press and hold"))
    }

    @Test
    fun `a running test tells the operator how to stop it`() {
        val text = ActiveTestPresentation.statusText(
            ActiveTestState.Running(
                command = ActiveTestCommand.RADIATOR_FAN_RELAY,
                remainingMillis = 6_000L,
                durationMillis = 10_000L,
            ),
            connected = true,
        )
        assertTrue(text.contains("release"))
    }

    @Test
    fun `an acknowledged release says it was acknowledged`() {
        val text = ActiveTestPresentation.statusText(
            ActiveTestState.Finished(
                command = ActiveTestCommand.RADIATOR_FAN_RELAY,
                outcome = ActiveTestOutcome.RELEASED,
                returnControl = DiagnosticResult.Success("6F200100", "6F200100"),
            ),
            connected = true,
        )
        assertTrue(text.contains("acknowledged"))
        assertFalse(text.contains("did not acknowledge"))
    }

    @Test
    fun `an unacknowledged release says it was not acknowledged`() {
        val text = ActiveTestPresentation.statusText(
            ActiveTestState.Finished(
                command = ActiveTestCommand.RADIATOR_FAN_RELAY,
                outcome = ActiveTestOutcome.RELEASED,
                returnControl = DiagnosticResult.NoResponse,
            ),
            connected = true,
        )
        assertTrue(text.contains("did not acknowledge"))
    }

    @Test
    fun `a link-drop abort never claims control was returned`() {
        val text = ActiveTestPresentation.statusText(
            ActiveTestState.Finished(
                command = ActiveTestCommand.RADIATOR_FAN_RELAY,
                outcome = ActiveTestOutcome.ABORTED_ON_DISCONNECT,
                returnControl = DiagnosticResult.Malformed("IllegalStateException: not connected"),
            ),
            connected = false,
        )
        assertTrue("must be explicit about the failure", text.contains("could not be delivered"))
        assertFalse("must not claim success", text.contains("control returned to the ECU"))
    }

    @Test
    fun `refusals explain themselves`() {
        val notConnected = ActiveTestPresentation.statusText(
            ActiveTestState.Finished(
                command = ActiveTestCommand.RADIATOR_FAN_RELAY,
                outcome = ActiveTestOutcome.REFUSED_NOT_CONNECTED,
            ),
            connected = false,
        )
        assertTrue(notConnected.contains("nothing was transmitted"))

        val refusedByEcu = ActiveTestPresentation.statusText(
            ActiveTestState.Finished(
                command = ActiveTestCommand.RADIATOR_FAN_RELAY,
                outcome = ActiveTestOutcome.REFUSED_BY_ECU,
            ),
            connected = true,
        )
        assertTrue(refusedByEcu.contains("did not accept"))
    }

    @Test
    fun `the ui state exposes the countdown only while running`() {
        val idle = ActiveTestUiState()
        assertEquals(null, idle.countdown)
        assertFalse(idle.running)

        val running = ActiveTestUiState(
            test = ActiveTestState.Running(
                command = ActiveTestCommand.RADIATOR_FAN_RELAY,
                remainingMillis = 7_400L,
                durationMillis = 10_000L,
            ),
        )
        assertEquals("7.4 s", running.countdown)
        assertTrue(running.running)
        assertEquals(0.26f, running.progress, 0.001f)
    }

    @Test
    fun `the hold is only offered on a connected bench link`() {
        assertFalse(ActiveTestUiState().canHold)
        assertTrue(
            ActiveTestUiState(transportState = com.innova.diag.core.transport.TransportState.CONNECTED).canHold,
        )
    }
}
