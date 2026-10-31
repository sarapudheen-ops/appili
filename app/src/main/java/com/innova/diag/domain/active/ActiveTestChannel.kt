package com.innova.diag.domain.active

import com.innova.diag.domain.obd.DiagnosticResult

/**
 * The transmission seam for active tests.
 *
 * Same shape as `domain.obd`'s `ObdCommandPort` — send one command, get text back or a typed failure —
 * but declared separately so an active test cannot be run through a dependency that was reviewed as a
 * read-only path. The binding lives in `adapter.elm` (`ElmActiveTestChannel`), which keeps the
 * dependency pointing adapter → domain (`docs/ASSUMPTIONS.md` A27).
 *
 * Failures are values, not exceptions: a refused or silent ECU must reach the controller as
 * [DiagnosticResult.Unsupported] / [DiagnosticResult.NoResponse] / [DiagnosticResult.Timeout] /
 * [DiagnosticResult.Malformed], so the controller can decide to stop *and* record why.
 */
fun interface ActiveTestChannel {

    /**
     * Sends [frame] (uppercase hex, no terminator) and returns the normalized response text.
     *
     * @throws kotlinx.coroutines.CancellationException when the caller is cancelled; the controller
     *   treats that as a dead-man release, not as an ECU condition.
     */
    suspend fun exchange(frame: String): DiagnosticResult<String>
}
