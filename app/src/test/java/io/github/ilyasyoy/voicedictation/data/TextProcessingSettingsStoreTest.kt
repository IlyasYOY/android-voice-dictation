package io.github.ilyasyoy.voicedictation.data

import java.io.File
import java.io.IOException
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

class TextProcessingSettingsStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun absentSettingsAreDisabledAndExplicitSaveRestoresAsOneSnapshot() {
        val file = File(temporary.root, "processing.properties")
        assertFalse(TextProcessingSettingsStore(file).read().enabled)
        val desired = TextProcessingSettings(true, "gpt-4.1-nano", "Исправь пунктуацию")
        TextProcessingSettingsStore(file).save(desired)
        assertEquals(desired, TextProcessingSettingsStore(file).read())
    }
    @Test fun failedSaveDoesNotEnableAndUnreadableSettingsDoNotBecomeAnImplicitDefault() {
        val file = File(temporary.root, "processing.properties")
        val store = TextProcessingSettingsStore(file, RecordingCommit { _, _ -> throw IOException() })
        try { store.save(TextProcessingSettings(true)); fail() } catch (_: IOException) { }
        assertFalse(store.read().enabled)
        file.writeText("format=99\nenabled=true\n")
        try { store.read(); fail() } catch (_: IllegalStateException) { }
    }
}
