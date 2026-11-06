package io.github.ilyasyoy.voicedictation.data

import java.io.File
import java.util.Properties

/** A failed save must not enable paid work through an in-memory preferences cache. */
internal class TextProcessingSettingsStore(private val file: File, private val commit: RecordingCommit = AtomicRecordingCommit) {
    fun read(): TextProcessingSettings {
        if (!file.exists()) return TextProcessingSettings()
        val values = Properties().apply { file.inputStream().use { load(it) } }
        check(values.getProperty("format") == "1")
        return TextProcessingSettings(values.getProperty("enabled").toBooleanStrict(),
            checkNotNull(values.getProperty("model")), checkNotNull(values.getProperty("prompt"))).also { check(it.valid) }
    }
    fun save(settings: TextProcessingSettings) {
        require(settings.valid)
        val values = Properties().apply {
            setProperty("format", "1"); setProperty("enabled", settings.enabled.toString())
            setProperty("model", settings.model); setProperty("prompt", settings.prompt)
        }
        commit.write(file, values)
    }
}
