package com.innova.diag.domain.active

import com.innova.diag.adapter.elm.ElmActiveTestChannel
import com.innova.diag.adapter.elm.ElmChannel
import com.innova.diag.core.transport.PhysicalTransport
import com.innova.diag.core.transport.TransportState
import com.innova.diag.domain.obd.DiagnosticResult
import com.innova.diag.platform.bluetooth.BluetoothClassicTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dead-man switch, the ceiling and the abort listener — the three ways an active test can end, and
 * the one exit every one of them takes.
 *
 * These run against the real [MockEcuSimulator] through a real [ElmChannel], so "sends the
 * return-control sequence" is asserted as **bytes the bench ECU received**, not as a method call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveTestControllerTest {

    @Test
    fun `releasing the dead-man switch sends the return-control sequence`() = runTest {
        val simulator = MockEcuSimulator().apply { connect() }
        val recorder = RecordingChannel(ElmActiveTestChannel(ElmChannel(simulator)))
        val controller = ActiveTestController(simulator, recorder)

        val holding = launch { controller.hold(ActiveTestCommand.RADIATOR_FAN_RELAY) }
        testScheduler.advanceTimeBy(500L)
        testScheduler.runCurrent()
        assertTrue("the test should be latched while held", controller.state.value.isRunning)

        holding.cancelAndJoin() // the operator lets go

        assertEquals(
            "activation, then ReturnControlToECU — in that order",
            listOf("2F20010301", "2F200100"),
            recorder.frames,
        )
        assertEquals(
            "and the bytes really reached the ECU",
            listOf("2F20010301", "2F200100"),
            simulator.frames,
        )

        val finished = controller.state.value as ActiveTestState.Finished
        assertEquals(ActiveTestOutcome.RELEASED, finished.outcome)
        assertTrue("the ECU acknowledged the release", finished.controlReturned)
        assertTrue(controller.state.value.isSafe)
    }

    @Test
    fun `the hard ceiling cuts the test off at ten seconds`() = runTest {
        val simulator = MockEcuSimulator().apply { connect() }
        val recorder = RecordingChannel(ElmActiveTestChannel(ElmChannel(simulator)))
        val controller = ActiveTestController(simulator, recorder)
        val startedAt = testScheduler.currentTime

        val outcome = controller.hold(ActiveTestCommand.RADIATOR_FAN_RELAY)

        assertEquals(ActiveTestOutcome.CUT_OFF, outcome)
        val elapsed = testScheduler.currentTime - startedAt
        assertTrue("the ceiling must not exceed 10 s, took $elapsed ms", elapsed <= 10_000L)
        assertEquals(listOf("2F20010301", "2F200100"), recorder.frames)
        assertEquals(ActiveTestOutcome.CUT_OFF, (controller.state.value as ActiveTestState.Finished).outcome)
        assertTrue(controller.state.value.isSafe)
    }

    @Test
    fun `a shorter ceiling is honoured`() = runTest {
        val simulator = MockEcuSimulator().apply { connect() }
        val recorder = RecordingChannel(ElmActiveTestChannel(ElmChannel(simulator)))
        val controller = ActiveTestController(simulator, recorder, maxDurationMillis = 500L)
        val startedAt = testScheduler.currentTime

        val outcome = controller.hold(ActiveTestCommand.RADIATOR_FAN_RELAY)

        assertEquals(ActiveTestOutcome.CUT_OFF, outcome)
        assertEquals(500L, testScheduler.currentTime - startedAt)
        assertEquals(listOf("2F20010301", "2F200100"), recorder.frames)
    }

    @Test
    fun `a ceiling above ten seconds is refused at construction`() {
        val error = runCatching {
            ActiveTestController(
                MockEcuSimulator(),
                ActiveTestChannel { DiagnosticResult.NoResponse },
                maxDurationMillis = 10_001L,
            )
        }.exceptionOrNull()

        assertTrue("expected a refusal, got $error", error is IllegalArgumentException)
    }

    @Test
    fun `a physical transport is refused at construction`() {
        val error = runCatching {
            ActiveTestController(
                FakePhysicalTransport(),
                ActiveTestChannel { DiagnosticResult.NoResponse },
            )
        }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
        assertEquals("Physical execution disabled", error?.message)
    }

    @Test
    fun `BluetoothClassicTransport is marked as a physical link`() {
        // The lock only protects a real adapter if the real adapter carries the marker.
        assertTrue(PhysicalTransport::class.java.isAssignableFrom(BluetoothClassicTransport::class.java))
    }

    @Test
    fun `the mock simulator is not a physical link`() {
        assertFalse(MockEcuSimulator() is PhysicalTransport)
    }

    @Test
    fun `a simulated Bluetooth drop aborts the test and runs the stop routine`() = runTest {
        val simulator = MockEcuSimulator().apply { connect() }
        val recorder = RecordingChannel(ElmActiveTestChannel(ElmChannel(simulator)))
        val controller = ActiveTestController(simulator, recorder)

        val holding = launch { controller.hold(ActiveTestCommand.RADIATOR_FAN_RELAY) }
        testScheduler.advanceTimeBy(300L)
        testScheduler.runCurrent()
        assertTrue(controller.state.value.isRunning)

        simulator.simulateDisconnect() // the link drops mid-test
        testScheduler.settle()

        val finished = controller.state.value as ActiveTestState.Finished
        assertEquals(ActiveTestOutcome.ABORTED_ON_DISCONNECT, finished.outcome)
        assertTrue("the abort must reach a safe state", controller.state.value.isSafe)
        assertTrue("the hold job must have finished", holding.isCompleted)

        // The stop routine is invoked even though the link is already gone: the attempt is the
        // requirement, and the honest record is that it was not acknowledged.
        assertEquals(listOf("2F20010301", "2F200100"), recorder.frames)
        assertFalse("a dead link cannot acknowledge, and we must not claim it did", finished.controlReturned)

        // A dead link cannot start a new test either.
        val refused = controller.hold(ActiveTestCommand.RADIATOR_FAN_RELAY)
        assertEquals(ActiveTestOutcome.REFUSED_NOT_CONNECTED, refused)
        assertEquals(2, recorder.frames.size)
    }

    @Test
    fun `a link error also aborts the test`() = runTest {
        val simulator = MockEcuSimulator().apply { connect() }
        val recorder = RecordingChannel(ElmActiveTestChannel(ElmChannel(simulator)))
        val controller = ActiveTestController(simulator, recorder)

        val holding = launch { controller.hold(ActiveTestCommand.RADIATOR_FAN_RELAY) }
        testScheduler.advanceTimeBy(300L)
        testScheduler.runCurrent()

        simulator.simulateLinkError(IllegalStateException("rfcomm died"))
        testScheduler.settle()

        val finished = controller.state.value as ActiveTestState.Finished
        assertEquals(ActiveTestOutcome.ABORTED_ON_DISCONNECT, finished.outcome)
        assertTrue(holding.isCompleted)
    }

    @Test
    fun `an unconnected link refuses without transmitting`() = runTest {
        val simulator = MockEcuSimulator() // never connected
        val recorder = RecordingChannel(ElmActiveTestChannel(ElmChannel(simulator)))
        val controller = ActiveTestController(simulator, recorder)

        val outcome = controller.hold(ActiveTestCommand.RADIATOR_FAN_RELAY)

        assertEquals(ActiveTestOutcome.REFUSED_NOT_CONNECTED, outcome)
        assertEquals(emptyList<String>(), recorder.frames)
        assertEquals(emptyList<String>(), simulator.frames)
        assertTrue(controller.state.value.isSafe)
    }

    @Test
    fun `a negative response to the activation is not a latched test`() = runTest {
        val simulator = MockEcuSimulator().apply { connect() }
        // requestOutOfRange for the exact activation frame
        simulator.script("2F20010301", "7F2F31")
        val recorder = RecordingChannel(ElmActiveTestChannel(ElmChannel(simulator)))
        val controller = ActiveTestController(simulator, recorder)

        val outcome = controller.hold(ActiveTestCommand.RADIATOR_FAN_RELAY)

        assertEquals(ActiveTestOutcome.REFUSED_BY_ECU, outcome)
        assertFalse("nothing was latched, so nothing may be reported as running", controller.state.value.isRunning)
        assertTrue(controller.state.value.isSafe)
        assertEquals(
            "the release frame is still sent: one benign exchange beats any doubt",
            listOf("2F20010301", "2F200100"),
            recorder.frames,
        )
    }

    @Test
    fun `a routine command uses the routine equivalent of return control`() = runTest {
        val simulator = MockEcuSimulator().apply { connect() }
        val recorder = RecordingChannel(ElmActiveTestChannel(ElmChannel(simulator)))
        val controller = ActiveTestController(simulator, recorder)
        val routine = ActiveTestCommand.Routine(routineId = 0x0203, label = "Cooling fan routine")

        val holding = launch { controller.hold(routine) }
        testScheduler.advanceTimeBy(400L)
        testScheduler.runCurrent()
        holding.cancelAndJoin()

        assertEquals(listOf("31010203", "31020203"), recorder.frames)
    }

    @Test
    fun `the countdown decreases while held and never goes negative`() = runTest {
        val simulator = MockEcuSimulator().apply { connect() }
        val controller = ActiveTestController(
            simulator,
            ElmActiveTestChannel(ElmChannel(simulator)),
            maxDurationMillis = 1_000L,
        )

        val holding = launch { controller.hold(ActiveTestCommand.RADIATOR_FAN_RELAY) }
        testScheduler.runCurrent()
        val running = controller.state.value as ActiveTestState.Running
        assertEquals(1_000L, running.remainingMillis)

        testScheduler.advanceTimeBy(600L)
        testScheduler.runCurrent()
        val later = controller.state.value as ActiveTestState.Running
        assertEquals(400L, later.remainingMillis)
        assertTrue(later.fractionElapsed in 0f..1f)

        holding.cancelAndJoin()
        assertNotNull(controller.state.value as ActiveTestState.Finished)
    }

    @Test
    fun `a second hold while one is latched is refused`() = runTest {
        val simulator = MockEcuSimulator().apply { connect() }
        val controller = ActiveTestController(simulator, ElmActiveTestChannel(ElmChannel(simulator)))
        val holding = launch { controller.hold(ActiveTestCommand.RADIATOR_FAN_RELAY) }
        testScheduler.advanceTimeBy(300L)
        testScheduler.runCurrent()

        val error = runCatching { controller.hold(ActiveTestCommand.RADIATOR_FAN_RELAY) }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
        holding.cancelAndJoin()
    }

    @Test
    fun `the controller starts idle and safe`() {
        val controller = ActiveTestController(
            MockEcuSimulator(),
            ActiveTestChannel { DiagnosticResult.NoResponse },
        )

        assertEquals(ActiveTestState.Idle, controller.state.value)
        assertTrue(controller.state.value.isSafe)
    }
}

/**
 * Lets scheduled work run until nothing is pending at the current instant.
 *
 * A disconnect reaches the controller through a `StateFlow` emission, and the stop routine then runs
 * from a `finally` that dispatches — so a single `advanceTimeBy` can leave the state one hop short.
 * Several short steps plus `runCurrent()` per step make the assertions independent of that detail.
 */
private fun TestCoroutineScheduler.settle() {
    repeat(4) {
        runCurrent()
        advanceTimeBy(50L)
    }
    runCurrent()
}

/** Wraps a channel and records the frames asked of it, so "attempted" is observable. */
private class RecordingChannel(private val delegate: ActiveTestChannel) : ActiveTestChannel {

    private val log = mutableListOf<String>()

    val frames: List<String> get() = synchronized(log) { log.toList() }

    override suspend fun exchange(frame: String): DiagnosticResult<String> {
        synchronized(log) { log += frame }
        return delegate.exchange(frame)
    }
}

/** A transport that claims to reach a vehicle: everything the hardware lock refuses. */
private class FakePhysicalTransport : PhysicalTransport {

    private val link = MutableStateFlow<TransportState>(TransportState.CONNECTED)

    override fun state(): StateFlow<TransportState> = link.asStateFlow()

    override fun incomingBytes(): Flow<ByteArray> = emptyFlow()

    override suspend fun connect() = Unit

    override suspend fun disconnect() = Unit

    override suspend fun write(data: ByteArray) = Unit
}
