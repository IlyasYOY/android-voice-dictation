package io.github.ilyasyoy.voicedictation

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ilyasyoy.voicedictation.ui.*
import io.github.ilyasyoy.voicedictation.session.DictationSessions
import io.github.ilyasyoy.voicedictation.session.RecordingRecovery
import io.github.ilyasyoy.voicedictation.session.RecordingRetryFailure
import io.github.ilyasyoy.voicedictation.floating.FloatingSetupActivity
import io.github.ilyasyoy.voicedictation.ui.theme.VoiceDictationTheme
import io.github.ilyasyoy.voicedictation.onboarding.*
import io.github.ilyasyoy.voicedictation.floating.FloatingPreferences

class MainActivity : ComponentActivity() {
    private lateinit var model: DictationViewModel
    private var setupFlow: SetupFlow? = null
    private var setupGrants by mutableStateOf(SetupGrants(false, false, false, false))
    private var pasteProblem by mutableStateOf(false)
    private var navigationProblem by mutableStateOf(false)
    private val setupPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { setupPermissionReturned() }
    private var requestingPermission = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val requested = requestingPermission
        requestingPermission = false
        if (requested && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (granted) startWithPermission() else model.permissionDenied()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                (testFactory?.invoke(this@MainActivity) ?: DictationViewModel { scope ->
                    DictationSessions.create(applicationContext, scope,mainDraft=true)
                }) as T
        })[DictationViewModel::class.java]
        if (!testSkipOnboarding) {
            setupFlow = model.setup(testSetupStore ?: SetupPreferences(applicationContext))
            refreshSetup(checkGrants = true, foreground = true)
        }
        setContent {
            VoiceDictationTheme {
                var historyOpen by rememberSaveable { mutableStateOf(false) }
                val state by model.state.collectAsStateWithLifecycle()
                val draft by model.setupKey.collectAsStateWithLifecycle()
                val setupUi = setupFlow?.state?.collectAsStateWithLifecycle()?.value ?: SetupUi()
                LaunchedEffect(state.keyLoaded, state.keyBusy, state.keySaved, state.hasSavedKey, state.validKey, draft.active, draft.confirmed) {
                    refreshSetup()
                }
                LaunchedEffect(state.audioBusy) { if (state.audioBusy) historyOpen = false }
                val recovery by RecordingRecovery.processOwned.state.collectAsStateWithLifecycle()
                BackHandler(state.audioBusy || state.canRetry) { model.cancel() }
                val showSetup = setupUi.step != null && !state.audioBusy && !historyOpen
                BackHandler(showSetup) { deferSetup() }
                if (showSetup) SetupScreen(setupUi, state, draft, setupGrants, pasteProblem, navigationProblem,
                    microphoneSettings(), notificationSettings(), recovery.savedCount,
                    onKey = { pasteProblem = false; model.editSetupKey(it) }, onPaste = { pasteSetupKey(false) },
                    onBrowser = { navigateSetup(SetupAction.KEY_PAGE) }, onPrimary = ::setupPrimary,
                    onEnableFloating = {
                        setupFlow?.chooseFloating(); FloatingPreferences.setEnabled(applicationContext, true)
                        refreshSetup(checkGrants = true); navigateSetup(SetupAction.ACCESSIBILITY)
                    }, onAppSettings = { navigateSetup(SetupAction.APP_SETTINGS) }, onDefer = ::deferSetup,
                    onHistory = { deferSetup(); retainRetryRecording(); historyOpen = true })
                else DictationScreen(state, { pasteProblem = false; model.setKey(it) }, ::record, model::stop, model::cancel,
                    model::retry, model::edit, ::copy, model::clear, { model.forgetKey(); model.clearSetupKey(); setupFlow?.deferKey() },
                    onSave = model::saveKey,
                    onFloatingSetup = {
                        if (setupFlow == null || setupFlow?.beginNavigation() == true) startActivity(Intent(this, FloatingSetupActivity::class.java))
                    }, evidenceNotice = testNotice,
                    savedRecordings = recovery.savedCount, historyCapacity = recovery.capacity,
                    onHistoryLimit = { limit -> runCatching {
                        io.github.ilyasyoy.voicedictation.data.AndroidRecordingStorage.repository(applicationContext).setHistoryLimit(limit)
                    }.isSuccess },
                    onSavedRecordings = { retainRetryRecording(); historyOpen = true },
                    onSetup = if (setupUi.missing != 0) ({ model.clearSetupKey(); setupFlow?.manual(); Unit }) else null,
                    onPasteKey = if (setupFlow != null) ({ pasteSetupKey(true) }) else null, pasteProblem = pasteProblem)
                if (historyOpen) RecordingHistorySheet(
                    onDismiss = { historyOpen = false; model.refreshDraft() },
                    onRecordsChanged = model::refreshDraft,
                    draft = HistoryDraft(state.text, model.draftSourceId(), state.text.isNotBlank() || model.draftSourceId() != null || state.phase == io.github.ilyasyoy.voicedictation.session.Phase.RESULT),
                    draftProblem = state.problem == io.github.ilyasyoy.voicedictation.session.Problem.LOCAL_STORAGE,
                    onDraftEdit = if (state.busy) null else model::edit,
                    onDraftClear = if (state.busy) null else model::clear)
            }
        }
    }
    override fun onResume() {
        super.onResume()
        setupFlow?.navigationReturned()
        if (::model.isInitialized) refreshSetup(checkGrants = true, foreground = true)
    }
    private fun snapshot(): SetupSnapshot {
        val state = model.state.value
        return SetupSnapshot(!state.keyLoaded || (state.keyBusy && state.key.isEmpty()), model.setupKeyReady(), setupGrants)
    }
    private fun refreshSetup(checkGrants: Boolean = false, foreground: Boolean = false) {
        val flow = setupFlow ?: return
        if (checkGrants) setupGrants = testSetupGrants?.invoke() ?: SetupPlatform.grants(this)
        if (foreground) flow.foreground(snapshot()) else flow.update(snapshot())
    }
    internal fun setupPermissionReturned() {
        if (!::model.isInitialized || setupFlow == null) return
        setupGrants = testSetupGrants?.invoke() ?: SetupPlatform.grants(this)
        setupFlow?.permissionReturned(snapshot())
    }
    private fun deferSetup() {
        model.clearSetupKey(); pasteProblem = false; navigationProblem = false
        setupFlow?.defer()
    }
    private fun microphoneSettings() = setupFlow?.requested(SetupPermission.MICROPHONE) == true &&
        !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
    private fun notificationSettings() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED ||
        (setupFlow?.requested(SetupPermission.NOTIFICATIONS) == true && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
    private fun setupPrimary() {
        when (setupFlow?.state?.value?.step) {
            SetupStep.KEY -> model.saveSetupKey()
            SetupStep.MICROPHONE -> if (microphoneSettings()) navigateSetup(SetupAction.MICROPHONE)
                else requestSetupPermission(SetupPermission.MICROPHONE, Manifest.permission.RECORD_AUDIO, SetupAction.MICROPHONE)
            SetupStep.FLOATING -> navigateSetup(SetupAction.ACCESSIBILITY)
            SetupStep.NOTIFICATIONS -> if (notificationSettings()) navigateSetup(SetupAction.NOTIFICATIONS)
                else requestSetupPermission(SetupPermission.NOTIFICATIONS, Manifest.permission.POST_NOTIFICATIONS, SetupAction.NOTIFICATIONS)
            SetupStep.BACKGROUND -> setupFlow?.acknowledgeBackground()
            else -> Unit
        }
    }
    private fun requestSetupPermission(kind: SetupPermission, permissionName: String, action: SetupAction) {
        if (setupFlow?.beginPermission(kind) != true) return
        navigationProblem = false
        try { testSetupAction?.invoke(action) ?: setupPermission.launch(permissionName) }
        catch (_: Exception) { setupPermissionReturned(); navigationProblem = true }
    }
    private fun navigateSetup(action: SetupAction) {
        if (setupFlow?.beginNavigation() != true) return
        navigationProblem = false
        try { testSetupAction?.invoke(action) ?: startActivity(SetupPlatform.intent(this, action)) }
        catch (_: Exception) { setupFlow?.navigationReturned(); navigationProblem = true }
    }
    private fun pasteSetupKey(openSetup: Boolean) {
        val state = model.state.value
        val draft = model.setupKey.value
        val candidate = KeyPaste.candidate(state.key.ifEmpty { draft.text }, state.hasSavedKey,
            state.busy || !state.keyLoaded || !state.keyStoreKnown, lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && window.decorView.hasWindowFocus()) {
            val reader = testPasteReader
            if (reader != null) reader() else SetupPlatform.plainClipboard(this)
        }
        if (candidate == null) { pasteProblem = true; return }
        val current = model.state.value
        if (current.key.isNotEmpty() || current.hasSavedKey || !current.keyStoreKnown || current.busy || model.setupKey.value.text.isNotEmpty()) return
        model.editSetupKey(candidate); pasteProblem = false
        if (openSetup) setupFlow?.manual()
        refreshSetup()
    }
    override fun onStart() {super.onStart();if(::model.isInitialized)model.refreshDraft()}
    private fun record() {
        val state=model.state.value
        if (requestingPermission || state.audioBusy) return
        if (!model.checkHistoryCapacity()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startWithPermission()
        } else { requestingPermission = true; permission.launch(Manifest.permission.RECORD_AUDIO) }
    }
    private fun retainRetryRecording() {
        val problem=model.state.value.problem
        model.takeRetryRecording()?.let { RecordingRecovery.processOwned.retain(it,RecordingRetryFailure.from(problem)) }
    }
    private fun startWithPermission() {
        retainRetryRecording()
        model.start(true)
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
        setupFlow?.background(continuation = isChangingConfigurations || requestingPermission)
        requestingPermission = false
        if (::model.isInitialized) {
            val problem=model.state.value.problem
            model.background()?.let { RecordingRecovery.processOwned.retain(it,RecordingRetryFailure.from(problem)) }
        }
        super.onStop()
    }
    companion object {
        // In-process instrumentation seam; never set by exported intents or persisted configuration.
        internal var testFactory: ((Context) -> DictationViewModel)? = null
        internal var testNotice: String? = null
        internal var testSkipOnboarding: Boolean = false
        internal var testSetupStore: SetupStore? = null
        internal var testSetupGrants: (() -> SetupGrants)? = null
        internal var testSetupAction: ((SetupAction) -> Unit)? = null
        internal var testPasteReader: (() -> String?)? = null
    }
}
