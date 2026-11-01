package io.github.ilyasyoy.voicedictation.ui

import io.github.ilyasyoy.voicedictation.session.*
import io.github.ilyasyoy.voicedictation.ui.design.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.ilyasyoy.voicedictation.R

@Composable
fun DictationScreen(
    state: DictationState, onKey: (String) -> Unit, onRecord: () -> Unit, onStop: () -> Unit,
    onCancel: () -> Unit, onRetry: () -> Unit, onEdit: (String) -> Unit, onCopy: () -> Unit,
    onClear: () -> Unit, onForget: () -> Unit, onMode: (Mode) -> Unit,
    onSave: () -> Unit, onFloatingSetup: () -> Unit = {}, evidenceNotice: String? = null,
    savedRecordings: Int = 0, onSavedRecordings: () -> Unit = onFloatingSetup,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var controlsOpen by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(settingsOpen) { scroll.scrollTo(0) }
    LaunchedEffect(state.audioBusy) { if (!state.audioBusy) controlsOpen = false }
    val historyGesture = remember(settingsOpen, state.audioBusy, onSavedRecordings) {
        object : NestedScrollConnection {
            var distance = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && !settingsOpen && !state.audioBusy) {
                    distance = if (available.y < 0) distance - available.y else 0f
                    if (distance > 80f) { distance = 0f; onSavedRecordings() }
                }
                return Offset.Zero
            }
        }
    }
    BackHandler(settingsOpen) { settingsOpen = false }
    Scaffold(modifier = Modifier.safeDrawingPadding(), contentWindowInsets = WindowInsets(0), topBar = {
        Column {
            if (evidenceNotice != null) Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(evidenceNotice, Modifier.fillMaxWidth().padding(8.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelSmall)
            }
            // Save/Forget happen in Settings: failures must stay visible even while its form is scrolled.
            if (settingsOpen && state.problem == Problem.STORAGE) Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(stringResource(R.string.storage_error), Modifier.fillMaxWidth()
                    .padding(16.dp).testTag("problem").semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets).imePadding(), contentAlignment = Alignment.TopCenter) {
            val controlSize = minOf(228.dp, maxWidth - 48.dp, maxHeight * .40f).coerceAtLeast(112.dp)
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().nestedScroll(historyGesture).verticalScroll(scroll)
                .padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val settingsLabel = stringResource(R.string.current_settings)
                val backLabel = stringResource(R.string.current_back)
                val controlsLabel = stringResource(R.string.current_controls)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (settingsOpen) TextButton({ settingsOpen = false }, Modifier.testTag("close_settings").semantics { contentDescription = backLabel }) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
                    if (settingsOpen) Text(stringResource(R.string.current_settings),
                        Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    if (!settingsOpen && state.mode == Mode.LIVE && state.audioBusy) Box {
                        IconButton({ controlsOpen = true }, Modifier.testTag("session_controls").semantics { contentDescription = controlsLabel }) { Text("⋮") }
                        DropdownMenu(controlsOpen, { controlsOpen = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.current_cancel_session)) },
                                onClick = { controlsOpen = false; onCancel() }, modifier = Modifier.testTag("cancel"))
                        }
                    }
                    if (!settingsOpen) Spacer(Modifier.weight(1f))
                    if (!settingsOpen) IconButton({ settingsOpen = true }, Modifier.testTag("open_settings").semantics { contentDescription = settingsLabel }, enabled = !state.audioBusy) {
                        val color = MaterialTheme.colorScheme.onSurface
                        Canvas(Modifier.size(24.dp)) {
                            drawCircle(color, size.minDimension*.29f, style = Stroke(2.dp.toPx()))
                            drawCircle(color, size.minDimension*.09f, style = Stroke(2.dp.toPx()))
                            for (i in 0..7) {
                                val a = i * Math.PI / 4
                                drawLine(color, Offset(center.x+(kotlin.math.cos(a)*size.width*.34).toFloat(), center.y+(kotlin.math.sin(a)*size.height*.34).toFloat()),
                                    Offset(center.x+(kotlin.math.cos(a)*size.width*.45).toFloat(), center.y+(kotlin.math.sin(a)*size.height*.45).toFloat()), strokeWidth = 2.dp.toPx())
                            }
                        }
                    }
                }
                if (settingsOpen) {
                    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.current_mode), style = MaterialTheme.typography.titleMedium)
                            ModeSelector(state.mode, !state.busy && !state.canRetry, onMode)
                            Text(stringResource(R.string.current_mode_detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.setup), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.cost_notice), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(state.key, onKey, Modifier.fillMaxWidth().testTag("api_key"),
                    label = { Text(stringResource(R.string.api_key)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !state.busy)
                if (state.keyBusy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("key_progress"))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button({ focus.clearFocus(); keyboard?.hide(); onSave() }, Modifier.testTag("save_key"), enabled = state.validKey && !state.keySaved && !state.busy) {
                        Text(stringResource(R.string.save_key))
                    }
                }
                if (state.keySaved) Text(stringResource(R.string.key_saved), Modifier.testTag("key_saved"), style = MaterialTheme.typography.bodySmall)
                else if (state.hasSavedKey) Text(stringResource(R.string.key_changed), style = MaterialTheme.typography.bodySmall)
                if (state.key.isNotEmpty() || state.hasSavedKey || state.problem == Problem.STORAGE) TextButton({ focus.clearFocus(); keyboard?.hide(); onForget() }, Modifier.testTag("forget"), enabled = !state.busy) {
                    Text(stringResource(R.string.forget))
                }
                Text(stringResource(R.string.privacy), style = MaterialTheme.typography.bodySmall)                        }
                    }
                    OutlinedButton(onFloatingSetup, Modifier.fillMaxWidth().testTag("floating_setup"), enabled = !state.audioBusy) { Text(stringResource(R.string.floating_setup)) }
                    return@Column
                }
                val recording = state.phase == Phase.RECORDING
                val processing = state.phase in setOf(Phase.CONNECTING, Phase.TRANSCRIBING, Phase.FINALIZING)
                CurrentControl(stringResource(if (recording) (if (state.mode == Mode.LIVE) R.string.stop_live else R.string.stop) else if (processing) R.string.current_processing else R.string.record),
                    if (recording) "stop" else if (processing) "processing" else "record",
                    when { recording -> CurrentVisual.RECORDING; state.phase == Phase.CONNECTING || state.keyBusy -> CurrentVisual.CONNECTING; processing -> CurrentVisual.PROCESSING; state.problem != null -> CurrentVisual.ERROR; else -> CurrentVisual.IDLE },
                    if (recording) true else !processing && (state.mode==Mode.RECORD || (state.validKey && !state.keyBusy)),
                    if (recording) onStop else onRecord, controlSize)
                ModeSelector(state.mode, !state.busy && !state.canRetry, onMode)
                Column(Modifier.fillMaxWidth().heightIn(min = 104.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.problem != null) {
                    Text(stringResource(R.string.error), Modifier.testTag("problem")
                        .semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
                }
                if (state.phase !in setOf(Phase.IDLE, Phase.RESULT, Phase.ERROR)) Text(stringResource(when (state.phase) {
                    Phase.CONNECTING -> R.string.connecting
                    Phase.FINALIZING -> R.string.finalizing
                    Phase.RECORDING -> if (state.mode == Mode.LIVE) R.string.live_recording else R.string.recording
                    Phase.TRANSCRIBING -> R.string.transcribing
                    else -> R.string.error
                }), Modifier.testTag("status"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (recording) Text(state.elapsed, Modifier.testTag("timer"), style = MaterialTheme.typography.titleLarge)
                if (state.mode == Mode.RECORD && state.audioBusy) TextButton(onCancel, Modifier.testTag("cancel")) {
                    Text(stringResource(if (state.mode == Mode.LIVE) R.string.cancel_live else R.string.cancel))
                }
                if (!state.validKey && !state.audioBusy && state.problem == null) TextButton({ settingsOpen = true }, Modifier.testTag("add_key")) { Text(stringResource(R.string.current_add_key)) }
                if (state.mode == Mode.LIVE && state.audioBusy) Text(stringResource(when (state.phase) {
                    Phase.CONNECTING -> R.string.live_connect_status; Phase.RECORDING -> R.string.live_stream_status; else -> R.string.live_finish_status
                }), Modifier.testTag("live_status"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.safetyStopped && state.problem == null) Text(stringResource(R.string.safety_stopped), Modifier.testTag("safety_stopped"))
                if (state.canRetry) Button(onRetry, Modifier.testTag("retry"), enabled = state.validKey && !state.keyBusy) { Text(stringResource(R.string.retry)) }
                if (state.text.isNotBlank() || state.phase == Phase.RESULT) {
                    OutlinedTextField(state.text, onEdit, Modifier.fillMaxWidth().testTag("transcript"), label = { Text(stringResource(R.string.transcript)) },
                        minLines = 2, readOnly = state.busy, shape = RoundedCornerShape(24.dp))
                    if (state.mode == Mode.LIVE && state.livePartial) Text(
                        stringResource(R.string.partial_text),
                        Modifier.testTag("live_text_status"), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onCopy, Modifier.weight(1f).testTag("copy"), enabled = !state.busy && state.text.isNotBlank()) { Text(stringResource(R.string.copy)) }
                        OutlinedButton(onClear, Modifier.weight(1f).testTag("clear"), enabled = !state.busy && state.text.isNotEmpty()) { Text(stringResource(R.string.clear)) }
                    }
                }
                val historyLabel = stringResource(R.string.history_open)
                Column(Modifier.fillMaxWidth().testTag("history_swipe").pointerInput(state.audioBusy) {
                    var distance = 0f
                    detectVerticalDragGestures(onDragStart = { distance = 0f }, onVerticalDrag = { change, amount ->
                        distance += amount
                        if (!state.audioBusy) change.consume()
                    }, onDragEnd = { if (!state.audioBusy && distance < -48.dp.toPx()) onSavedRecordings() })
                }, horizontalAlignment = Alignment.CenterHorizontally) {
                    TextButton(onSavedRecordings,Modifier.testTag("saved_recordings").semantics { contentDescription = historyLabel },enabled=!state.audioBusy) {
                        Text(stringResource(R.string.saved_recordings,savedRecordings))
                    }
                    Text(stringResource(R.string.history_swipe_hint),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(stringResource(R.string.current_tap_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                TextButton(onFloatingSetup, Modifier.testTag("floating_setup"), enabled = !state.audioBusy) { Text(stringResource(R.string.floating_setup)) }
            }
        }
    }
}
