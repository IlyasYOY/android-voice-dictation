package io.github.ilyasyoy.voicedictation

import android.app.Application
import io.github.ilyasyoy.voicedictation.session.DictationSessions

/** Process-owned cleanup also runs for live-only, service and setup launches. No recorder is opened. */
class VoiceDictationApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DictationSessions.prepare(this)
    }
}
