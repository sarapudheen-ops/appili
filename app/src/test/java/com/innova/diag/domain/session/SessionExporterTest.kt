package com.innova.diag.domain.session

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Export shape and sanitization.
 *
 * Two claims are tested together because they protect the same thing: a log that is *readable* and a
 * log that is *safe to share*. A malformed export is useless; an unsanitized one leaks a VIN.
 */
class SessionExporterTest {

    private val vin = "1M8GDM9AXKP042788"
    private val vinHex = "314D3847444D3941584B50303432373838"
    private val vinResponse = "490201$vinHex"
    private val protocol = "AUTO, ISO 15765-4 (CAN 11/500)"

    private fun logWithEntries(): SessionLog {
        var now = START_MILLIS
        val log = SessionLog(clock = { now })
        log.record("ATI", now, "ELM327 v1.5", null, now + 5)
        now += 10
        log.record("0902", now, vinResponse, null, now + 5)
        now += 10
        log.record("0105", now, null, "NO_DATA", now + 5)
        log.recordAdapterInfo(identity = "ELM327 v1.5", protocol = protocol)
        return log
    }

    @Test
    fun `the JSON export is a well-formed document with the session facts`() {
        val json = SessionExporter.export(
            logWithEntries().snapshot(),
            SessionExportFormat.JSON,
            SessionSanitizer(vin),
        )

        assertTrue(json.startsWith("{\n"))
        assertTrue(json.endsWith("}\n"))
        assertEquals("braces must balance", json.count { it == '{' }, json.count { it == '}' })
        assertTrue(json.contains("\"schema\": \"appili.session.v1\""))
        assertTrue(json.contains("\"readOnly\": true"))
        assertTrue(json.contains("\"sanitized\": true"))
        assertTrue(json.contains("\"entryCount\": 3"))
        assertTrue(json.contains("\"command\": \"ATI\""))
        assertTrue(json.contains("\"response\": \"ELM327 v1.5\""))
        assertTrue(json.contains("\"failure\": \"NO_DATA\""))
    }

    @Test
    fun `the JSON export never carries the VIN, as text or as hex`() {
        val json = SessionExporter.export(
            logWithEntries().snapshot(),
            SessionExportFormat.JSON,
            SessionSanitizer(vin),
        )

        assertFalse("raw VIN leaked: $json", json.contains(vin))
        assertFalse("hex-encoded VIN leaked: $json", json.contains(vinHex, ignoreCase = true))
        assertTrue(json.contains(SessionSanitizer.VIN_PLACEHOLDER))
    }

    @Test
    fun `the CSV export is rectangular with one header row`() {
        val csv = SessionExporter.export(
            logWithEntries().snapshot(),
            SessionExportFormat.CSV,
            SessionSanitizer(vin),
        )
        val lines = csv.trim().lines()

        assertEquals(4, lines.size)
        assertEquals(
            "sessionStartedAt,adapterIdentity,protocol,readOnly,sanitized,index,sentAt,receivedAt," +
                "command,response,failure",
            lines[0],
        )
        assertTrue("fields containing commas must be quoted", lines[1].contains("\"$protocol\""))
        assertTrue(lines[3].contains("NO_DATA"))
    }

    @Test
    fun `the CSV export never carries the VIN`() {
        val csv = SessionExporter.export(
            logWithEntries().snapshot(),
            SessionExportFormat.CSV,
            SessionSanitizer(vin),
        )

        assertFalse(csv.contains(vin))
        assertFalse(csv.contains(vinHex, ignoreCase = true))
        assertTrue(csv.contains(SessionSanitizer.VIN_PLACEHOLDER))
    }

    @Test
    fun `quotes, backslashes and newlines are escaped per format`() {
        val log = SessionLog(clock = { START_MILLIS })
        log.record("AT\"Q", START_MILLIS, "line1\nline2 \"q\", comma", null, START_MILLIS + 1)
        val snapshot = log.snapshot()

        val json = SessionExporter.export(snapshot, SessionExportFormat.JSON, SessionSanitizer(null))
        val csv = SessionExporter.export(snapshot, SessionExportFormat.CSV, SessionSanitizer(null))

        assertTrue("a quote must be escaped in JSON: $json", json.contains("AT\\\"Q"))
        assertTrue("a newline must be escaped in JSON: $json", json.contains("line1\\nline2"))
        assertTrue(
            "a quoted CSV field doubles its quotes: $csv",
            csv.contains("\"line1\nline2 \"\"q\"\", comma\""),
        )
    }

    @Test
    fun `file names are UTC-stamped and carry the right extension`() {
        val snapshot = logWithEntries().snapshot().copy(
            exportedAtMillis = Instant.parse("2026-11-01T10:15:00Z").toEpochMilli(),
        )

        assertEquals(
            "appili-session-20261101-101500.json",
            SessionExporter.fileName(snapshot, SessionExportFormat.JSON),
        )
        assertEquals(
            "appili-session-20261101-101500.csv",
            SessionExporter.fileName(snapshot, SessionExportFormat.CSV),
        )
    }

    @Test
    fun `an empty session still exports a valid document`() {
        val log = SessionLog(clock = { START_MILLIS })

        val json = SessionExporter.export(
            log.snapshot(),
            SessionExportFormat.JSON,
            SessionSanitizer(null),
        )

        assertTrue(json.contains("\"entryCount\": 0"))
        assertTrue(json.endsWith("}\n"))
    }

    private companion object {
        const val START_MILLIS = 1_700_000_000_000L
    }
}
