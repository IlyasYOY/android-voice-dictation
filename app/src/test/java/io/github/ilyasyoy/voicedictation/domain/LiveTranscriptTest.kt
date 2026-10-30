package io.github.ilyasyoy.voicedictation.domain

import org.junit.Assert.*
import org.junit.Test

class LiveTranscriptTest {
    @Test fun `deltas become one final item without duplication`() {
        val t = LiveTranscript()
        t.delta("a", "Hello ", "e1"); t.delta("a", "world", "e2"); t.delta("a", "world", "e2")
        assertEquals("Hello world", t.text); assertTrue(t.partial)
        t.complete("a", "Hello, world.")
        t.delta("a", "late duplicate", "e3"); t.complete("a", "Hello, world.")
        assertEquals("Hello, world.", t.text); assertFalse(t.partial); assertEquals(1, t.completedCount)
    }
    @Test fun `out of order completions use committed item chain`() {
        val t = LiveTranscript()
        t.complete("b", "Second."); t.complete("a", "First.")
        t.committed("b", "a"); t.committed("a", null)
        assertEquals("First.\nSecond.", t.text)
    }
    @Test fun `later item completion preserves earlier partial`() {
        val t = LiveTranscript(); t.committed("a", null); t.committed("b", "a")
        t.delta("a", "unfinished", "a1"); t.complete("b", "Done.")
        assertEquals("unfinished\nDone.", t.text); assertTrue(t.partial)
    }
    @Test fun `audio policy supports half an hour under upload boundary`() {
        assertEquals(1800, AudioPolicy.MAX_SECONDS)
        assertTrue(AudioPolicy.BIT_RATE.toLong() * AudioPolicy.MAX_SECONDS / 8 < AudioPolicy.FILE_BYTES)
        assertTrue(AudioPolicy.FILE_BYTES < AudioPolicy.UPLOAD_BYTES)
    }
    @Test fun `client endpoint detector commits after speech and silence or long turn`() {
        val vad = AudioTurnDetector()
        val speech = ByteArray(4800) { if (it % 2 == 0) 0 else 16 }
        val silence = ByteArray(4800)
        assertFalse(vad.append(speech))
        repeat(7) { assertFalse(vad.append(silence)) }
        assertTrue(vad.append(silence))
        repeat(149) { assertFalse(vad.append(silence)) }
        assertTrue(vad.append(silence))
    }
}
