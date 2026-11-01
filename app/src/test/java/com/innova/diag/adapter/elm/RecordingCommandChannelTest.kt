package com.innova.diag.adapter.elm

import com.innova.diag.core.transport.assertThrowsSuspend
import com.innova.diag.domain.session.SessionLog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the session log is allowed to claim.
 *
 * The dangerous mistake is logging a command that never left the phone, or logging a failure as if it
 * were an answer. Both are asserted against here.
 */
class RecordingCommandChannelTest {

    @Test
    fun `a successful exchange is recorded with its response and timestamps`() = runTest {
        val log = SessionLog(clock = { 0L })
        val channel = RecordingCommandChannel(
            delegate = FixedChannel("ELM327 v1.5"),
            log = log,
            clock = { 4_242L },
        )

        val response = channel.execute("ATI")

        assertEquals("ELM327 v1.5", response)
        val entry = log.snapshot().entries.single()
        assertEquals("ATI", entry.command)
        assertEquals("ELM327 v1.5", entry.response)
        assertNull(entry.failure)
        assertEquals(4_242L, entry.sentAtMillis)
        assertEquals(4_242L, entry.receivedAtMillis)
    }

    @Test
    fun `an adapter failure is recorded with a stable token and rethrown`() = runTest {
        val log = SessionLog(clock = { 0L })
        val channel = RecordingCommandChannel(
            delegate = FailingChannel { command -> ElmException.NoData(command) },
            log = log,
        )

        assertThrowsSuspend<ElmException.NoData> { channel.execute("0105") }

        val entry = log.snapshot().entries.single()
        assertEquals("0105", entry.command)
        assertNull("no response may be invented", entry.response)
        assertEquals("NO_DATA", entry.failure)
        assertTrue(!entry.answered)
    }

    @Test
    fun `each failure type gets its own token`() = runTest {
        val expectations = listOf<Pair<ElmException, String>>(
            ElmException.NoData("X") to "NO_DATA",
            ElmException.Timeout("X", 2_000L) to "TIMEOUT",
            ElmException.LinkClosed("X") to "LINK_CLOSED",
            ElmException.BusError("X") to "BUS_ERROR",
            ElmException.UnknownCommand("X", "?") to "REJECTED",
        )

        for ((failure, expectedToken) in expectations) {
            assertEquals(expectedToken, failure.token())
        }
    }

    @Test
    fun `a command the channel refused before writing is not logged`() = runTest {
        val log = SessionLog(clock = { 0L })
        val channel = RecordingCommandChannel(
            delegate = RejectingChannel(),
            log = log,
        )

        assertThrowsSuspend<IllegalArgumentException> { channel.execute("ATZ\rATE0") }

        assertEquals(
            "nothing reached the adapter, so nothing may be logged",
            0,
            log.size,
        )
    }
}

private class FixedChannel(private val response: String) : ElmCommandChannel {
    override suspend fun execute(
        command: String,
        timeoutMillis: Long?,
        normalization: ElmResponseNormalization,
    ): String = response
}

private class FailingChannel(private val failure: (String) -> ElmException) : ElmCommandChannel {
    override suspend fun execute(
        command: String,
        timeoutMillis: Long?,
        normalization: ElmResponseNormalization,
    ): String = throw failure(command)
}

/** Mimics [ElmChannel]'s own validation: a blank or multi-line command never reaches the wire. */
private class RejectingChannel : ElmCommandChannel {
    override suspend fun execute(
        command: String,
        timeoutMillis: Long?,
        normalization: ElmResponseNormalization,
    ): String {
        require(command.none { it == '\r' || it == '\n' }) { "ELM command must not contain a line terminator" }
        return "OK"
    }
}
