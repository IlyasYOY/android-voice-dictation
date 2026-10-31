package io.github.ilyasyoy.voicedictation

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ilyasyoy.voicedictation.ui.*
import io.github.ilyasyoy.voicedictation.session.DictationSessions
import io.github.ilyasyoy.voicedictation.session.ModePreferences
import io.github.ilyasyoy.voicedictation.ui.theme.VoiceDictationTheme

class MainActivity : ComponentActivity() {
    private lateinit var model: DictationViewModel
    private var requestingPermission = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val requested = requestingPermission
        requestingPermission = false
        if (requested && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (granted) model.start(true) else model.permissionDenied()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                (testFactory?.invoke(this@MainActivity) ?: DictationViewModel { scope ->
                    DictationSessions.create(applicationContext, scope, initialMode = ModePreferences.load(applicationContext))
                }) as T
        })[DictationViewModel::class.java]
        setContent {
            VoiceDictationTheme {
                val state by model.state.collectAsStateWithLifecycle()
                BackHandler(state.audioBusy || state.canRetry) { model.cancel() }
                DictationScreen(state, model::setKey, ::record, model::stop, model::cancel,
                    model::retry, model::edit, ::copy, model::clear, model::forgetKey,
                    { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())) },
                    onMode = { mode -> model.setMode(mode); if (model.state.value.mode == mode) ModePreferences.save(applicationContext,mode) }, onSave = model::saveKey,
                    onFloatingSetup = { startActivity(Intent(this, io.github.ilyasyoy.voicedictation.floating.FloatingSetupActivity::class.java)) }, evidenceNotice = testNotice)
            }
        }
    }
    private fun record() {
        if (requestingPermission || model.state.value.busy) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            model.start(true)
        } else { requestingPermission = true; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    private fun copy() {
        val text = model.state.value.text
        if (text.isBlank()) return
        val clip = ClipData.newPlainText(getString(R.string.transcript), text)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }
    override fun onStop() {
        requestingPermission = false
        if (::model.isInitialized) model.cancel()
        super.onStop()
    }
    companion object {
        // In-process instrumentation seam; never set by exported intents or persisted configuration.
        internal var testFactory: ((Context) -> DictationViewModel)? = null
        internal var testNotice: String? = null
    }
}
