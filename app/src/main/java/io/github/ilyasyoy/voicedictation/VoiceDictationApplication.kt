package io.github.ilyasyoy.voicedictation

import android.app.Application
import io.github.ilyasyoy.voicedictation.session.DictationSessions

/** Restore/import durable data for every entry point. No recorder or network request is opened. */
class VoiceDictationApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DictationSessions.prepare(this)
    }
}
