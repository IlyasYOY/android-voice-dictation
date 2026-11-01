package io.github.ilyasyoy.voicedictation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.ilyasyoy.voicedictation.R
import io.github.ilyasyoy.voicedictation.data.HistoryCapacity

@Composable
internal fun HistoryCapacityNotice(capacity: HistoryCapacity?, onHistory: (() -> Unit)? = null, onLimit: () -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().testTag("history_capacity_notice")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.history_full), style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (capacity != null) Text(stringResource(R.string.history_places_used, capacity.used, capacity.limit), style = MaterialTheme.typography.labelMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (onHistory != null) TextButton(onHistory, Modifier.weight(1f).testTag("capacity_open_history")) {
                    Text(stringResource(R.string.capacity_history))
                }
                TextButton(onLimit, Modifier.weight(1f).testTag("capacity_open_limit")) { Text(stringResource(R.string.capacity_limit)) }
            }
        }
    }
}

@Composable
internal fun HistoryLimitCard(capacity: HistoryCapacity, onSave: (Int) -> Boolean) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.history_limit_title), style = MaterialTheme.typography.titleMedium)
            HistoryLimitEditor(capacity, onSave)
        }
    }
}

@Composable
internal fun HistoryLimitDialog(capacity: HistoryCapacity, onSave: (Int) -> Boolean, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.history_limit_title)) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            HistoryLimitEditor(capacity, onSave, onDismiss)
        } }, confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
private fun HistoryLimitEditor(capacity: HistoryCapacity, onSave: (Int) -> Boolean, onSaved: () -> Unit = {}) {
    var value by rememberSaveable(capacity.limit, capacity.readable) { mutableStateOf(if (capacity.readable) capacity.limit.toString() else "") }
    var touched by rememberSaveable { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf(false) }
    val number = HistoryCapacity.parse(value)
    val invalid = touched && number == null
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.history_places_used, capacity.used, capacity.limit), style = MaterialTheme.typography.labelMedium)
        OutlinedTextField(value, { value = it; touched = true; failed = false },
            Modifier.fillMaxWidth().testTag("history_limit_input"), singleLine = true,
            label = { Text(stringResource(R.string.history_limit_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = invalid || failed || !capacity.readable)
        if (invalid) Text(stringResource(R.string.history_limit_invalid), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("history_limit_invalid"))
        else if (failed || !capacity.readable) Text(stringResource(R.string.error), color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("history_limit_problem").semantics { liveRegion = LiveRegionMode.Polite })
        Text(stringResource(R.string.history_limit_detail), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button({
            val parsed = HistoryCapacity.parse(value)
            if (parsed != null) {
                if (onSave(parsed)) { focus.clearFocus(); keyboard?.hide(); touched = false; failed = false; onSaved() }
                else failed = true
            }
        }, Modifier.testTag("save_history_limit"), enabled = number != null && (!capacity.readable || number != capacity.limit || failed)) {
            Text(stringResource(R.string.history_limit_save))
        }
    }
}
