package io.github.ilyasyoy.voicedictation.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Locale
import java.util.Properties
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class VocabularyRepositoryTest {
    private val root = Files.createTempDirectory("vocabulary").toFile()
    @After fun cleanup() { root.deleteRecursively() }
    private fun store(commit: RecordingCommit = AtomicRecordingCommit) = VocabularyRepository(File(root, "words"), commit)
    private fun failure(issue: VocabularyIssue, action: () -> Unit) {
        try { action(); fail("Expected vocabulary failure") } catch (e: VocabularyFailure) { assertEquals(issue, e.issue); assertNull(e.message) }
    }
    @Test fun `upgrade starts empty and CRUD survives restart without touching existing data`() {
        val history = File(root, "recordings-v1").apply { mkdirs() }
        val key = File(root, "encrypted-key").apply { writeText("synthetic-existing-state") }
        File(history, "existing").writeText("history-state")
        val dictionary = store()
        assertTrue(dictionary.state.value.readable); assertEquals(emptyList<String>(), dictionary.snapshot())
        dictionary.add("  Neovim  "); dictionary.add("Ильяс"); dictionary.add("Responses API"); dictionary.add("😀 термин")
        assertEquals(listOf("Neovim", "Ильяс", "Responses API", "😀 термин"), store().snapshot())
        dictionary.edit("Neovim", "NEOVIM"); dictionary.delete("Ильяс")
        assertEquals(listOf("NEOVIM", "Responses API", "😀 термин"), store().snapshot())
        assertEquals("synthetic-existing-state", key.readText()); assertEquals("history-state", File(history, "existing").readText())
        assertFalse(dictionary.state.value.toString().contains("NEOVIM"))
    }
    @Test fun `unicode duplicates are locale independent preserve spelling and never overwrite`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            val dictionary = store(); dictionary.add("Ильяс"); dictionary.add("Café"); dictionary.add("INPUT")
            failure(VocabularyIssue.DUPLICATE) { dictionary.add("  ИЛЬЯС ") }
            failure(VocabularyIssue.DUPLICATE) { dictionary.add("Cafe\u0301") }
            failure(VocabularyIssue.DUPLICATE) { dictionary.add("input") }
            failure(VocabularyIssue.DUPLICATE) { dictionary.edit("INPUT", "Café") }
            dictionary.edit("INPUT", "input")
            assertEquals(listOf("Ильяс", "Café", "input"), store().snapshot())
        } finally { Locale.setDefault(previous) }
    }
    @Test fun `invalid empty and over budget terms fail honestly with unchanged commit`() {
        val dictionary = store(); dictionary.add("kept")
        failure(VocabularyIssue.EMPTY) { dictionary.add(" \u00a0 ") }
        for (text in listOf("a\nb", "a\rb", "<term>", "a\u0000b", "a\u2028b"))
            failure(VocabularyIssue.INVALID) { dictionary.add(text) }
        failure(VocabularyIssue.CAPACITY) { dictionary.add("a".repeat(VocabularyRepository.MAX_WORD_BYTES)) }
        failure(VocabularyIssue.MISSING) { dictionary.edit("absent", "new") }
        failure(VocabularyIssue.MISSING) { dictionary.delete("absent") }
        assertEquals(listOf("kept"), store().snapshot())
    }
    @Test fun `failed staging leaves previous words and failure after rename reconciles disk`() {
        store().add("old")
        val failing = store(RecordingCommit { _, _ -> throw IOException() })
        failure(VocabularyIssue.STORAGE) { failing.add("new") }
        assertEquals(listOf("old"), failing.snapshot()); assertEquals(listOf("old"), store().snapshot())
        val afterRename = store(RecordingCommit { file, values -> AtomicRecordingCommit.write(file, values); throw IOException() })
        failure(VocabularyIssue.STORAGE) { afterRename.add("new") }
        assertEquals(listOf("old", "new"), afterRename.snapshot()); assertEquals(afterRename.snapshot(), store().snapshot())
    }
    @Test fun `corrupt unknown future and unreadable storage never becomes writable empty state`() {
        for (contents in listOf("version=2\ncount=0\n", "version=1\ncount=1\n", "version=1\ncount=2\nword.0=x\nword.1=X\n")) {
            val dir = File(root, "words").apply { mkdirs() }
            val file = File(dir, "words.properties").apply { writeText(contents) }
            val dictionary = store(); assertFalse(dictionary.state.value.readable)
            failure(VocabularyIssue.STORAGE) { dictionary.snapshot() }
            failure(VocabularyIssue.STORAGE) { dictionary.add("replacement") }
            assertEquals(contents, file.readText())
            file.delete()
        }
        File(root, "words/words.properties").mkdir()
        assertFalse(store().state.value.readable)
    }
    @Test fun `incomplete staging is ignored and explicit reload recovers read failure`() {
        val dictionary = store(); dictionary.add("committed")
        val file = File(root, "words/words.properties")
        val committed = file.readBytes()
        File(root, "words/words.properties.tmp").writeText("incomplete")
        assertEquals(listOf("committed"), store().snapshot())
        file.writeText("version=unknown"); dictionary.refresh()
        failure(VocabularyIssue.STORAGE) { dictionary.snapshot() }
        file.writeBytes(committed); dictionary.refresh()
        assertEquals(listOf("committed"), dictionary.snapshot())
    }
    @Test fun `snapshot is independent of later CRUD and includes all words without truncation`() {
        val dictionary = store(); dictionary.add("first")
        val first = dictionary.snapshot(); dictionary.add("second")
        assertEquals(listOf("first"), first)
        dictionary.delete("first"); assertEquals(listOf("second"), dictionary.snapshot())
    }
}
