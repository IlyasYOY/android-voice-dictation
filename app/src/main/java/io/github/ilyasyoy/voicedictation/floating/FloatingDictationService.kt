package io.github.ilyasyoy.voicedictation.floating

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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.github.ilyasyoy.voicedictation.R
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.ui.*
import kotlinx.coroutines.*

class FloatingDictationService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val stores = ViewModelStore()
    private var editorInput: EditorInput? = null
    private var original: EditorToken? = null
    private var pending: EditorToken? = null
    private var connection: Any? = null // API33 connection, never field text.
    private var model: DictationViewModel? = null
    private var observer: Job? = null
    private var insertion: Job? = null
    private var epoch = 0L
    private var retainedText = ""
    private var window: FloatingWindow? = null
    private var foreground = false
    private var finishRequested = false
    private var insertionAttempted = false
    private var inserting = false
    private var expanded = false
    private var message: String? = null
    private var needsSetup = false
    private var imeTop = 0
    private var disposed = false
    private var receiverRegistered = false
    private val refresh = Runnable { updateVisibility() }
    private val screenOff = object : BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { closeSession(); window?.hide() } }

    @RequiresApi(33)
    override fun onCreateInputMethod(): InputMethod = EditorInput(this) { scheduleRefresh() }.also { editorInput = it }
    override fun onServiceConnected() {
        instance = this
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.floating_channel), NotificationManager.IMPORTANCE_LOW))
        window = FloatingWindow(this, ::bubbleClick, ::stopFromUser, ::cancelFromUser, ::copy, ::closeSession, ::openSetup)
        scheduleRefresh()
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { scheduleRefresh() } // Never reads event.text/source.
    private fun scheduleRefresh() { if (!disposed) { handler.removeCallbacks(refresh); handler.postDelayed(refresh, 150) } }
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
            closeSession(); window?.hide(); return
        }
        if (model?.state?.value?.audioBusy == true && (!microphoneGranted() || !notificationsAllowed())) {
            cancelKeepingText(getString(R.string.floating_need_permission)); needsSetup = true
        }
        imeTop = keyboard.top
        val origin = original
        if (origin != null && !inserting && (model?.state?.value?.audioBusy == true) &&
            (!origin.sameEditor(current) || origin.selectionStart != current.selectionStart || origin.selectionEnd != current.selectionEnd)) {
            cancelKeepingText(getString(R.string.floating_focus_changed))
        }
        render()
        handler.removeCallbacks(refresh); handler.postDelayed(refresh, 400)
    }
    private fun render() {
        if (!FloatingPreferences.enabled(this) || !awake() || keyboardBounds() == null || editor()?.let(EditorPolicy::allowed) != true) { window?.hide(); return }
        val state = model?.state?.value
        val active = state?.audioBusy == true || state?.keyBusy == true
        val status = message ?: when (state?.phase) {
            Phase.CONNECTING -> getString(R.string.live_connect_status)
            Phase.RECORDING -> getString(R.string.live_stream_status) + " · " + state.elapsed
            Phase.FINALIZING -> getString(R.string.live_finish_status)
            Phase.ERROR -> getString(R.string.floating_stream_error)
            else -> getString(R.string.floating_start)
        }
        try { window?.render(imeTop, expanded, state?.text ?: retainedText, status, active, !(state?.text ?: retainedText).isBlank(), needsSetup, DebugFloatingConfiguration.notice(this)) }
        catch (_: Exception) { closeSession(); window?.hide() }
    }
    private fun microphoneGranted() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun notificationsAllowed(): Boolean = NotificationManagerCompat.from(this).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    private fun bubbleClick() {
        val state = model?.state?.value
        if (state?.phase == Phase.RECORDING) { stopFromUser(); return }
        if (state?.audioBusy == true || state?.keyBusy == true) return
        closeSession()
        original = editor()?.takeIf(EditorPolicy::allowed) ?: return
        expanded = true
        if (!microphoneGranted() || !notificationsAllowed()) {
            message = getString(R.string.floating_need_permission); needsSetup = true; render(); return
        }
        connection = if (Build.VERSION.SDK_INT >= 33) editorInput?.currentInputConnection else null
        pending = original
        try { ContextCompat.startForegroundService(this, Intent(this, FloatingDictationService::class.java).setAction(START)) }
        catch (_: Exception) { pending = null; message = getString(R.string.floating_start_blocked); needsSetup = true; render() }
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
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_launcher).setContentTitle(getString(R.string.app_name))
            .setContentText(getString(when (model?.state?.value?.phase) { Phase.RECORDING -> R.string.live_stream_status; Phase.FINALIZING -> R.string.live_finish_status; else -> R.string.live_connect_status }))
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, getString(R.string.stop_live), action(STOP, 1)).addAction(0, getString(R.string.floating_cancel), action(CANCEL, 2)).build()
    }
    private fun begin() {
        if (model != null || !microphoneGranted()) return
        try {
            ServiceCompat.startForeground(this, NOTIFICATION, notification(), if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
            foreground = true
        } catch (_: Exception) { message = getString(R.string.floating_start_blocked); needsSetup = true; render(); return }
        finishRequested = false; insertionAttempted = false; message = null
        val vm = DebugFloatingConfiguration.model(this) ?: DictationViewModel(AndroidAudioRecorder(this), OpenAiTranscriber(), AndroidPcmSource(), OpenAiRealtimeTranscriber(), AndroidApiKeyStore(this))
        model = ViewModelProvider(stores, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
        })[DictationViewModel::class.java]
        var launched = false
        observer = scope.launch {
            vm.state.collect { state ->
                if (!launched && !state.keyBusy) {
                    launched = true
                    if (!state.validKey) { message = getString(R.string.floating_need_key); needsSetup = true; endForeground() }
                    else { vm.setMode(Mode.LIVE); vm.start(true) }
                }
                if (state.phase == Phase.ERROR) { finishRequested = false; endForeground(); message = getString(R.string.floating_stream_error) }
                if (state.phase == Phase.RESULT) {
                    endForeground()
                    if (finishRequested && !insertionAttempted) insert(state.text, state.livePartial)
                }
                if (foreground && microphoneGranted()) getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification())
                render()
            }
        }
    }
    private fun stopFromUser() {
        if (model?.state?.value?.phase != Phase.RECORDING) { cancelFromUser(); return }
        finishRequested = true; model?.stop()
    }
    private fun cancelFromUser() = cancelKeepingText(getString(R.string.floating_cancelled))
    private fun cancelKeepingText(reason: String) {
        val text = model?.state?.value?.text ?: retainedText
        closeSession() // Cancels pending key load and its observer, so Cancel can never start a microphone later.
        retainedText = text; expanded = true; message = reason; render()
    }
    private fun insert(text: String, partial: Boolean) {
        insertionAttempted = true; finishRequested = false
        val token = original; val current = editor(); val input = connection
        if (partial || token == null || current == null || input == null ||
            !EditorPolicy.canInsert(token, current, Build.VERSION.SDK_INT, text) || Build.VERSION.SDK_INT < 33) {
            message = getString(R.string.floating_copy_fallback); render(); return
        }
        inserting = true
        val currentEpoch = epoch
        insertion = scope.launch {
            try {
                val conn = input as InputMethod.AccessibilityInputConnection
                // Zero-length preflight reads only caret metadata; unsupported editors get Copy without any write.
                val before = withContext(Dispatchers.IO) { conn.getSurroundingText(0, 0, 0) }
                val stillFocused = editor()
                if (before == null || before.offset < 0 || before.selectionStart != before.selectionEnd ||
                    before.offset + before.selectionStart != token.selectionStart || stillFocused == null ||
                    !EditorPolicy.canInsert(token, stillFocused, Build.VERSION.SDK_INT, text) || currentEpoch != epoch) {
                    if (currentEpoch == epoch) message = getString(R.string.floating_copy_fallback)
                    return@launch
                }
                conn.commitText(text, 1, null) // Never set all text, press keys, submit, paste, or perform editor actions.
                delay(250)
                val surrounding = withContext(Dispatchers.IO) { conn.getSurroundingText(minOf(text.length, 256), 0, 0) }
                val now = editor()
                val ok = now != null && token.sameEditor(now) && EditorPolicy.allowed(now) && surrounding != null && surrounding.offset >= 0 &&
                    EditorPolicy.confirmed(token.selectionStart, text, surrounding.offset + surrounding.selectionStart,
                        surrounding.offset + surrounding.selectionEnd, surrounding.text)
                if (currentEpoch == epoch) message = getString(if (ok) R.string.floating_inserted else R.string.floating_unconfirmed)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (currentEpoch == epoch) message = getString(R.string.floating_unconfirmed) }
            finally { if (currentEpoch == epoch) { inserting = false; original = null; connection = null; render() } }
        }
    }
    private fun copy() {
        val text = model?.state?.value?.text ?: retainedText
        if (text.isBlank()) return
        val clip = ClipData.newPlainText(getString(R.string.transcript), text)
        if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        try { getSystemService(ClipboardManager::class.java).setPrimaryClip(clip); message = getString(R.string.copied) }
        catch (_: Exception) { message = getString(R.string.floating_copy_failed) }
        render()
    }
    private fun endForeground() {
        if (foreground) { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE); foreground = false; stopSelf() }
    }
    private fun closeSession() {
        epoch++; insertion?.cancel(); insertion = null
        pending = null; finishRequested = false; insertionAttempted = true; original = null; connection = null
        observer?.cancel(); observer = null; model?.cancel(); model = null; stores.clear(); endForeground()
        retainedText = ""; expanded = false; message = null; needsSetup = false; inserting = false
        window?.hide()
    }
    private fun openSetup() { startActivity(Intent(this, FloatingSetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    fun disableFromUser() { closeSession(); disableSelf() }
    override fun onInterrupt() { closeSession() }
    override fun onUnbind(intent: Intent?): Boolean { closeSession(); return super.onUnbind(intent) }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) { super.onConfigurationChanged(newConfig); closeSession(); scheduleRefresh() }
    override fun onDestroy() {
        disposed = true; handler.removeCallbacksAndMessages(null); closeSession(); scope.cancel()
        if (receiverRegistered) { unregisterReceiver(screenOff); receiverRegistered = false }
        if (instance === this) instance = null
        super.onDestroy()
    }
    companion object {
        @Volatile internal var instance: FloatingDictationService? = null
        private const val CHANNEL = "floating_microphone"
        private const val NOTIFICATION = 41
        private const val START = "io.github.ilyasyoy.voicedictation.floating.START"
        private const val STOP = "io.github.ilyasyoy.voicedictation.floating.STOP"
        private const val CANCEL = "io.github.ilyasyoy.voicedictation.floating.CANCEL"
    }
}
