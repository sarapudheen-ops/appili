package com.innova.diag.domain.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The log is evidence: it must record exactly what happened and hand out copies, not live state. */
class SessionLogTest {

    @Test
    fun `entries are numbered in order and keep their outcome`() {
        val log = SessionLog(clock = { 1_000L })

        log.record("ATZ", 1_010L, "ELM327 v1.5", null, 1_020L)
        log.record("0105", 1_030L, null, "NO_DATA", 1_040L)

        val entries = log.snapshot().entries
        assertEquals(2, log.size)
        assertEquals(listOf(1, 2), entries.map { it.index })
        assertEquals(listOf("ATZ", "0105"), entries.map { it.command })
        assertEquals("ELM327 v1.5", entries[0].response)
        assertTrue(entries[0].answered)
        assertNull(entries[1].response)
        assertEquals("NO_DATA", entries[1].failure)
        assertTrue(!entries[1].answered)
    }

    @Test
    fun `a snapshot is a copy and does not grow with the log`() {
        val log = SessionLog(clock = { 1_000L })
        log.record("ATI", 1_000L, "ELM327 v1.5", null, 1_001L)
        val snapshot = log.snapshot()

        log.record("010C", 1_002L, "410C1A20", null, 1_003L)

        assertEquals(1, snapshot.entries.size)
        assertEquals(2, log.size)
        assertEquals(1_000L, snapshot.startedAtMillis)
    }

    @Test
    fun `adapter info is carried into the snapshot and cleared on reset`() {
        val log = SessionLog(clock = { 1_000L })
        log.recordAdapterInfo(identity = "ELM327 v1.5", protocol = "AUTO")

        assertEquals("ELM327 v1.5", log.snapshot().adapterIdentity)
        assertEquals("AUTO", log.snapshot().protocol)

        log.clear()

        assertEquals(0, log.size)
        assertNull(log.snapshot().adapterIdentity)
        assertNull(log.snapshot().protocol)
    }
}
