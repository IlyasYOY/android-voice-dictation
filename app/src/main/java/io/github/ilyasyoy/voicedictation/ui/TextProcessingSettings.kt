package io.github.ilyasyoy.voicedictation.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.ilyasyoy.voicedictation.R
import io.github.ilyasyoy.voicedictation.data.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class TextProcessingSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = TextProcessingPreferences(application)
    private val mutable = MutableStateFlow(runCatching { preferences.read() }.getOrNull())
    val state = mutable.asStateFlow()
    fun save(settings: TextProcessingSettings): Boolean = runCatching {
        preferences.save(settings); mutable.value = settings
    }.isSuccess
}

@Composable
internal fun TextProcessingSettingsCard(settings: TextProcessingSettings?, enabled: Boolean,
    onSave: (TextProcessingSettings) -> Boolean) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.text_processing_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.text_processing_notice), style = MaterialTheme.typography.bodySmall)
            if (settings == null) Text(stringResource(R.string.text_processing_settings_error), color = MaterialTheme.colorScheme.error)
            var active by rememberSaveable(settings) { mutableStateOf(settings?.enabled ?: false) }
            var model by rememberSaveable(settings) { mutableStateOf(settings?.model ?: "gpt-4.1-mini") }
            var prompt by rememberSaveable(settings) { mutableStateOf(settings?.prompt ?: TextProcessingSettings.DEFAULT_PROMPT) }
            var failed by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.text_processing_enable), Modifier.weight(1f))
                val label = stringResource(R.string.text_processing_title)
                Switch(active, { active = it; failed = false }, enabled = enabled,
                    modifier = Modifier.testTag("processing_enabled").semantics { contentDescription = label })
            }
            ProcessingForm(model, { model = it; failed = false }, prompt, { prompt = it; failed = false }, enabled)
            val candidate = TextProcessingSettings(active, model.trim(), prompt)
            Button({ failed = !onSave(candidate) }, enabled = enabled && candidate.valid && candidate != settings,
                modifier = Modifier.fillMaxWidth().testTag("save_processing_settings")) {
                Text(stringResource(R.string.text_processing_save))
            }
            if (failed) Text(stringResource(R.string.text_processing_settings_error),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
internal fun ProcessingForm(model: String, onModel: (String) -> Unit, prompt: String, onPrompt: (String) -> Unit, enabled: Boolean) {
    var menu by remember { mutableStateOf(false) }
    Box {
        OutlinedTextField(model, onModel, Modifier.fillMaxWidth().testTag("processing_model"),
            label = { Text(stringResource(R.string.text_processing_model)) }, singleLine = true, enabled = enabled)
    }
    TextButton({ menu = true }, enabled = enabled, modifier = Modifier.testTag("processing_models")) {
        Text(stringResource(R.string.text_processing_choose_model))
        DropdownMenu(menu, { menu = false }) {
            TextProcessingSettings.suggestedModels.forEach { value ->
                DropdownMenuItem(text = { Text(value) }, onClick = { onModel(value); menu = false })
            }
        }
    }
    OutlinedTextField(prompt, onPrompt, Modifier.fillMaxWidth().testTag("processing_prompt"),
        label = { Text(stringResource(R.string.text_processing_prompt)) }, minLines = 3, maxLines = 7, enabled = enabled,
        supportingText = { Text(stringResource(R.string.text_processing_prompt_hint)) }, isError = prompt.length > 8000)
}

@Composable
internal fun RepeatProcessingDialog(initial: TextProcessingSettings, onDismiss: () -> Unit,
    onProcess: (TextProcessingSettings) -> Unit, onDraftChange: (TextProcessingSettings) -> Unit = {}) {
    var model by rememberSaveable { mutableStateOf(initial.model) }
    var prompt by rememberSaveable { mutableStateOf(initial.prompt) }
    val settings = TextProcessingSettings(true, model.trim(), prompt)
    HistorySafeDialog(onDismiss, title = stringResource(R.string.text_processing_repeat), content = {
        Text(stringResource(R.string.text_processing_once), style = MaterialTheme.typography.bodySmall)
        ProcessingForm(model, { model = it; onDraftChange(TextProcessingSettings(true, model, prompt)) },
            prompt, { prompt = it; onDraftChange(TextProcessingSettings(true, model, prompt)) }, true)
        Text(stringResource(R.string.text_processing_notice), style = MaterialTheme.typography.bodySmall)
    }, buttons = {
        TextButton(onDismiss) { Text(stringResource(android.R.string.cancel)) }
        Button({ onProcess(settings) }, enabled = settings.valid, modifier = Modifier.testTag("confirm_processing")) {
            Text(stringResource(R.string.text_processing_run))
        }
    })
}
