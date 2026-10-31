package com.innova.diag.core.transport

/**
 * Raw byte fixtures shared by the transport tests.
 *
 * These are **opaque bytes**, never parsed. No ELM327/OBD protocol decoding happens in this phase,
 * so the fixtures deliberately avoid asserting any meaning about the content: they exist to pin
 * down *fragmentation* behaviour, and a future parser will get its own HEX transcript fixtures.
 */
internal object FakeTransportFixtures {

    /**
     * A generic request line as an adapter would emit it, terminated with carriage return.
     * A getter, not a shared instance: [ByteArray] is mutable, and a fixture that one test can
     * corrupt for another is worse than no fixture.
     */
    val GENERIC_REQUEST: ByteArray
        get() = "0100\r".encodeToByteArray()

    /**
     * A response line whose natural boundaries do *not* align with protocol tokens: with
     * [SPLIT_CHUNK_SIZES] the third fragment ends in the middle of the `" 1A "` token, so any
     * consumer that assumes one fragment equals one line or one data item fails loudly.
     */
    val SPLIT_PAYLOAD: ByteArray
        get() = "41 0C 1A F8\r".encodeToByteArray()

    /** Fragment widths for [SPLIT_PAYLOAD]: 2 + 3 + 4 + 3 = 12 bytes. */
    val SPLIT_CHUNK_SIZES: List<Int> = listOf(2, 3, 4, 3)

    /** The exact fragments [FakeByteTransport] must deliver for [SPLIT_PAYLOAD]. */
    val SPLIT_FRAGMENTS: List<String> = listOf("41", " 0C", " 1A ", "F8\r")

    /** Reassembles fragments in order. */
    fun concat(fragments: List<ByteArray>): ByteArray =
        fragments.fold(ByteArray(0)) { accumulated, fragment -> accumulated + fragment }
}

/**
 * JUnit 4 has no suspending `assertThrows`, and wrapping in `runBlocking` inside `runTest` would
 * stall the virtual clock. This helper keeps the suspension point intact.
 */
internal suspend inline fun <reified T : Throwable> assertThrowsSuspend(
    noinline block: suspend () -> Unit,
): T {
    try {
        block()
    } catch (thrown: Throwable) {
        if (thrown is T) return thrown
        throw AssertionError(
            "expected ${T::class.java.name} but caught ${thrown::class.java.name}",
            thrown,
        )
    }
    throw AssertionError("expected ${T::class.java.name} but nothing was thrown")
}
