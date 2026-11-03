package io.github.ilyasyoy.voicedictation.data

import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.Locale
import java.util.Properties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class VocabularyIssue { EMPTY, INVALID, DUPLICATE, CAPACITY, STORAGE, MISSING }
internal class VocabularyFailure(val issue: VocabularyIssue) : IOException()
internal data class VocabularyState(val words: List<String> = emptyList(), val readable: Boolean = false) {
    override fun toString() = "VocabularyState(count=${words.size}, readable=$readable)"
}

/** A separate private ledger. A failed or unknown schema is never replaced with an empty dictionary. */
internal class VocabularyRepository(private val directory: File, private val commit: RecordingCommit = AtomicRecordingCommit) {
    private val file = File(directory, "words.properties")
    private val mutable = MutableStateFlow(VocabularyState())
    val state = mutable.asStateFlow()
    init { refresh() }

    @Synchronized fun refresh() {
        try {
            if (directory.mkdirs()) directory.parentFile?.let(AtomicRecordingCommit::syncDirectory)
            check(directory.isDirectory && directory.canRead() && directory.canExecute())
            mutable.value = VocabularyState(read(), true)
        } catch (_: Exception) { mutable.value = mutable.value.copy(readable = false) }
    }

    /** Each explicit upload calls this anew, including Retry; no network is owned by this repository. */
    @Synchronized fun snapshot(): List<String> {
        if (!mutable.value.readable) throw VocabularyFailure(VocabularyIssue.STORAGE)
        return mutable.value.words.toList()
    }
    @Synchronized fun add(input: String) = change { words -> words + validated(input, words) }
    @Synchronized fun edit(original: String, input: String) = change { words ->
        val index = words.indexOf(original)
        if (index < 0) throw VocabularyFailure(VocabularyIssue.MISSING)
        words.toMutableList().apply { set(index, validated(input, words.filterIndexed { i, _ -> i != index })) }
    }
    @Synchronized fun delete(original: String) = change { words ->
        if (original !in words) throw VocabularyFailure(VocabularyIssue.MISSING)
        words - original
    }
    private fun change(transform: (List<String>) -> List<String>) {
        val next = transform(snapshot())
        if (next.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } > MAX_WORD_BYTES) throw VocabularyFailure(VocabularyIssue.CAPACITY)
        val properties = Properties().apply {
            setProperty("version", "1"); setProperty("count", next.size.toString())
            next.forEachIndexed { i, word -> setProperty("word.$i", word) }
        }
        try { commit.write(file, properties) }
        catch (_: Exception) {
            // Directory sync can fail after rename. Reconcile what actually reached disk before another edit/upload.
            refresh()
            throw VocabularyFailure(VocabularyIssue.STORAGE)
        }
        mutable.value = VocabularyState(next, true)
    }
    private fun read(): List<String> {
        if (!file.exists()) return emptyList() // Upgrade from versions without Words requires no other-file migration.
        check(file.isFile && file.length() <= MAX_FILE_BYTES)
        val values = Properties().apply { file.inputStream().use(::load) }
        check(values.getProperty("version") == "1")
        val count = checkNotNull(values.getProperty("count").toIntOrNull())
        check(count in 0..MAX_WORD_BYTES && values.size == count + 2)
        val result = mutableListOf<String>()
        repeat(count) { i ->
            val word = checkNotNull(values.getProperty("word.$i"))
            check(validated(word, result) == word)
            result.add(word)
        }
        check(result.sumOf { it.toByteArray(Charsets.UTF_8).size.toLong() } <= MAX_WORD_BYTES)
        return result
    }
    companion object {
        // Local storage/request-size guard, not an undocumented OpenAI keyword count/token limit. Never truncates.
        const val MAX_WORD_BYTES = 65_536
        private const val MAX_FILE_BYTES = 1_048_576
        fun validated(input: String, existing: List<String>): String {
            if (input.any { it == '<' || it == '>' || it.isISOControl() || it == '\u2028' || it == '\u2029' })
                throw VocabularyFailure(VocabularyIssue.INVALID)
            val word = input.trim()
            if (word.isEmpty()) throw VocabularyFailure(VocabularyIssue.EMPTY)
            val normalized = identity(word)
            if (existing.any { identity(it) == normalized }) throw VocabularyFailure(VocabularyIssue.DUPLICATE)
            return word
        }
        private fun identity(word: String) = Normalizer.normalize(word, Normalizer.Form.NFC).lowercase(Locale.ROOT)
    }
}
