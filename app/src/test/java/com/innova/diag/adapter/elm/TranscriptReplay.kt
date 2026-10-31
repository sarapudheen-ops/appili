package com.innova.diag.adapter.elm

import com.innova.diag.core.transport.fake.FakeByteTransport
import kotlinx.coroutines.delay

/**
 * Replays a recorded adapter transcript one fragment at a time, with the original pauses between
 * fragments.
 *
 * A real RFCOMM link does not deliver `"41 00 BE 3F\r\r>"` in one piece: it hands over whatever the
 * adapter had written when the read returned, which is usually a mid-token fragment followed by a
 * pause. Replaying a transcript that way is the only way to prove the channel reassembles rather
 * than accidentally parses a whole response.
 *
 * Under `runTest` the delays are virtual, so a transcript with 50 ms and 20 ms pauses costs
 * microseconds of wall-clock time and stays deterministic.
 */
internal class TranscriptReplay(private val transport: FakeByteTransport) {

    private class Fragment(val bytes: ByteArray, val delayAfterMillis: Long)

    private val fragments = mutableListOf<Fragment>()

    /** Appends an ASCII fragment and the pause that follows it. */
    fun chunk(text: String, delayAfterMillis: Long = 0L): TranscriptReplay = apply {
        fragments += Fragment(text.encodeToByteArray(), delayAfterMillis)
    }

    /** Appends a raw byte fragment and the pause that follows it. */
    fun bytes(payload: ByteArray, delayAfterMillis: Long = 0L): TranscriptReplay = apply {
        fragments += Fragment(payload.copyOf(), delayAfterMillis)
    }

    /** Plays the whole transcript in order. Run this concurrently with the command under test. */
    suspend fun play() {
        fragments.forEachIndexed { index, fragment ->
            transport.emit(fragment.bytes)
            if (index < fragments.lastIndex && fragment.delayAfterMillis > 0L) {
                delay(fragment.delayAfterMillis)
            }
        }
    }
}
