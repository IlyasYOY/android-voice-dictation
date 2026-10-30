package io.github.ilyasyoy.voicedictation.floating

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.ui.DictationViewModel
import android.content.Context
import kotlinx.coroutines.*
import androidx.core.content.edit

/** Debug fixture opt-in only; arbitrary endpoints/real keys can never be redirected here. */
internal object DebugFloatingConfiguration {
    fun notice(context: Context): String? = if (context.getSharedPreferences("floating_fixture", Context.MODE_PRIVATE)
        .getBoolean("enabled", false)) "TEST TRANSCRIPTION · localhost · synthetic key" else null
    fun trace(context: Context, event: String) { if (notice(context) != null) android.util.Log.d("FloatingFixture", event) }
    fun model(context: Context): DictationViewModel? {
        if (notice(context) == null) return null
        val port = context.getSharedPreferences("floating_fixture", Context.MODE_PRIVATE).getInt("port", 0)
        if (port !in 1024..65535) return null
        val network = OpenAiRealtimeTranscriber(endpoint = "ws://localhost:$port/v1/realtime?intent=transcription")
        val observed = RealtimeTranscriber { key, emit -> network.connect(key) { event ->
            if (event is LiveEvent.Failure) trace(context, "transport=${event.error.javaClass.simpleName}, cause=${event.error.cause?.javaClass?.simpleName}, pingTimeout=${event.error.cause?.message?.contains("ping") == true}")
            emit(event)
        } }
        return DictationViewModel(AndroidAudioRecorder(context), OpenAiTranscriber(), AndroidPcmSource(), observed,
            AndroidApiKeyStore(context, ".test.crossapp"))
    }
}
class DebugFloatingSetupActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
