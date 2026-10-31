package com.innova.diag.adapter.elm

import com.innova.diag.domain.obd.DiagnosticResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ATI` / `ATDP` reading and parsing.
 *
 * The identity and the protocol are the adapter's *claims*. They are shown as such, and an unanswered
 * one is reported, never filled in from a guess.
 */
class AdapterInfoReaderTest {

    @Test
    fun `identity and protocol are read with spaces preserved`() = runTest {
        val channel = ScriptedTextChannel(
            responses = mapOf(
                "ATI" to "ELM327 v1.5",
                "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)",
            ),
        )

        val info = AdapterInfoReader(channel).read()

        assertEquals("ELM327 v1.5", info.identity.dataOrNull)
        assertEquals("AUTO, ISO 15765-4 (CAN 11/500)", info.protocol.dataOrNull)
        assertEquals(
            "whitespace matters for these two commands",
            listOf(ElmResponseNormalization.TEXT, ElmResponseNormalization.TEXT),
            channel.calls.map { call -> call.normalization },
        )
        assertEquals(listOf("ATI", "ATDP"), channel.calls.map { call -> call.command })
    }

    @Test
    fun `the searching banner is dropped from the identity`() = runTest {
        val channel = ScriptedTextChannel(responses = mapOf("ATI" to "SEARCHING... ELM327 v1.5"))

        val info = AdapterInfoReader(channel).read()

        assertEquals("ELM327 v1.5", info.identity.dataOrNull)
    }

    @Test
    fun `an unanswered identity is No Response and an unanswered protocol is No Response`() = runTest {
        val channel = ScriptedTextChannel(
            responses = emptyMap(),
            failures = mapOf("ATDP" to ElmException.NoData("ATDP")),
        )

        val info = AdapterInfoReader(channel).read()

        assertTrue(info.identity is DiagnosticResult.NoResponse)
        assertTrue(info.protocol is DiagnosticResult.NoResponse)
        assertNull(info.identity.dataOrNull)
    }

    @Test
    fun `a timeout stays a timeout`() = runTest {
        val channel = ScriptedTextChannel(
            failures = mapOf("ATI" to ElmException.Timeout("ATI", 2_000L)),
        )

        val info = AdapterInfoReader(channel).read()

        assertTrue(info.identity is DiagnosticResult.Timeout)
    }

    @Test
    fun `blank answers are No Response, not an empty identity`() = runTest {
        val channel = ScriptedTextChannel(responses = mapOf("ATI" to "   "))

        val info = AdapterInfoReader(channel).read()

        assertTrue(info.identity is DiagnosticResult.NoResponse)
    }

    @Test
    fun `auto-detection is recognised from the protocol text`() {
        assertTrue(AdapterInfoText.isAutoDetected("AUTO, ISO 15765-4 (CAN 11/500)"))
        assertTrue(AdapterInfoText.isAutoDetected("auto"))
        assertFalse(AdapterInfoText.isAutoDetected("ISO 15765-4 (CAN 11/500)"))
    }

    @Test
    fun `whitespace inside a line is collapsed but not removed`() {
        assertEquals("ELM327 v1.5", AdapterInfoText.identity("ELM327   v1.5"))
    }
}

/** Returns canned text per command, or throws the scripted adapter failure. */
private class ScriptedTextChannel(
    private val responses: Map<String, String> = emptyMap(),
    private val failures: Map<String, ElmException> = emptyMap(),
) : ElmCommandChannel {

    data class Call(val command: String, val normalization: ElmResponseNormalization)

    val calls = mutableListOf<Call>()

    override suspend fun execute(
        command: String,
        timeoutMillis: Long?,
        normalization: ElmResponseNormalization,
    ): String {
        calls += Call(command, normalization)
        failures[command]?.let { failure -> throw failure }
        return responses[command] ?: ""
    }
}
