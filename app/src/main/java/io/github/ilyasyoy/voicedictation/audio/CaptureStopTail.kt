package io.github.ilyasyoy.voicedictation.audio

import java.io.IOException

/** One nonblocking snapshot while MIC is still open, then stop MIC before encoding any tail.
 * A full bounded buffer is ambiguous: fail instead of claiming an intact tail. No read-after-stop.
 */
internal fun snapshotStopTail(
    buffer: ShortArray,
    read: (ShortArray) -> Int,
    stop: () -> Unit,
    consume: (ShortArray, Int) -> Unit,
) {
    require(buffer.isNotEmpty())
    try {
        val count = try {
            val available = read(buffer)
            if (available < 0 || available >= buffer.size) throw IOException("Stop tail unavailable or exceeded bounded buffer")
            available
        } finally { stop() }
        if (count > 0) consume(buffer, count)
    } finally { buffer.fill(0) }
}
