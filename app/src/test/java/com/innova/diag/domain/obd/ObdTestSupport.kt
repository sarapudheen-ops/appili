package com.innova.diag.domain.obd

/**
 * Small assertion helpers shared by the decoder tests.
 *
 * They exist so a failing test says *which* [DiagnosticResult] variant actually came back: "expected
 * 1672.0 but got NoResponse" is a diagnosis, whereas "expected true, got false" is a puzzle.
 */
internal fun <T> DiagnosticResult<T>.requireSuccess(what: String = "decode"): DiagnosticResult.Success<T> =
    when (this) {
        is DiagnosticResult.Success -> this
        else -> throw AssertionError("$what was expected to succeed but produced ${variantName()}")
    }

internal fun DiagnosticResult<*>.variantName(): String = when (this) {
    is DiagnosticResult.Success -> "Success(${data})"
    DiagnosticResult.Unsupported -> "Unsupported"
    DiagnosticResult.NoResponse -> "NoResponse"
    DiagnosticResult.Timeout -> "Timeout"
    is DiagnosticResult.Malformed -> "Malformed(${rawHex})"
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
