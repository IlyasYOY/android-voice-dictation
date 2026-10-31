package io.github.ilyasyoy.voicedictation.ui

import io.github.ilyasyoy.voicedictation.session.*
import io.github.ilyasyoy.voicedictation.ui.design.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
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
    onClear: () -> Unit, onForget: () -> Unit, onSettings: () -> Unit, onMode: (Mode) -> Unit,
    onSave: () -> Unit, onFloatingSetup: () -> Unit = {}, evidenceNotice: String? = null,
) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(settingsOpen) { scroll.scrollTo(0) }
    BackHandler(settingsOpen) { settingsOpen = false }
    Scaffold(topBar = {
        Column {
            if (evidenceNotice != null) Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(evidenceNotice, Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelSmall)
            }
            // Save/Forget happen in Settings: failures must stay visible even while its form is scrolled.
            if (settingsOpen && state.problem == Problem.STORAGE) Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(stringResource(R.string.storage_error), Modifier.fillMaxWidth()
                    .then(if (evidenceNotice == null) Modifier.statusBarsPadding() else Modifier)
                    .padding(16.dp).testTag("problem").semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(scroll)
                .padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val settingsLabel = stringResource(R.string.current_settings)
                val backLabel = stringResource(R.string.current_back)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (settingsOpen) TextButton({ settingsOpen = false }, Modifier.testTag("close_settings").semantics { contentDescription = backLabel }) { Text("‹", style = MaterialTheme.typography.headlineSmall) }
                    Text(stringResource(if (settingsOpen) R.string.current_settings else R.string.app_name),
                        Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
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
                    Text(stringResource(R.string.current_settings_intro), Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.current_mode), style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                FilterChip(state.mode == Mode.RECORD, { onMode(Mode.RECORD) }, label = { Text(stringResource(R.string.mode_record)) },
                                    enabled = !state.busy && !state.canRetry, modifier = Modifier.testTag("mode_record"))
                                FilterChip(state.mode == Mode.LIVE, { onMode(Mode.LIVE) }, label = { Text(stringResource(R.string.mode_live)) },
                                    enabled = !state.busy && !state.canRetry, modifier = Modifier.testTag("mode_live"))
                            }
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
                TextButton({ settingsOpen = true }, Modifier.testTag("mode_summary"), enabled = !state.audioBusy) {
                    Text(stringResource(if (state.mode == Mode.RECORD) R.string.mode_record else R.string.mode_live) + "  ·  " + stringResource(R.string.current_change))
                }
                Text(stringResource(when (state.phase) {
                    Phase.IDLE -> R.string.current_idle
                    Phase.CONNECTING -> R.string.connecting
                    Phase.FINALIZING -> R.string.finalizing
                    Phase.RECORDING -> if (state.mode == Mode.LIVE) R.string.live_recording else R.string.recording
                    Phase.TRANSCRIBING -> R.string.transcribing
                    Phase.RESULT -> R.string.result
                    Phase.ERROR -> R.string.error
                }), Modifier.testTag("status"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val recording = state.phase == Phase.RECORDING
                val processing = state.phase in setOf(Phase.CONNECTING, Phase.TRANSCRIBING, Phase.FINALIZING)
                CurrentControl(stringResource(if (recording) (if (state.mode == Mode.LIVE) R.string.stop_live else R.string.stop) else if (processing) R.string.current_processing else R.string.record),
                    if (recording) "stop" else if (processing) "processing" else "record",
                    when { recording -> CurrentVisual.RECORDING; processing -> CurrentVisual.PROCESSING; state.problem != null -> CurrentVisual.ERROR; else -> CurrentVisual.IDLE },
                    if (recording) true else !processing && state.validKey && !state.keyBusy,
                    if (recording) onStop else onRecord)
                if (recording) Text(state.elapsed, Modifier.testTag("timer"), style = MaterialTheme.typography.titleLarge)
                if (state.audioBusy || state.canRetry) TextButton(onCancel, Modifier.testTag("cancel")) {
                    Text(stringResource(if (state.mode == Mode.LIVE) R.string.cancel_live else R.string.cancel))
                }
                if (!state.validKey && !state.audioBusy) TextButton({ settingsOpen = true }, Modifier.testTag("add_key")) { Text(stringResource(R.string.current_add_key)) }
                if (state.mode == Mode.LIVE && state.audioBusy) Text(stringResource(when (state.phase) {
                    Phase.CONNECTING -> R.string.live_connect_status; Phase.RECORDING -> R.string.live_stream_status; else -> R.string.live_finish_status
                }), Modifier.testTag("live_status"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.safetyStopped) Text(stringResource(R.string.safety_stopped), Modifier.testTag("safety_stopped"))
                state.problem?.let { problem ->
                    Text(stringResource(when (problem) {
                        Problem.KEY -> R.string.key_error; Problem.STORAGE -> R.string.storage_error; Problem.PERMISSION -> R.string.permission_error
                        Problem.RECORDING -> R.string.record_error; Problem.NETWORK -> if (state.mode == Mode.LIVE) R.string.live_network_error else R.string.network_error
                        Problem.AUTH -> R.string.auth_error; Problem.QUOTA -> R.string.quota_error; Problem.SERVER -> R.string.server_error; Problem.RESPONSE -> R.string.response_error
                    }), Modifier.testTag("problem"), color = MaterialTheme.colorScheme.error)
                    if (problem == Problem.PERMISSION) TextButton(onSettings, Modifier.testTag("settings")) { Text(stringResource(R.string.settings)) }
                    if (problem in setOf(Problem.KEY, Problem.STORAGE, Problem.AUTH)) TextButton({ settingsOpen = true }) { Text(stringResource(R.string.current_settings)) }
                }
                if (state.canRetry) Button(onRetry, Modifier.testTag("retry"), enabled = state.validKey && !state.keyBusy) { Text(stringResource(R.string.retry)) }
                if (state.text.isNotBlank() || state.phase == Phase.RESULT) {
                    OutlinedTextField(state.text, onEdit, Modifier.fillMaxWidth().testTag("transcript"), label = { Text(stringResource(R.string.transcript)) },
                        minLines = 2, readOnly = state.busy, shape = RoundedCornerShape(24.dp))
                    if (state.mode == Mode.LIVE && (state.livePartial || state.completedSegments > 0)) Text(
                        if (state.livePartial) stringResource(R.string.partial_text) else stringResource(R.string.final_segments, state.completedSegments),
                        Modifier.testTag("live_text_status"), style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onCopy, Modifier.weight(1f).testTag("copy"), enabled = !state.busy && state.text.isNotBlank()) { Text(stringResource(R.string.copy)) }
                        OutlinedButton(onClear, Modifier.weight(1f).testTag("clear"), enabled = !state.busy && state.text.isNotEmpty()) { Text(stringResource(R.string.clear)) }
                    }
                }
                Text(stringResource(R.string.current_tap_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                TextButton(onFloatingSetup, Modifier.testTag("floating_setup"), enabled = !state.audioBusy) { Text(stringResource(R.string.floating_setup)) }
            }
        }
    }
}
