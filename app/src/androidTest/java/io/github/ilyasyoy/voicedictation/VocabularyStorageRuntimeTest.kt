package io.github.ilyasyoy.voicedictation

import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.data.*
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Real Android private storage and atomic rename/directory sync. No UI, key, microphone or HTTP. */
class VocabularyStorageRuntimeTest {
    @Test fun wordsPersistAcrossReopenAndFailedWritePreservesLastCommit() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "words-test-${UUID.randomUUID()}")
        try {
            val store = VocabularyRepository(root)
            store.add("  Neovim  "); store.add("Ильяс"); store.add("Responses API")
            assertEquals(listOf("Neovim", "Ильяс", "Responses API"), VocabularyRepository(root).snapshot())
            store.edit("Neovim", "notice"); store.delete("Responses API")
            val failed = VocabularyRepository(root, RecordingCommit { _, _ -> throw IOException() })
            try { failed.edit("notice", "not-committed"); fail() } catch (e: VocabularyFailure) { assertEquals(VocabularyIssue.STORAGE, e.issue) }
            assertEquals(listOf("notice", "Ильяс"), VocabularyRepository(root).snapshot())
            try { store.add("ИЛЬЯС"); fail() } catch (e: VocabularyFailure) { assertEquals(VocabularyIssue.DUPLICATE, e.issue) }
            File(root, "words.properties.tmp").writeText("incomplete")
            assertEquals(listOf("notice", "Ильяс"), VocabularyRepository(root).snapshot())
        } finally { root.deleteRecursively() }
    }
    @Test fun applicationAndActivityContextsResolveOneProcessDictionary() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertSame(AndroidVocabularyStorage.repository(context), AndroidVocabularyStorage.repository(context.applicationContext))
    }
}
