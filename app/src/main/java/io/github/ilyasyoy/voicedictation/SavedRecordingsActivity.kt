package io.github.ilyasyoy.voicedictation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import io.github.ilyasyoy.voicedictation.session.DictationSessions
import io.github.ilyasyoy.voicedictation.ui.RecordingHistorySheet
import io.github.ilyasyoy.voicedictation.ui.theme.VoiceDictationTheme

/** Bubble recovery uses the same sheet and actions as the main screen; no editor is retained. */
class SavedRecordingsActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(); DictationSessions.prepare(this)
        setContent { VoiceDictationTheme { Surface(Modifier.fillMaxSize()) {
            RecordingHistorySheet(onDismiss=::finish)
        } } }
    }
}
