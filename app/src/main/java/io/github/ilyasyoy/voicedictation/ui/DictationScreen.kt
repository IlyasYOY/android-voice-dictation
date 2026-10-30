package io.github.ilyasyoy.voicedictation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.ilyasyoy.voicedictation.R

@Composable
fun DictationScreen(
    state: DictationState,
    onKey: (String) -> Unit,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onEdit: (String) -> Unit,
    onCopy: () -> Unit,
    onClear: () -> Unit,
    onForget: () -> Unit,
    onSettings: () -> Unit,
    onMode: (Mode) -> Unit,
    onSave: () -> Unit,
    onFloatingSetup: () -> Unit = {},
    evidenceNotice: String? = null,
) {
    Scaffold(topBar = {
        if (evidenceNotice != null) Surface(color = MaterialTheme.colorScheme.errorContainer) {
            Text(evidenceNotice, Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
                color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelSmall)
        }
    }) { insets ->
        Box(Modifier.fillMaxSize().padding(insets).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.eyebrow), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.title), style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold)
                OutlinedButton(onFloatingSetup, Modifier.fillMaxWidth().testTag("floating_setup"), enabled = !state.audioBusy) { Text(stringResource(R.string.floating_setup)) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(state.mode == Mode.RECORD, { onMode(Mode.RECORD) }, label = { Text(stringResource(R.string.mode_record)) },
                        enabled = !state.busy && !state.canRetry, modifier = Modifier.testTag("mode_record"))
                    FilterChip(state.mode == Mode.LIVE, { onMode(Mode.LIVE) }, label = { Text(stringResource(R.string.mode_live)) },
                        enabled = !state.busy && !state.canRetry, modifier = Modifier.testTag("mode_live"))
                }
                Card(shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(when (state.phase) {
                            Phase.IDLE -> R.string.idle
                            Phase.CONNECTING -> R.string.connecting
                            Phase.FINALIZING -> R.string.finalizing
                            Phase.RECORDING -> if (state.mode == Mode.LIVE) R.string.live_recording else R.string.recording
                            Phase.TRANSCRIBING -> R.string.transcribing
                            Phase.RESULT -> R.string.result
                            Phase.ERROR -> R.string.error
                        }), Modifier.testTag("status"), style = MaterialTheme.typography.titleLarge)
                        if (state.phase == Phase.RECORDING) {
                            Text(state.elapsed, Modifier.testTag("timer"))
                            Button(onStop, Modifier.fillMaxWidth().testTag("stop")) { Text(stringResource(if (state.mode == Mode.LIVE) R.string.stop_live else R.string.stop)) }
                        } else if (state.phase in setOf(Phase.TRANSCRIBING, Phase.CONNECTING, Phase.FINALIZING)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            Button(onRecord, Modifier.fillMaxWidth().testTag("record"), enabled = state.validKey && !state.keyBusy) {
                                Text(stringResource(R.string.record))
                            }
                        }
                        if (state.audioBusy || state.canRetry) OutlinedButton(onCancel, Modifier.fillMaxWidth().testTag("cancel")) {
                            Text(stringResource(if (state.mode == Mode.LIVE) R.string.cancel_live else R.string.cancel))
                        }
                        if (state.mode == Mode.LIVE && state.audioBusy) Text(stringResource(when (state.phase) {
                            Phase.CONNECTING -> R.string.live_connect_status
                            Phase.RECORDING -> R.string.live_stream_status
                            else -> R.string.live_finish_status
                        }), Modifier.testTag("live_status"), style = MaterialTheme.typography.bodySmall)
                        if (state.safetyStopped) Text(stringResource(R.string.safety_stopped), Modifier.testTag("safety_stopped"))
                        state.problem?.let { problem ->
                            Text(stringResource(when (problem) {
                                Problem.KEY -> R.string.key_error
                                Problem.STORAGE -> R.string.storage_error
                                Problem.PERMISSION -> R.string.permission_error
                                Problem.RECORDING -> R.string.record_error
                                Problem.NETWORK -> if (state.mode == Mode.LIVE) R.string.live_network_error else R.string.network_error
                                Problem.AUTH -> R.string.auth_error
                                Problem.QUOTA -> R.string.quota_error
                                Problem.SERVER -> R.string.server_error
                                Problem.RESPONSE -> R.string.response_error
                            }), Modifier.testTag("problem"), color = MaterialTheme.colorScheme.error)
                            if (problem == Problem.PERMISSION) TextButton(onSettings, Modifier.testTag("settings")) {
                                Text(stringResource(R.string.settings))
                            }
                        }
                        if (state.canRetry) Button(onRetry, Modifier.testTag("retry"), enabled = state.validKey && !state.keyBusy) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                OutlinedTextField(state.text, onEdit, Modifier.fillMaxWidth().testTag("transcript"),
                    label = { Text(stringResource(R.string.transcript)) }, minLines = 3,
                    readOnly = state.busy, shape = RoundedCornerShape(16.dp))
                if (state.mode == Mode.LIVE && (state.livePartial || state.completedSegments > 0)) Text(
                    if (state.livePartial) stringResource(R.string.partial_text) else stringResource(R.string.final_segments, state.completedSegments),
                    Modifier.testTag("live_text_status"), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onCopy, Modifier.weight(1f).testTag("copy"), enabled = state.text.isNotBlank() && !state.busy) {
                        Text(stringResource(R.string.copy))
                    }
                    OutlinedButton(onClear, Modifier.weight(1f).testTag("clear"), enabled = state.text.isNotEmpty() && !state.busy) {
                        Text(stringResource(R.string.clear))
                    }
                }
                HorizontalDivider()
                Text(stringResource(R.string.setup), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.cost_notice), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(state.key, onKey, Modifier.fillMaxWidth().testTag("api_key"),
                    label = { Text(stringResource(R.string.api_key)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    enabled = !state.busy)
                if (state.keyBusy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("key_progress"))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onSave, Modifier.testTag("save_key"), enabled = state.validKey && !state.keySaved && !state.busy) {
                        Text(stringResource(R.string.save_key))
                    }
                }
                if (state.keySaved) Text(stringResource(R.string.key_saved), Modifier.testTag("key_saved"), style = MaterialTheme.typography.bodySmall)
                else if (state.hasSavedKey) Text(stringResource(R.string.key_changed), style = MaterialTheme.typography.bodySmall)
                if (state.key.isNotEmpty() || state.hasSavedKey || state.problem == Problem.STORAGE) TextButton(onForget, Modifier.testTag("forget"), enabled = !state.busy) {
                    Text(stringResource(R.string.forget))
                }
                Text(stringResource(R.string.privacy), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
