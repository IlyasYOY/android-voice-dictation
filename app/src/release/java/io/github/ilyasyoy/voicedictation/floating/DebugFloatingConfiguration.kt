package io.github.ilyasyoy.voicedictation.floating

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import io.github.ilyasyoy.voicedictation.session.DictationSession
import io.github.ilyasyoy.voicedictation.session.DictationSessions

internal object DebugFloatingConfiguration {
    suspend fun transcribeRetained(context: Context,audio: java.io.File): String? = null
    fun trace(context: Context, event: String) = Unit
    fun session(context: Context, scope: CoroutineScope): DictationSession? = null
    fun notice(context: Context): String? = null
}
