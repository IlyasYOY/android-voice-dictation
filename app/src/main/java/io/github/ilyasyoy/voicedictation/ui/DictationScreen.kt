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
    evidenceNotice: String? = null,
) {
    Scaffold { insets ->
        Box(Modifier.fillMaxSize().padding(insets).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.eyebrow), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.title), style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold)
                if (evidenceNotice != null) Text(evidenceNotice, color = MaterialTheme.colorScheme.error)
                Card(shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(when (state.phase) {
                            Phase.IDLE -> R.string.idle
                            Phase.RECORDING -> R.string.recording
                            Phase.TRANSCRIBING -> R.string.transcribing
                            Phase.RESULT -> R.string.result
                            Phase.ERROR -> R.string.error
                        }), Modifier.testTag("status"), style = MaterialTheme.typography.titleLarge)
                        if (state.phase == Phase.RECORDING) {
                            Text(stringResource(R.string.timer, state.seconds), Modifier.testTag("timer"))
                            Button(onStop, Modifier.fillMaxWidth().testTag("stop")) { Text(stringResource(R.string.stop)) }
                        } else if (state.phase == Phase.TRANSCRIBING) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        } else {
                            Button(onRecord, Modifier.fillMaxWidth().testTag("record"), enabled = state.validKey) {
                                Text(stringResource(R.string.record))
                            }
                        }
                        if (state.busy || state.canRetry) OutlinedButton(onCancel, Modifier.fillMaxWidth().testTag("cancel")) {
                            Text(stringResource(R.string.cancel))
                        }
                        state.problem?.let { problem ->
                            Text(stringResource(when (problem) {
                                Problem.KEY -> R.string.key_error
                                Problem.PERMISSION -> R.string.permission_error
                                Problem.RECORDING -> R.string.record_error
                                Problem.NETWORK -> R.string.network_error
                                Problem.AUTH -> R.string.auth_error
                                Problem.QUOTA -> R.string.quota_error
                                Problem.SERVER -> R.string.server_error
                                Problem.RESPONSE -> R.string.response_error
                            }), Modifier.testTag("problem"), color = MaterialTheme.colorScheme.error)
                            if (problem == Problem.PERMISSION) TextButton(onSettings, Modifier.testTag("settings")) {
                                Text(stringResource(R.string.settings))
                            }
                        }
                        if (state.canRetry) Button(onRetry, Modifier.testTag("retry"), enabled = state.validKey) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
                OutlinedTextField(state.text, onEdit, Modifier.fillMaxWidth().testTag("transcript"),
                    label = { Text(stringResource(R.string.transcript)) }, minLines = 3,
                    enabled = !state.busy, shape = RoundedCornerShape(16.dp))
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
                if (state.key.isNotEmpty()) TextButton(onForget, Modifier.testTag("forget"), enabled = !state.busy) {
                    Text(stringResource(R.string.forget))
                }
                Text(stringResource(R.string.privacy), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
