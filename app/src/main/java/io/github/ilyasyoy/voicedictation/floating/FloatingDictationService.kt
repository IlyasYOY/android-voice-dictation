package io.github.ilyasyoy.voicedictation.floating

import io.github.ilyasyoy.voicedictation.diagnostics.*

import io.github.ilyasyoy.voicedictation.session.*

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Rect
import android.os.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.ilyasyoy.voicedictation.R
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.session.DictationSessions
import kotlinx.coroutines.*

class FloatingDictationService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var editorInput: EditorInput? = null
    private var original: EditorToken? = null
    private var pending: EditorToken? = null
    private var connection: EditorConnection? = null
    private var capture: FloatingCapture? = null
    private val model get() = capture?.session
    private var insertion: Job? = null
    private var epoch = 0L
    private val feedback = FloatingFeedback.processOwned
    private var window: FloatingWindow? = null
    private var foreground = false
    private var finishRequested = false
    private var insertionAttempted = false
    private var inserting = false
    private var commitAttempted = false
    private var imeTop = 0
    private var editorEligible = false
    private var nextRefreshAt = Long.MAX_VALUE
    private var disposed = false
    private var receiverRegistered = false
    private val refresh = Runnable { nextRefreshAt = Long.MAX_VALUE; updateVisibility() }
    private val screenOff = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { Diagnostics.record(DiagnosticArea.OVERLAY, DiagnosticAction.SCREEN_OFF); editorEligible = false; closeSession(); window?.hide() } }

    @RequiresApi(33)
    override fun onCreateInputMethod(): InputMethod = EditorInput(this) { scheduleRefresh() }.also { editorInput = it }
    override fun onServiceConnected() {
        Diagnostics.record(DiagnosticArea.OVERLAY, DiagnosticAction.CONNECT)
        instance = this
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.floating_channel), NotificationManager.IMPORTANCE_LOW))
        feedback.dismiss()
        window = createWindow()
        scheduleRefresh()
    }
    private fun createWindow(): FloatingWindow {
        return FloatingWindow(this, ::bubbleClick, ::cancelFromUser, ::copy, { closeSession(hideWindow = false); render() }, ::openSetup,
            discard = { closeSession(hideWindow = false, discardFeedback = true); render() }, recover = ::openRecovery)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { scheduleRefresh() } // Never reads event.text/source.
    private fun scheduleRefresh(delay: Long = 150) {
        if (disposed) return
        val due = SystemClock.uptimeMillis() + delay
        // A stream of overlay/window events may bring a check forward, never postpone an existing one.
        if (due < nextRefreshAt) {
            handler.removeCallbacks(refresh); nextRefreshAt = due; handler.postDelayed(refresh, delay)
        }
    }
    private fun keyboardBounds(): Rect? = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        ?.let { Rect().apply { it.getBoundsInScreen(this) } }
    private fun editor(): EditorToken? {
        val appWindow = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused } ?: return null
        val node = appWindow.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (!node.refresh()) return null
        if (!node.isEditable || !node.isEnabled || !node.isVisibleToUser || !node.isFocused || node.packageName?.toString() == packageName) return null
        val bounds = Rect().apply { node.getBoundsInScreen(this) }
        val pkg = node.packageName?.toString() ?: return null
        val modern = if (Build.VERSION.SDK_INT >= 33) editorInput?.takeIf { it.editorPackage == pkg } else null
        return EditorToken(pkg, node.windowId, node.viewIdResourceName ?: "${node.className}:${bounds.flattenToString()}",
            if (Build.VERSION.SDK_INT >= 33) modern?.serial ?: 0 else 0,
            if (Build.VERSION.SDK_INT >= 33) modern?.selectionStart ?: node.textSelectionStart else node.textSelectionStart,
            if (Build.VERSION.SDK_INT >= 33) modern?.selectionEnd ?: node.textSelectionEnd else node.textSelectionEnd, node.isPassword,
            if (Build.VERSION.SDK_INT >= 34) node.isAccessibilityDataSensitive else false,
            if (Build.VERSION.SDK_INT >= 33) modern?.inputType ?: node.inputType else node.inputType,
            if (Build.VERSION.SDK_INT >= 33) modern?.imeOptions ?: 0 else 0,
            if (Build.VERSION.SDK_INT >= 33) modern?.composingStart ?: -1 else -1,
            if (Build.VERSION.SDK_INT >= 33) modern?.composingEnd ?: -1 else -1)
    }
    private fun awake() = getSystemService(PowerManager::class.java).isInteractive && !getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun updateVisibility() {
        if (disposed) return
        val keyboard = keyboardBounds()
        val current = editor()
        if (!FloatingPreferences.enabled(this) || !awake() || keyboard == null || current == null || !EditorPolicy.allowed(current)) {
            editorEligible = false
            if (model != null) DebugFloatingConfiguration.trace(this, "hide: keyboard=${keyboard != null}, editor=${current != null}, allowed=${current?.let(EditorPolicy::allowed)}, awake=${awake()}")
            closeSession(); window?.hide(); return
        }
        if (model?.state?.value?.busy == true && (!microphoneGranted() || !notificationsAllowed())) {
            cancelKeepingText(getString(R.string.floating_need_permission)); feedback.needsSetup = true
        }
        editorEligible = true
        imeTop = keyboard.top
        val origin = original
        if (origin != null && !inserting && (model?.state?.value?.busy == true || pending != null) &&
            (!origin.sameEditor(current) || origin.selectionStart != current.selectionStart || origin.selectionEnd != current.selectionEnd)) {
            DebugFloatingConfiguration.trace(this, "cancel: sameEditor=${origin.sameEditor(current)}, caretSame=${origin.selectionStart == current.selectionStart && origin.selectionEnd == current.selectionEnd}")
            cancelKeepingText(getString(R.string.floating_focus_changed))
        }
        render()
        scheduleRefresh(400)
    }
    private fun render() {
        if (!editorEligible || !FloatingPreferences.enabled(this) || !awake()) { window?.hide(); return }
        val state = model?.state?.value
        val active = state?.audioBusy == true || state?.keyBusy == true
        val capacityFull = runCatching { AndroidRecordingStorage.repository(this).capacity().full }.getOrDefault(false)
        val status = if (capacityFull && !active) getString(R.string.history_full) else feedback.message ?: when (state?.phase) {
            Phase.RECORDING -> getString(R.string.recording) + " · " + state.elapsed
            Phase.TRANSCRIBING -> getString(R.string.transcribing)
            Phase.PROCESSING -> getString(R.string.text_processing)
            Phase.ERROR -> getString(R.string.error)
            else -> getString(R.string.floating_start)
        }
        try { window?.render(imeTop, false, state?.text ?: feedback.text, status, active, feedback.text.isNotBlank() || RecordingRecovery.processOwned.state.value.savedCount>0, feedback.needsSetup, DebugFloatingConfiguration.notice(this), active && state?.phase != Phase.RECORDING, feedback.error || state?.problem != null, state?.keyBusy == true, historyFull = capacityFull, levels = state?.voiceLevels ?: io.github.ilyasyoy.voicedictation.audio.VoiceLevelSource.Silent, keepScreenOn = capture?.keepsScreenOn == true) }
        catch (error: Exception) { Diagnostics.failure(DiagnosticArea.OVERLAY, DiagnosticAction.RENDER, error); closeSession(); window?.hide() }
    }
    private fun microphoneGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun notificationsAllowed(): Boolean = NotificationManagerCompat.from(this).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    private fun bubbleClick() {
        val state = model?.state?.value
        if (state?.phase == Phase.RECORDING) { stopFromUser(); return }
        if (state?.audioBusy == true || state?.keyBusy == true || inserting) return
        val capacity = runCatching { AndroidRecordingStorage.repository(this).capacity() }.getOrNull()
        if (capacity?.full == true) { openRecovery(); return }
        if (capacity == null || !capacity.readable) {
            offerRecovery(feedback.text, getString(R.string.error), error = true); return
        }
        startNew()
    }
    private fun startNew() {
        closeSession(hideWindow = false)
        feedback.resetSignal()
        original = editor()?.takeIf(EditorPolicy::allowed) ?: return
        feedback.expanded = false
        if (!microphoneGranted() || !notificationsAllowed()) {
            offerRecovery("",getString(R.string.floating_need_permission),true); return
        }
        connection = if (Build.VERSION.SDK_INT >= 33) editorInput?.currentInputConnection?.let(::AndroidEditorConnection) else null
        pending = original
        try { ContextCompat.startForegroundService(this, Intent(this, FloatingDictationService::class.java).setAction(START)) }
        catch (error: Exception) { Diagnostics.failure(DiagnosticArea.OVERLAY, DiagnosticAction.FOREGROUND, error); offerRecovery("",getString(R.string.floating_start_blocked),true) }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            START -> {
                val token = pending; pending = null
                val current = editor()
                if (token != null && current != null && token.sameEditor(current) && EditorPolicy.allowed(current) && token.selectionStart == current.selectionStart && token.selectionEnd == current.selectionEnd && keyboardBounds() != null && awake() && FloatingPreferences.enabled(this)) begin()
                else stopSelf(startId)
            }
            STOP -> stopFromUser()
            CANCEL -> cancelFromUser()
        }
        return START_NOT_STICKY
    }
    private fun notification(): Notification {
        fun action(value: String, request: Int) = PendingIntent.getService(this, request,
            Intent(this, FloatingDictationService::class.java).setAction(value), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_current_notification).setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (model?.state?.value?.phase == Phase.TRANSCRIBING) R.string.transcribing else R.string.recording))
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, getString(R.string.stop), action(STOP, 1)).addAction(0, getString(R.string.floating_cancel), action(CANCEL, 2)).build()
    }
    private fun begin() {
        if (model != null || !microphoneGranted()) return
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, notification(), if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            foreground = true
        } catch (error: Exception) { Diagnostics.failure(DiagnosticArea.OVERLAY, DiagnosticAction.FOREGROUND, error); offerRecovery("",getString(R.string.floating_start_blocked),true); return }
        finishRequested = false; insertionAttempted = false; commitAttempted = false; feedback.resetSignal()
        val session = DebugFloatingConfiguration.session(this, scope) ?: DictationSessions.create(this, scope)
        val origin = original ?: run { session.close(); endForeground(); return }
        capture = FloatingCapture(scope, session, origin, ::environment,
            onInvalid = ::invalidStart,
            onState = capacityState@{ state ->
                if (state.capacityBlocked) {
                    closeSession(hideWindow = false, retainCurrent = false)
                    render()
                    return@capacityState
                }
                DebugFloatingConfiguration.trace(this, "state=${state.phase}, loadingKey=${state.keyBusy}, problem=${state.problem}")
                if (state.phase == Phase.ERROR) {
                    if(state.canRetry) session.takeRetryRecording()?.let { RecordingRecovery.processOwned.retain(it,RecordingRetryFailure.from(state.problem)) }
                    offerRecovery(state.text,getString(if (state.processingFailed) R.string.text_processing_failed else R.string.error), error = true)
                }
                if (state.phase in setOf(Phase.TRANSCRIBING, Phase.PROCESSING)) endForeground()
                if (state.phase == Phase.RESULT) {
                    endForeground()
                    if (finishRequested && !insertionAttempted) insert(state.text)
                }
                if (foreground && microphoneGranted()) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
                render()
            }, onScreenOn = { window?.setScreenOn(it) })
        capture?.start()
    }
    private fun invalidStart() {
        Diagnostics.record(DiagnosticArea.OVERLAY, DiagnosticAction.START, DiagnosticCode.BLOCKED)
        if (!FloatingPreferences.enabled(this) || !awake() || keyboardBounds() == null) { closeSession(); return }
        val permissionLost = !microphoneGranted() || !notificationsAllowed()
        cancelKeepingText(getString(if (permissionLost) R.string.floating_need_permission else R.string.floating_focus_changed))
        if (permissionLost) { feedback.needsSetup = true; render() }
    }
    private fun environment() = FloatingEnvironment(editor(), FloatingPreferences.enabled(this), awake(),
        keyboardBounds() != null, microphoneGranted(), notificationsAllowed())
    private fun stopFromUser() {
        if (model?.state?.value?.phase != Phase.RECORDING) { cancelFromUser(); return }
        finishRequested = true; model?.stop()
    }
    private fun cancelFromUser() = cancelKeepingText(getString(R.string.floating_cancelled),discardRecording=true)
    private fun cancelKeepingText(reason: String,discardRecording: Boolean=false) {
        Diagnostics.record(DiagnosticArea.OVERLAY, DiagnosticAction.CANCEL)
        val text = model?.state?.value?.text ?: feedback.text
        closeSession(hideWindow = false, retainCurrent = false, retainRecording = !discardRecording)
        feedback.offer(text,reason); render()
    }
    private fun insert(text: String) {
        insertionAttempted = true; finishRequested = false
        if (!FloatingPreferences.enabled(this) || !awake() || keyboardBounds() == null) { closeSession(); return }
        val token = original; val current = editor(); val input = connection
        if (token == null || current == null || input == null ||
            !EditorPolicy.canInsert(token, current, Build.VERSION.SDK_INT, text) || Build.VERSION.SDK_INT < 33) {
            Diagnostics.record(DiagnosticArea.INSERTION, DiagnosticAction.INSERT, DiagnosticCode.COPY)
            offerRecovery(text,getString(R.string.floating_copy_fallback)); return
        }
        inserting = true
        val currentEpoch = epoch
        insertion = scope.launch {
            try {
                val result = FinalTextInsertion.insert(token, text, input, ::environment, { commitAttempted = true }) {
                    currentEpoch == epoch && !disposed
                }
                Diagnostics.record(DiagnosticArea.INSERTION, DiagnosticAction.INSERT, DiagnosticCode.valueOf(result.name))
                if (currentEpoch == epoch) {
                    if (result == InsertionResult.CANCELLED) closeSession()
                    else {
                        feedback.complete(result,text,getString(R.string.floating_copy_fallback),getString(R.string.floating_unconfirmed))
                        // Both confirmed and recoverable completion release key/editor/session ownership.
                        // The same mark settles to idle or attention while only recovery text remains.
                        closeSession(hideWindow = false, retainCurrent = false)
                        render()
                    }
                }
            } catch (e: CancellationException) { throw e }
            finally { if (currentEpoch == epoch) { inserting = false; original = null; connection = null; render() } }
        }
    }
    private fun offerRecovery(text: String, reason: String, needsSetup: Boolean = false, error: Boolean = false) {
        feedback.offer(text,reason,needsSetup,error)
        closeSession(hideWindow = false, retainCurrent = false)
        stopSelf() // Also clear a started-service request if startForeground itself was rejected.
        render()
    }
    private fun copy() {
        val text = model?.state?.value?.text ?: feedback.text
        if (text.isBlank()) return
        val clip = ClipData.newPlainText(getString(R.string.transcript), text)
        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        try { getSystemService(ClipboardManager::class.java).setPrimaryClip(clip); feedback.message = getString(R.string.copied) }
        catch (error: Exception) { Diagnostics.failure(DiagnosticArea.OVERLAY, DiagnosticAction.COPY, error); feedback.message = getString(R.string.floating_copy_failed) }
        render()
    }
    private fun endForeground() {
        if (foreground) { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); foreground = false; stopSelf() }
    }
    private fun closeSession(hideWindow: Boolean = true, discardFeedback: Boolean = false, retainCurrent: Boolean = true, retainRecording: Boolean = true) {
        window?.setScreenOn(false)
        // Stop all resources/clear editor ownership independently of pending user text.
        // A hidden keyboard, Setup, lock or service rebind must never turn recovery into data loss.
        val state = model?.state?.value
        if(retainRecording) model?.takeRecordingForRecovery()?.let {
            RecordingRecovery.processOwned.retain(it,RecordingRetryFailure.from(state?.problem))
        }
        if (retainCurrent && commitAttempted && !discardFeedback && state != null) {
            // The write may already have happened: retain only passive text, never a failure/retry gate.
            feedback.retain(state.text); feedback.resetSignal()
        }
        if (retainCurrent && !commitAttempted && !discardFeedback && state != null && (state.text.isNotBlank() || (state.busy && !feedback.available))) {
            feedback.offer(state.text, getString(when {
                inserting || (insertionAttempted && state.phase == Phase.RESULT) -> R.string.floating_unconfirmed
                state.busy -> R.string.floating_cancelled
                else -> R.string.floating_copy_fallback
            }))
        }
        epoch++; insertion?.cancel(); insertion = null
        pending = null; finishRequested = false; insertionAttempted = true; original = null; connection = null
        capture?.close(); capture = null; endForeground()
        if (discardFeedback) feedback.discard() else feedback.dismiss()
        inserting = false
        if (hideWindow) window?.hide()
    }
    private fun openSetup() { startActivity(Intent(this, FloatingSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    private fun openRecovery() { startActivity(Intent(this,io.github.ilyasyoy.voicedictation.SavedRecordingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun disableFromUser() { closeSession(); disableSelf() }
    override fun onInterrupt() { Diagnostics.record(DiagnosticArea.OVERLAY, DiagnosticAction.INTERRUPT); closeSession() }
    override fun onUnbind(intent: Intent?): Boolean { Diagnostics.record(DiagnosticArea.OVERLAY, DiagnosticAction.UNBIND); closeSession(); return super.onUnbind(intent) }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) { super.onConfigurationChanged(newConfig); Diagnostics.record(DiagnosticArea.OVERLAY, DiagnosticAction.CONFIGURATION); closeSession(); window = createWindow(); scheduleRefresh() }
    override fun onDestroy() {
        Diagnostics.record(DiagnosticArea.OVERLAY, DiagnosticAction.CLOSE)
        disposed = true; handler.removeCallbacksAndMessages(null); closeSession(); scope.cancel()
        if (receiverRegistered) { unregisterReceiver(screenOff); receiverRegistered = false }
        if (instance === this) instance = null
        super.onDestroy()
    }
    companion object {
        @Volatile internal var instance: FloatingDictationService? = null
        internal const val CHANNEL = "floating_microphone"
        private const val NOTIFICATION = 41
        private const val START = "io.github.ilyasyoy.voicedictation.floating.START"
        private const val STOP = "io.github.ilyasyoy.voicedictation.floating.STOP"
        private const val CANCEL = "io.github.ilyasyoy.voicedictation.floating.CANCEL"
    }
}
