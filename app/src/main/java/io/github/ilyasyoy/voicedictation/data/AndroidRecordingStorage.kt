package io.github.ilyasyoy.voicedictation.data

import android.content.Context
import java.io.File

/** One ledger per private directory, shared by main, floating, setup and manual retry. */
internal object AndroidRecordingStorage {
    private val repositories=mutableMapOf<String,RecordingRepository>()
    @Synchronized fun repository(context: Context): RecordingRepository {
        val app=context.applicationContext
        val directory=File(app.noBackupFilesDir,"recordings-v1")
        return repositories.getOrPut(directory.canonicalPath) {
            RecordingRepository(directory).also { store ->
                runCatching {store.migrateLegacy(File(app.cacheDir,"dictation"))}.onFailure {store.migrationFailed()}
            }
        }
    }
}
