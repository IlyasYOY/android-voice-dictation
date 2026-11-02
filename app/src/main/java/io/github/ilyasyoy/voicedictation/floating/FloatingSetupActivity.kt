package io.github.ilyasyoy.voicedictation.floating

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ilyasyoy.voicedictation.R
import io.github.ilyasyoy.voicedictation.onboarding.*
import io.github.ilyasyoy.voicedictation.session.*
import io.github.ilyasyoy.voicedictation.ui.FloatingSetupScreen
import io.github.ilyasyoy.voicedictation.ui.design.SoftCurrentPalette as P
import io.github.ilyasyoy.voicedictation.ui.theme.VoiceDictationTheme
import kotlinx.coroutines.*

class FloatingSetupActivity : ComponentActivity() {
    companion object {
        internal const val SHOW_RECORDINGS = "show_recordings"
        internal var testStore: SetupStore? = null
        internal var testGrants: (() -> SetupGrants)? = null
        internal var testAction: ((SetupAction) -> Unit)? = null
        internal var testDisable: (() -> Unit)? = null
        internal var testEnable: (() -> Unit)? = null
    }
    private class Model(store: SetupStore) : ViewModel() { val setup = FloatingSetupController(store) }
    private lateinit var model: Model
    private val setup get() = model.setup
    private var closing = false
    private var revision by mutableIntStateOf(0)
    private val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { permissionReturned() }
    private fun grants() = testGrants?.invoke() ?: SetupPlatform.grants(this)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = (Model(testStore ?: SetupPreferences(applicationContext)) as T)
        })[Model::class.java]
        setup.refresh(grants())
        retryScope.launch { RecordingRecovery.processOwned.state.collect { if (!closing) render() } }
        setContent { VoiceDictationTheme {
            val ui by setup.state.collectAsStateWithLifecycle()
            val version = revision
            val recoveryAnchor = remember { BringIntoViewRequester() }
            FloatingSetupScreen(ui.grants, ui.progress, ui.blocked, microphoneSettings(), notificationSettings(), ui.problem,
                ::finish, ::enable, ::primary, ::disable, { navigate(SetupAction.APP_SETTINGS) }, setup::acknowledgeBackground) {
                if (RecordingRecovery.processOwned.count > 0 || FloatingFeedback.processOwned.text.isNotBlank()) {
                    key(version) { AndroidView(factory = { recoveryView() }, modifier = Modifier.bringIntoViewRequester(recoveryAnchor)) }
                    LaunchedEffect(Unit) {
                        if (intent.getBooleanExtra(SHOW_RECORDINGS, false)) { withFrameNanos { }; recoveryAnchor.bringIntoView() }
                    }
                }
            }
        } }
    }
    override fun onResume() { super.onResume(); if (::model.isInitialized) { setup.resumed(grants()); revision++ } }
    private fun render() { if (::model.isInitialized) { setup.refresh(grants()); revision++ } }
    internal fun permissionReturned() { if (::model.isInitialized) setup.permissionReturned(grants()) }
    private fun microphoneSettings() = setup.requested(SetupPermission.MICROPHONE) && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
    private fun notificationSettings() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
        (setup.requested(SetupPermission.NOTIFICATIONS) && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
    private fun primary() {
        when (setup.state.value.step) {
            FloatingSetupStep.MICROPHONE -> if (microphoneSettings()) navigate(SetupAction.MICROPHONE)
                else request(SetupPermission.MICROPHONE, Manifest.permission.RECORD_AUDIO, SetupAction.MICROPHONE)
            FloatingSetupStep.ACCESSIBILITY -> navigate(SetupAction.ACCESSIBILITY)
            FloatingSetupStep.NOTIFICATIONS -> if (notificationSettings()) navigate(SetupAction.NOTIFICATIONS)
                else request(SetupPermission.NOTIFICATIONS, Manifest.permission.POST_NOTIFICATIONS, SetupAction.NOTIFICATIONS)
            FloatingSetupStep.BACKGROUND -> setup.acknowledgeBackground()
            else -> Unit
        }
    }
    private fun request(kind: SetupPermission, name: String, action: SetupAction) {
        if (!setup.beginPermission(kind)) return
        try { testAction?.invoke(action) ?: permission.launch(name) } catch (_: Exception) { setup.failedAction() }
    }
    private fun navigate(action: SetupAction) {
        if (!setup.beginNavigation()) return
        try { testAction?.invoke(action) ?: startActivity(SetupPlatform.intent(this, action)) } catch (_: Exception) { setup.failedAction() }
    }
    private fun enable() {
        if (setup.state.value.blocked) return
        setup.chooseFloating()
        if (testEnable != null) testEnable?.invoke() else FloatingPreferences.setEnabled(this, true)
        render()
    }
    private fun disable() {
        if (setup.state.value.blocked) return
        val bound = FloatingDictationService.instance != null
        if (testDisable != null) testDisable?.invoke() else FloatingPreferences.setEnabled(this, false)
        render()
        // A connected service can revoke its own grant. If unavailable, expose Android's explicit revoke control.
        if (setup.state.value.grants.accessibility && !bound && testDisable == null) navigate(SetupAction.ACCESSIBILITY)
        retryScope.launch { delay(300); if (!closing) render() }
    }
    private fun recoveryView(): LinearLayout {
        val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val ink = if (dark) P.NightText else P.Plum
        fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun button(label: Int, id: Int = 0, enabled: Boolean = true, onClick: () -> Unit) { layout.addView(Button(this).apply {
            isEnabled = enabled; text = getString(label); if (id != 0) this.id = id; isAllCaps = false; setTextColor(ink); minHeight = dp(52)
            background = GradientDrawable().apply { setColor(if (dark) P.NightSurface else 0xFFE7DDF2.toInt()); cornerRadius = dp(24).toFloat() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
            setOnClickListener { onClick() }
        }) }
        val recovery = FloatingFeedback.processOwned
        val recordings=RecordingRecovery.processOwned
        if(recordings.count>0) {
            layout.addView(TextView(this).apply { text=getString(R.string.current_saved_recordings,recordings.count); textSize=17f; setTextColor(ink) })
            recordings.state.value.failure?.let { failure ->
                layout.addView(TextView(this).apply {
                    id=R.id.floating_audio_problem
                    text=getString(when(failure) {
                        RecordingRetryFailure.KEY,RecordingRetryFailure.AUTH -> R.string.current_retry_key_error
                        RecordingRetryFailure.NETWORK -> R.string.current_retry_network_error
                        RecordingRetryFailure.SERVICE -> R.string.current_retry_service_error
                        RecordingRetryFailure.STORAGE -> R.string.recording_storage_error
                    })
                    textSize=16f;setTextColor(ink);accessibilityLiveRegion=android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
                })
            }
            button(R.string.current_retry_recording,R.id.floating_retry_audio,!recordings.retrying) {
                retryScope.launch {
                    try {
                        val text=recordings.retry { file -> DebugFloatingConfiguration.transcribeRetained(this@FloatingSetupActivity,file)
                            ?: DictationSessions.transcribeRetained(this@FloatingSetupActivity,file) }
                        if(text!=null) { recovery.retain(text); recovery.resetSignal() }
                    } catch(e: CancellationException) { throw e }
                    catch(_: Exception) { recovery.offer("",getString(R.string.floating_stream_error),error=true) }
                    finally { if(!closing && !isDestroyed)render() }
                }
                render()
            }
            button(R.string.current_discard_recordings,R.id.floating_discard_audio,!recordings.retrying) { recordings.discard(); render() }
        }
        if (recovery.text.isNotBlank()) {
            layout.addView(TextView(this).apply { text=getString(R.string.current_recover); textSize=18f; setTextColor(ink); setPadding(0,dp(16),0,dp(8)) })
            layout.addView(TextView(this).apply { id=R.id.floating_preview; text=recovery.text; textSize=17f; setTextColor(ink); setTextIsSelectable(true) })
            button(R.string.copy, R.id.floating_copy) {
                val clip=android.content.ClipData.newPlainText(getString(R.string.transcript),recovery.text)
                if (Build.VERSION.SDK_INT>=33) clip.description.extras=android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE",true) }
                getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(clip)
            }
            button(R.string.current_discard, R.id.floating_discard) { recovery.discard(); render() }
        }
        for (i in 0 until layout.childCount) (layout.getChildAt(i) as? TextView)?.setTextColor(ink)
        return layout
    }
    override fun onDestroy() { closing = true; retryScope.cancel(); super.onDestroy() }
}
