package io.github.ilyasyoy.voicedictation.data

import java.io.File

/** One orphan sweep per directory per process, before any owner can allocate its first file. */
internal class RecordingFiles private constructor(private val directory: File) {
    init { directory.mkdirs(); directory.listFiles()?.forEach { it.delete() } }
    fun create(): File = File.createTempFile("voice-", ".m4a", directory)
    companion object {
        private val stores = mutableMapOf<String, RecordingFiles>()
        @Synchronized fun shared(directory: File): RecordingFiles =
            stores.getOrPut(directory.canonicalPath) { RecordingFiles(directory) }
    }
}
