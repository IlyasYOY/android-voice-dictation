package io.github.ilyasyoy.voicedictation.onboarding

import org.junit.Assert.*
import org.junit.Test

class KeyPasteTest {
    private val synthetic = "sk-test-fixture-" + "a".repeat(32)
    @Test fun foregroundExplicitPasteAcceptsOnlyCandidateAndDoesNotSaveOrTransmit() {
        var reads = 0
        assertEquals(synthetic, KeyPaste.candidate("", false, false, true) { reads++; " $synthetic\n" })
        assertEquals(1, reads) // This port has no persistence or network dependency.
    }
    @Test fun backgroundAndUnfocusedAccessNeverReadsClipboard() {
        var reads = 0
        assertNull(KeyPaste.candidate("", false, false, false) { reads++; synthetic })
        assertEquals(0, reads)
    }
    @Test fun existingSavedOrDraftKeyAndLoadingNeverReadOrOverwrite() {
        var reads = 0
        assertNull(KeyPaste.candidate("typed", false, false, true) { reads++; synthetic })
        assertNull(KeyPaste.candidate("", true, false, true) { reads++; synthetic })
        assertNull(KeyPaste.candidate("", false, true, true) { reads++; synthetic })
        assertEquals(0, reads)
    }
    @Test fun ordinaryClipboardDataHeadersMalformedAndOversizedPayloadsAreNotCandidates() {
        for (text in listOf(null, "", "ordinary copied text", "https://example.com", "Bearer $synthetic", "sk-short", "$synthetic secret", "$synthetic\n$synthetic", "sk-"+"a".repeat(5000)))
            assertNull(KeyPaste.candidate("", false, false, true) { text })
    }
}
