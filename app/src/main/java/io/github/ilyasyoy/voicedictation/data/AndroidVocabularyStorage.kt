package io.github.ilyasyoy.voicedictation.data

import android.content.Context
import java.io.File

/** Shared by main, keyboard overlay and retained-recording retry. Never stores an API key. */
internal object AndroidVocabularyStorage {
    private val repositories = mutableMapOf<String, VocabularyRepository>()
    @Synchronized fun repository(context: Context): VocabularyRepository {
        val directory = File(context.applicationContext.noBackupFilesDir, "vocabulary-v1")
        return repositories.getOrPut(directory.absolutePath) { VocabularyRepository(directory) }
    }
}
