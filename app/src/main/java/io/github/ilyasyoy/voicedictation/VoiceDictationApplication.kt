package io.github.ilyasyoy.voicedictation

import android.app.Application
import io.github.ilyasyoy.voicedictation.diagnostics.*
import kotlinx.coroutines.*
import java.io.File
import io.github.ilyasyoy.voicedictation.session.DictationSessions

/** Restore/import durable data for every entry point. No recorder or network request is opened. */
class VoiceDictationApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Diagnostics.initialize(DiagnosticStore(File(noBackupFilesDir, "diagnostics")))
        Diagnostics.record(DiagnosticArea.APP, DiagnosticAction.START)
        Thread.getDefaultUncaughtExceptionHandler()?.let { platformHandler ->
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                try { Diagnostics.crash(error) } finally { platformHandler.uncaughtException(thread, error) }
            }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            while (isActive) { delay(60 * 60 * 1000L); Diagnostics.maintain() }
        }
        io.github.ilyasyoy.voicedictation.session.LegacyModeMigration.migrate(this)
        DictationSessions.prepare(this)
    }
}
