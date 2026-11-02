package io.github.ilyasyoy.voicedictation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.ilyasyoy.voicedictation.R
import io.github.ilyasyoy.voicedictation.onboarding.*
import io.github.ilyasyoy.voicedictation.session.DictationState
import io.github.ilyasyoy.voicedictation.session.Problem

@Composable
internal fun SetupScreen(ui: SetupUi, state: DictationState, draft: SetupKeyDraft, grants: SetupGrants,
    pasteProblem: Boolean, navigationProblem: Boolean, microphoneSettings: Boolean, notificationSettings: Boolean,
    savedCount: Int, onKey: (String) -> Unit, onPaste: () -> Unit, onBrowser: () -> Unit,
    onPrimary: () -> Unit, onEnableFloating: () -> Unit, onAppSettings: () -> Unit,
    onDefer: () -> Unit, onHistory: () -> Unit) {
    val step = ui.step ?: return
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var disclosure by rememberSaveable { mutableStateOf(false) }
    val blocked = ui.pending != null || ui.navigationPending || state.keyBusy
    val title = when (step) {
        SetupStep.LOADING -> R.string.setup_title
        SetupStep.KEY -> R.string.api_key
        SetupStep.MICROPHONE -> R.string.setup_microphone
        SetupStep.FLOATING -> R.string.setup_keyboard_button
        SetupStep.NOTIFICATIONS -> R.string.setup_notifications
        SetupStep.BACKGROUND -> R.string.setup_background
    }
    val detail = when (step) {
        SetupStep.KEY -> R.string.setup_key_detail
        SetupStep.MICROPHONE -> R.string.setup_microphone_detail
        SetupStep.FLOATING -> R.string.setup_floating_detail
        SetupStep.NOTIFICATIONS -> R.string.setup_notifications_detail
        SetupStep.BACKGROUND -> R.string.setup_background_detail
        SetupStep.LOADING -> null
    }
    val primary = when (step) {
        SetupStep.KEY -> R.string.setup_save_continue
        SetupStep.MICROPHONE -> if (microphoneSettings) R.string.setup_app_settings else R.string.floating_grant_mic
        SetupStep.FLOATING -> if (grants.floatingWanted) R.string.setup_accessibility else R.string.floating_enable
        SetupStep.NOTIFICATIONS -> if (notificationSettings) R.string.setup_notification_settings else R.string.setup_allow_notifications
        SetupStep.BACKGROUND -> R.string.setup_done
        SetupStep.LOADING -> null
    }
    Surface(Modifier.fillMaxSize().testTag("onboarding"), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("setup_title"))
                Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        detail?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
                        if (step == SetupStep.LOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (step == SetupStep.KEY) {
                            val keyLabel = stringResource(R.string.api_key)
                            OutlinedTextField(draft.text, onKey, Modifier.fillMaxWidth().testTag("setup_key").semantics { contentDescription = keyLabel },
                                singleLine = true, visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !blocked && state.keyStoreKnown && !state.hasSavedKey)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onPaste, Modifier.testTag("setup_paste"), enabled = !blocked && state.keyStoreKnown && draft.text.isEmpty() && state.key.isEmpty() && !state.hasSavedKey) {
                                    Text(stringResource(R.string.setup_paste))
                                }
                                TextButton(onBrowser, Modifier.testTag("setup_browser"), enabled = !blocked) { Text(stringResource(R.string.setup_get_key)) }
                            }
                            if (state.keyBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            if (pasteProblem) Text(stringResource(R.string.setup_paste_empty), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("paste_problem"))
                        }
                        if (ui.storageProblem || navigationProblem || (step == SetupStep.KEY && (state.problem == Problem.STORAGE || !state.keyStoreKnown)))
                            Text(stringResource(R.string.error), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("setup_problem"))
                        if (step == SetupStep.BACKGROUND) TextButton(onAppSettings, Modifier.testTag("setup_background_settings"), enabled = !blocked) {
                            Text(stringResource(R.string.setup_app_settings))
                        }
                        primary?.let { label ->
                            Button({
                                focus.clearFocus(); keyboard?.hide()
                                if (step == SetupStep.FLOATING && !grants.floatingWanted) disclosure = true else onPrimary()
                            }, Modifier.fillMaxWidth().testTag("setup_primary"),
                                enabled = !blocked && (step != SetupStep.KEY || (draft.valid && state.keyStoreKnown && !state.hasSavedKey))) {
                                Text(stringResource(label))
                            }
                        }
                    }
                }
                TextButton({ focus.clearFocus(); keyboard?.hide(); disclosure = false; onDefer() }, Modifier.align(Alignment.CenterHorizontally).testTag("setup_defer")) {
                    Text(stringResource(R.string.setup_not_now))
                }
                if (savedCount > 0) TextButton(onHistory, Modifier.align(Alignment.CenterHorizontally).testTag("setup_history")) {
                    Text(stringResource(R.string.saved_recordings, savedCount))
                }
            }
        }
    }
    if (disclosure) AlertDialog(onDismissRequest = { disclosure = false },
        title = { Text(stringResource(R.string.floating_service_name)) },
        text = { Text(stringResource(R.string.floating_disclosure), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton({ disclosure = false; onEnableFloating() }, Modifier.testTag("setup_agree")) { Text(stringResource(R.string.floating_agree)) } },
        dismissButton = { TextButton({ disclosure = false }) { Text(stringResource(android.R.string.cancel)) } })
}
