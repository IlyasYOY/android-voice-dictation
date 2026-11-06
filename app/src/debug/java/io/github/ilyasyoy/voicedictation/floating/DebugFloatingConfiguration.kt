package io.github.ilyasyoy.voicedictation.floating

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.session.DictationSession
import io.github.ilyasyoy.voicedictation.session.DictationSessions
import android.content.Context
import kotlinx.coroutines.*
import androidx.core.content.edit
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Debug fixture opt-in only; arbitrary endpoints/real keys can never be redirected here. */
internal object DebugFloatingConfiguration {
    suspend fun transcribeRetained(context: Context,audio: java.io.File): String? {
        if(notice(context)==null)return null
        val port=context.getSharedPreferences("floating_fixture",Context.MODE_PRIVATE).getInt("port",0)
        require(port in 1024..65535)
        val key=AndroidApiKeyStore(context,".test.crossapp").load() ?: throw io.github.ilyasyoy.voicedictation.session.MissingSavedKey()
        return OpenAiTranscriber(endpoint="http://localhost:$port/v1/audio/transcriptions".toHttpUrl()).transcribe(audio,key)
    }
    fun notice(context: Context): String? = if (context.getSharedPreferences("floating_fixture", Context.MODE_PRIVATE)
        .getBoolean("enabled", false)) "TEST TRANSCRIPTION · localhost · synthetic key" else null
    fun trace(context: Context, event: String) { if (notice(context) != null) android.util.Log.d("FloatingFixture", event) }
    fun session(context: Context, scope: CoroutineScope): DictationSession? {
        if (notice(context) == null) return null
        val port = context.getSharedPreferences("floating_fixture", Context.MODE_PRIVATE).getInt("port", 0)
        if (port !in 1024..65535) return null
        val file = OpenAiTranscriber(endpoint = "http://localhost:$port/v1/audio/transcriptions".toHttpUrl())
        return DictationSessions.create(context, scope, AndroidApiKeyStore(context, ".test.crossapp"), fileTranscriber = file)
    }
}
class DebugFloatingSetupActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("screen_probe")?.let { command ->
            DebugScreenOnProbe.command(this,command);finish();return
        }
        val port = intent.getIntExtra("port", 0)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 40, 24, 24) }
        layout.addView(TextView(this).apply { setText(io.github.ilyasyoy.voicedictation.R.string.fixture_disclosure) })
        fun button(label: String, action: suspend () -> Unit) { layout.addView(Button(this).apply {
            text = label; setOnClickListener { isEnabled = false; scope.launch { action(); finish() } }
        }) }
        if (port in 1024..65535) button("Enable isolated fixture mode") {
            AndroidApiKeyStore(this, ".test.crossapp").save("synthetic-cross-app-fixture")
            getSharedPreferences("floating_fixture", MODE_PRIVATE).edit { putInt("port", port); putBoolean("enabled", true) }
        }
        button("Forget fixture key") { AndroidApiKeyStore(this, ".test.crossapp").forget() }
        button("Disable fixture mode") {
            AndroidApiKeyStore(this, ".test.crossapp").forget()
            getSharedPreferences("floating_fixture", MODE_PRIVATE).edit { clear() }
        }
        setContentView(layout)
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
