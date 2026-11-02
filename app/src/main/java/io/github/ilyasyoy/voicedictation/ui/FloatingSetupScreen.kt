package io.github.ilyasyoy.voicedictation.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import io.github.ilyasyoy.voicedictation.R
import io.github.ilyasyoy.voicedictation.onboarding.*

@Composable
internal fun FloatingSetupScreen(grants: SetupGrants, progress: SetupProgress, blocked: Boolean,
    microphoneSettings: Boolean, notificationSettings: Boolean, problem: Boolean,
    onBack: () -> Unit, onEnable: () -> Unit, onPrimary: () -> Unit, onDisable: () -> Unit,
    onBackgroundSettings: () -> Unit, onBackgroundAcknowledged: () -> Unit, recovery: @Composable () -> Unit) {
    val step = FloatingSetupPolicy.step(grants, progress)
    val packageName = LocalContext.current.packageName
    fun tag(name: String) = Modifier.testTag("$packageName:id/$name")
    var more by remember { mutableStateOf(false) }
    var backgroundHelp by rememberSaveable { mutableStateOf(false) }
    var disclosure by rememberSaveable { mutableStateOf(false) }
    var enableConsent by rememberSaveable { mutableStateOf(false) }
    SetupLayout(stringResource(R.string.floating_button_title),
        Modifier.semantics { testTagsAsResourceId = true }.then(tag("floating_setup")),
        titleTag = "$packageName:id/floating_setup_title", onBack = onBack) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val ink = MaterialTheme.colorScheme.primary
            Surface(shape = RoundedCornerShape(28.dp, 36.dp, 30.dp, 40.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Canvas(Modifier.size(72.dp).padding(22.dp)) {
                    val x = size.width / 2
                    drawRoundRect(ink, Offset(x - size.width * .18f, 0f), androidx.compose.ui.geometry.Size(size.width * .36f, size.height * .6f),
                        androidx.compose.ui.geometry.CornerRadius(size.width * .2f))
                    drawLine(ink, Offset(x, size.height * .5f), Offset(x, size.height), 2.dp.toPx())
                    drawLine(ink, Offset(size.width * .25f, size.height), Offset(size.width * .75f, size.height), 2.dp.toPx())
                }
            }
            Text(stringResource(if (grants.floatingWanted) R.string.floating_on else R.string.floating_off),
                style = MaterialTheme.typography.titleMedium, modifier = tag("floating_setup_status"))
        }
        if (step != FloatingSetupStep.READY) SetupCard {
            val detail = when (step) {
                FloatingSetupStep.ENABLE, FloatingSetupStep.ACCESSIBILITY -> R.string.setup_floating_detail
                FloatingSetupStep.MICROPHONE -> R.string.setup_microphone_detail
                FloatingSetupStep.NOTIFICATIONS -> R.string.setup_notifications_detail
                FloatingSetupStep.BACKGROUND -> R.string.setup_background_detail
                else -> null
            }
            detail?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
            val label = when (step) {
                FloatingSetupStep.ENABLE -> R.string.floating_enable
                FloatingSetupStep.MICROPHONE -> if (microphoneSettings) R.string.setup_app_settings else R.string.floating_grant_mic
                FloatingSetupStep.ACCESSIBILITY -> R.string.setup_accessibility
                FloatingSetupStep.NOTIFICATIONS -> if (notificationSettings) R.string.setup_notification_settings else R.string.setup_allow_notifications
                FloatingSetupStep.BACKGROUND -> R.string.setup_done
                else -> R.string.setup_done
            }
            val id = when (step) {
                FloatingSetupStep.MICROPHONE -> "floating_mic"
                FloatingSetupStep.NOTIFICATIONS -> "floating_notifications"
                FloatingSetupStep.BACKGROUND -> "floating_background_done"
                else -> "floating_enable"
            }
            if (step == FloatingSetupStep.BACKGROUND) TextButton(onBackgroundSettings, tag("floating_background_settings"), enabled = !blocked) {
                Text(stringResource(R.string.setup_app_settings))
            }
            SetupPrimary(stringResource(label), {
                if (step == FloatingSetupStep.ENABLE) { enableConsent = true; disclosure = true } else onPrimary()
            }, tag(id), enabled = !blocked)
        }
        if (problem) Text(stringResource(R.string.error), color = MaterialTheme.colorScheme.error, modifier = tag("floating_setup_problem"))
        recovery()
        if (grants.floatingWanted || grants.accessibility) TextButton(onDisable, tag("floating_disable"), enabled = !blocked) {
            Text(stringResource(if (grants.floatingWanted) R.string.floating_turn_off else R.string.floating_revoke))
        }
        Box {
            TextButton({ more = true }, tag("floating_more"), enabled = !blocked) { Text(stringResource(R.string.setup_more)) }
            DropdownMenu(more, { more = false }, modifier = Modifier.semantics { testTagsAsResourceId = true }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.floating_about)) },
                    onClick = { more = false; enableConsent = false; disclosure = true }, modifier = tag("floating_about"))
                DropdownMenuItem(text = { Text(stringResource(R.string.setup_background)) },
                    onClick = { more = false; backgroundHelp = true }, modifier = tag("floating_background_help"))
            }
        }
    }
    if (disclosure) AlertDialog(onDismissRequest = { disclosure = false }, modifier = Modifier.semantics { testTagsAsResourceId = true },
        title = { Text(stringResource(R.string.floating_button_title)) },
        text = { Text(stringResource(R.string.floating_disclosure), Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton({ disclosure = false; if (enableConsent) onEnable() }, tag("floating_consent")) {
            Text(stringResource(if (enableConsent) R.string.floating_agree else R.string.setup_done))
        } },
        dismissButton = { if (enableConsent) TextButton({ disclosure = false }) { Text(stringResource(android.R.string.cancel)) } })
    if (backgroundHelp) AlertDialog(onDismissRequest = { backgroundHelp = false }, modifier = Modifier.semantics { testTagsAsResourceId = true },
        title = { Text(stringResource(R.string.setup_background)) },
        text = { Text(stringResource(R.string.setup_background_detail)) },
        confirmButton = { TextButton(onBackgroundSettings, enabled = !blocked) { Text(stringResource(R.string.setup_app_settings)) } },
        dismissButton = { TextButton({ onBackgroundAcknowledged(); backgroundHelp = false }) { Text(stringResource(R.string.setup_done)) } })

}

