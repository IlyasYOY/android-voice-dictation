package io.github.ilyasyoy.voicedictation.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.ilyasyoy.voicedictation.R
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.session.DictationSessions
import io.github.ilyasyoy.voicedictation.diagnostics.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class TextProcessingSettingsViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        // In-process local transport only, never configurable through intents or settings.
        internal var testModelList: (suspend () -> List<String>)? = null
    }
    private val preferences = TextProcessingPreferences(application)
    private val mutable = MutableStateFlow(runCatching { preferences.read() }.getOrNull())
    val state = mutable.asStateFlow()
    private val catalog = TextModelList(viewModelScope) {
        try {
            val local = testModelList
            if (local != null) local() else DictationSessions.textModels(application)
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) { Diagnostics.failure(DiagnosticArea.MODELS, DiagnosticAction.CONNECT, error); throw error }
    }
    val models = catalog.state
    fun refreshModels() = catalog.refresh()
    fun save(settings: TextProcessingSettings): Boolean = runCatching {
        preferences.save(settings); mutable.value = settings
    }.isSuccess
}

@Composable
internal fun TextProcessingSettingsCard(settings: TextProcessingSettings?, enabled: Boolean,
    onSave: (TextProcessingSettings) -> Boolean, catalog: TextModelListState = TextModelListState(),
    onRefresh: (() -> Unit)? = null) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.text_processing_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.text_processing_notice), style = MaterialTheme.typography.bodySmall)
            if (settings == null) Text(stringResource(R.string.text_processing_settings_error), color = MaterialTheme.colorScheme.error)
            var active by rememberSaveable(settings) { mutableStateOf(settings?.enabled ?: false) }
            var model by rememberSaveable(settings) { mutableStateOf(settings?.model ?: TextModels.DEFAULT) }
            var prompt by rememberSaveable(settings) { mutableStateOf(settings?.prompt ?: TextProcessingSettings.DEFAULT_PROMPT) }
            var failed by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(R.string.text_processing_enable), Modifier.weight(1f))
                val label = stringResource(R.string.text_processing_title)
                Switch(active, { active = it; failed = false }, enabled = enabled,
                    modifier = Modifier.testTag("processing_enabled").semantics { contentDescription = label })
            }
            TextModelDropdown(model, { model = it; failed = false }, catalog.models, enabled)
            onRefresh?.let { refresh ->
                OutlinedButton(refresh, enabled = enabled && !catalog.loading,
                    modifier = Modifier.fillMaxWidth().testTag("refresh_processing_models")) {
                    Text(stringResource(R.string.text_models_refresh))
                }
                if (catalog.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("models_progress"))
                if (catalog.failed) Text(stringResource(R.string.text_models_failed),
                    Modifier.testTag("models_problem").semantics { liveRegion = LiveRegionMode.Polite },
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else if (catalog.updated) Text(stringResource(if (model in catalog.models) R.string.text_models_updated else R.string.text_models_missing),
                    Modifier.testTag("models_status").semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.text_models_notice), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(prompt, { prompt = it; failed = false }, Modifier.fillMaxWidth().testTag("processing_prompt"),
                label = { Text(stringResource(R.string.text_processing_prompt)) }, minLines = 3, maxLines = 7, enabled = enabled,
                supportingText = { Text(stringResource(R.string.text_processing_prompt_hint)) }, isError = prompt.length > 8000)
            val candidate = TextProcessingSettings(active, model, prompt)
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
private fun TextModelDropdown(model: String, onModel: (String) -> Unit, models: List<String>, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    var menuWidth by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val modelLabel = stringResource(R.string.text_processing_model)
    val expandedLabel = stringResource(if (expanded) R.string.history_drawer_expanded else R.string.history_drawer_collapsed)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(modelLabel, style = MaterialTheme.typography.labelMedium)
        Box(Modifier.onSizeChanged { menuWidth = it.width }) {
            OutlinedButton({ expanded = !expanded }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag("processing_model").semantics {
                    contentDescription = modelLabel + ": " + TextModels.label(model)
                    stateDescription = expandedLabel
                }) {
                Text(TextModels.label(model), Modifier.weight(1f))
                Text("⌄", Modifier.clearAndSetSemantics { })
            }
            DropdownMenu(expanded, { expanded = false }, Modifier.width(with(density) { menuWidth.toDp() }).heightIn(max = 360.dp)) {
                (listOf(model) + models).distinct().forEach { id ->
                    DropdownMenuItem(text = { Text(TextModels.label(id)) },
                        onClick = { onModel(id); expanded = false },
                        modifier = Modifier.testTag("processing_model_option_$id").semantics { selected = id == model })
                }
            }
        }
    }
}
