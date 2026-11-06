package io.github.ilyasyoy.voicedictation.data

import android.content.Context

/** One committed settings snapshot; opening a repeat form never writes here. */
internal class TextProcessingPreferences(context: Context) {
    private val store = TextProcessingSettingsStore(java.io.File(context.applicationContext.filesDir, "text-processing.properties"))
    fun read(): TextProcessingSettings = store.read()
    fun save(settings: TextProcessingSettings) = store.save(settings)
}
