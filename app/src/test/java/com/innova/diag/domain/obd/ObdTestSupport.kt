package com.innova.diag.domain.obd

/**
 * Small assertion helpers shared by the decoder tests.
 *
 * They exist so a failing test says *which* [DiagnosticResult] variant actually came back: "expected
 * 1672.0 but got NoResponse" is a diagnosis, whereas "expected true, got false" is a puzzle.
 */

/**
 * Returns the [DiagnosticResult.Success] box so tests can read both `data` and `rawHex`.
 *
 * The cast is checked by hand (`dataOrNull == null` is true for every failure variant, since no
 * payload type in this codebase is nullable), which is why it is suppressed rather than left as an
 * unguarded `as`.
 */
@Suppress("UNCHECKED_CAST")
internal fun <T> DiagnosticResult<T>.requireSuccess(what: String = "decode"): DiagnosticResult.Success<T> {
    if (dataOrNull == null) {
        throw AssertionError("$what was expected to succeed but produced ${variantName()}")
    }
    return this as DiagnosticResult.Success<T>
}

internal fun DiagnosticResult<*>.variantName(): String = when {
    dataOrNull != null -> "Success(${dataOrNull})"
    this is DiagnosticResult.Malformed -> "Malformed(${rawText})"
    this is DiagnosticResult.Unsupported -> "Unsupported"
    this is DiagnosticResult.Timeout -> "Timeout"
    else -> "NoResponse"
}

internal fun DiagnosticResult<*>.assertNoResponse(what: String) {
    if (this !is DiagnosticResult.NoResponse) {
        throw AssertionError("$what should be NoResponse but produced ${variantName()}")
    }
}

internal fun DiagnosticResult<*>.assertMalformed(what: String) {
    if (this !is DiagnosticResult.Malformed) {
        throw AssertionError("$what should be Malformed but produced ${variantName()}")
    }
}

/** ASCII → uppercase hex, for building mode 09 / VIN fixtures the way an adapter would print them. */
internal fun String.asciiHex(): String =
    toByteArray(Charsets.US_ASCII).joinToString(separator = "") { "%02X".format(it.toInt() and 0xFF) }
