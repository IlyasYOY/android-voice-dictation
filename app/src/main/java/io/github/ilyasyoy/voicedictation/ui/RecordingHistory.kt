package io.github.ilyasyoy.voicedictation.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ilyasyoy.voicedictation.R
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.playback.*
import io.github.ilyasyoy.voicedictation.session.*
import java.text.DateFormat
import java.util.Date
import java.io.File

internal data class HistoryDraft(val text: String, val sourceId: String?, val visible: Boolean = text.isNotBlank()) {
    override fun toString() = "HistoryDraft(textLength=${text.length}, sourceId=$sourceId, visible=$visible)"
}

/** Standalone floating recovery still uses the same host and message content. */
@Composable
internal fun RecordingHistorySheet(onDismiss: () -> Unit, onRecordsChanged: () -> Unit = {},
    draft: HistoryDraft? = null, draftProblem: Boolean = false, onDraftEdit: ((String) -> Unit)? = null, onDraftClear: (() -> Unit)? = null) {
    val controller: HistoryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val recovery by RecordingRecovery.processOwned.state.collectAsStateWithLifecycle()
    HistoryPlaybackLifecycle(controller)
    HistoryPresentation(false, true, onDismiss, recovery, controller, rememberHistoryUiState(),
        onRecordsChanged, draft, draftProblem, onDraftEdit, onDraftClear)
}

@Composable
internal fun HistoryPlaybackLifecycle(controller: HistoryViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = LocalActivity.current
    DisposableEffect(lifecycle, controller, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) controller.background()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryPresentation(wide: Boolean, open: Boolean, onDismiss: () -> Unit,
    recovery: RecordingRecoveryState, controller: HistoryViewModel, ui: HistoryUiState,
    onRecordsChanged: () -> Unit = {}, draft: HistoryDraft? = null, draftProblem: Boolean = false,
    onDraftEdit: ((String) -> Unit)? = null, onDraftClear: (() -> Unit)? = null,
    sheetRegion: HistoryRegion? = null, windowWidth: Float = 0f, windowHeight: Float = 0f,
    onOpenForDialog: () -> Unit = {}, embedded: Boolean = false) {
    val pendingDialog = recovery.records.any { it.id == ui.deleteId || it.id == ui.processingId } || ui.limitOpen
    val visible = wide || LocalHistoryContentVisible.current || pendingDialog
    LaunchedEffect(wide, pendingDialog, open) { if (!wide && pendingDialog && !open) onOpenForDialog() }
    val playing by controller.playback.state.collectAsStateWithLifecycle()
    val repository = controller.repository
    val processingFailure by controller.processingFailure.collectAsStateWithLifecycle()
    var storedDraft by remember { mutableStateOf<HistoryDraft?>(null) }
    LaunchedEffect(recovery.records, draft == null) {
        if (draft == null) storedDraft = runCatching {
            repository?.savedDraft(RecordingTextSlot.MAIN)?.let { HistoryDraft(it.text, it.sourceId) }
        }.getOrNull()
    }
    val dismiss = { controller.background(); ui.closeTransient(); onDismiss() }
    val content: @Composable () -> Unit = {
        if (visible) {
        HistoryPanel(ui, onWordsSelected = { controller.background(); ui.menuId = null },
            modifier = Modifier.fillMaxHeight(),
            words = { VocabularyPanel(ui, it) }, history = { modifier -> Box(modifier) {
        RecordingHistory(recovery, playing,
            audioAvailable = { id -> repository?.audio(id)?.let { it.isFile && it.length() > 0 } == true },
            onPlay = controller::play, onRetry = controller::retry,
            onDelete = { id -> controller.delete(id); onRecordsChanged() }, onCopy = controller::copy,
            onClose = dismiss, draft = draft ?: storedDraft, draftProblem = draftProblem,
            onDraftEdit = onDraftEdit, onDraftClear = onDraftClear, onHistoryLimit = { ui.limitOpen = true },
            ui = ui, dismissible = !wide,
            onProcess = { id -> recovery.records.firstOrNull { it.id == id }?.let {
                ui.processingDraft = controller.processingDefaults(it); ui.processingId = id
            } }, onCancelProcessing = controller::cancelProcessing,
            processingFailure = processingFailure,
            waveform = { id, position, trackModifier -> LoadedAudioTrack(id, repository?.audio(id), position, trackModifier) })
        } })
        } else Box(Modifier.fillMaxSize()) // Keep sheet geometry, without decoding hidden history waveforms.
    }
    if (embedded) {
        if (wide) HistoryPanelHost(true, true, dismiss, content = content) else content()
    } else HistoryPanelHost(wide, open || pendingDialog, dismiss, content)
    if ((wide || open || pendingDialog) && ui.limitOpen) HistoryLimitDialog(recovery.capacity,
        onSave = { limit -> runCatching { checkNotNull(repository).setHistoryLimit(limit) }.isSuccess },
        onDismiss = { ui.limitOpen = false })
    recovery.records.firstOrNull { it.id == ui.processingId }?.let { record ->
        androidx.compose.runtime.key(record.id) {
            RepeatProcessingDialog(ui.processingDraft, onDismiss = { ui.processingId = null },
                onProcess = { controller.process(record.id, it); ui.processingId = null },
                onDraftChange = { ui.processingDraft = it })
        }
    }
}

/** Stateless host shared with headless component verification; identical bounds/IME policy for both tabs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryPanelHost(wide: Boolean, open: Boolean, onDismiss: () -> Unit,
    content: @Composable () -> Unit) {
    if (wide) Surface(Modifier.fillMaxSize().testTag("history_pane"), color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(28.dp)) { content() }
    else {
        val state = rememberStandardBottomSheetState(initialValue = if (open) SheetValue.Expanded else SheetValue.PartiallyExpanded)
        HistoryDrawer(state, open, true, 0, {}, {}, onDismiss, Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            main = { Box(Modifier.fillMaxSize()) }, content = content)
    }
}

@Composable
internal fun RecordingHistory(state: RecordingRecoveryState, playback: PlaybackState,
    audioAvailable: (String) -> Boolean, onPlay: (String) -> Unit, onRetry: (String) -> Unit,
    onDelete: (String) -> Unit, onCopy: (String) -> Unit, onClose: () -> Unit,
    draft: HistoryDraft? = null, draftProblem: Boolean = false, onDraftEdit: ((String) -> Unit)? = null, onDraftClear: (() -> Unit)? = null,
    onHistoryLimit: () -> Unit = {},
    ui: HistoryUiState = rememberHistoryUiState(), dismissible: Boolean = true,
    waveform: @Composable (String, Float, Modifier) -> Unit = { id, position, modifier ->
        AudioTrack(null, position, modifier.testTag("history_waveform_pending_$id"))
    }, onProcess: ((String) -> Unit)? = null, onCancelProcessing: (String) -> Unit = {}, processingFailure: String? = null) {
    val deleteId = ui.deleteId
    val deleteRecord = state.records.firstOrNull { it.id == deleteId }
    if (deleteRecord != null) HistorySafeDialog(onDismiss = { ui.deleteId = null },
        content = { Text(stringResource(R.string.saved_delete_confirm)) }, buttons = {
            TextButton({ ui.deleteId = null }) { Text(stringResource(android.R.string.cancel)) }
            TextButton({ onDelete(deleteRecord.id); ui.deleteId = null },
                enabled = !state.retrying && deleteRecord.id !in state.ownedIds,
                modifier = Modifier.testTag("history_confirm_delete")) { Text(stringResource(R.string.saved_delete)) }
        })
    val currentDraft = draft?.takeIf { it.visible }
    val dismissLabel = stringResource(R.string.history_close)
    val historyLabel = stringResource(R.string.history_title)
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 12.dp)
        .testTag("history_content").semantics {
            paneTitle = historyLabel
            if (dismissible) dismiss(dismissLabel) { onClose(); true }
        }) {
        LazyColumn(Modifier.fillMaxWidth().testTag("history_list"), state = ui.list,
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.capacity.full) item("capacity") {
                HistoryCapacityNotice(state.capacity, onLimit = onHistoryLimit)
            }
            if (state.storageProblem || draftProblem) item("storage") {
                Text(stringResource(R.string.error), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp).testTag("history_problem").semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (currentDraft != null && currentDraft.sourceId == null) item("current-text") {
                DraftMessage(currentDraft, onDraftEdit, onDraftClear, onCopy, ui)
            }
            if (state.records.isEmpty() && (currentDraft == null || currentDraft.sourceId != null)) item("empty") {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.saved_empty), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.history_empty_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.records.forEachIndexed { index, record ->
                val day = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(record.createdAt))
                val previousDay = state.records.getOrNull(index - 1)?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.createdAt)) }
                if (day != previousDay) item("day-${record.id}") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(day, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item(record.id) {
                    val rowDraft = currentDraft?.takeIf { it.sourceId == record.id }
                    val available = audioAvailable(record.id)
                    HistoryMessage(record, state, playback, available,
                        rowDraft, if (rowDraft != null) onDraftEdit else null, if (rowDraft != null) onDraftClear else null,
                        onPlay, onRetry, { ui.deleteId = record.id }, onCopy, waveform, ui,
                        onProcess, onCancelProcessing, processingFailure == record.id)
                }
            }
        }
    }
}

@Composable
private fun HistoryMessage(record: SavedRecording, state: RecordingRecoveryState, playback: PlaybackState,
    available: Boolean, draft: HistoryDraft?, onEdit: ((String) -> Unit)?, onClear: (() -> Unit)?,
    onPlay: (String) -> Unit, onRetry: (String) -> Unit, onDelete: () -> Unit, onCopy: (String) -> Unit,
    waveform: @Composable (String, Float, Modifier) -> Unit, ui: HistoryUiState,
    onProcess: ((String) -> Unit)?, onCancelProcessing: (String) -> Unit, processingFailure: Boolean) {
    val expanded = record.id in ui.expanded
    val menu = ui.menuId == record.id
    val text = draft?.text ?: state.receivedTexts[record.id] ?: record.transcript
    val owned = record.id in state.ownedIds
    val active = playback.id == record.id
    val busy = record.status in setOf(SavedRecordingStatus.CAPTURING, SavedRecordingStatus.UPLOADING)
    val timestamp = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(record.createdAt))
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(record.createdAt))
    val moreLabel = stringResource(R.string.history_more)
    val largeText = LocalDensity.current.fontScale > 1.3f
    val canRetry = record.kind == SavedRecordingKind.RECORD && available && record.status in
        setOf(SavedRecordingStatus.PENDING, SavedRecordingStatus.FAILED, SavedRecordingStatus.INTERRUPTED)
    val playLabel = stringResource(if (active && playback.loading) R.string.history_loading else if (active && playback.playing) R.string.history_pause else R.string.history_play)
    val status = when (record.status) {
        SavedRecordingStatus.COMPLETE -> if (record.partial) R.string.partial_text else null
        SavedRecordingStatus.CAPTURING -> R.string.recording
        SavedRecordingStatus.UPLOADING -> R.string.transcribing
        SavedRecordingStatus.FAILED -> null
        SavedRecordingStatus.INTERRUPTED -> R.string.saved_interrupted
        SavedRecordingStatus.UNAVAILABLE, SavedRecordingStatus.DELETING -> R.string.saved_unavailable
        SavedRecordingStatus.PENDING -> R.string.saved_pending
    }
    val busyLabel = stringResource(if (record.status == SavedRecordingStatus.CAPTURING) R.string.recording else R.string.transcribing)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 8.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(.96f).testTag("history_card_${record.id}").animateContentSize()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (record.kind == SavedRecordingKind.RECORD) {
                        FilledIconButton({ onPlay(record.id) },
                            enabled = available && !busy && state.records.none { it.status == SavedRecordingStatus.CAPTURING },
                            modifier = Modifier.size(48.dp).testTag("history_play_${record.id}")
                                .semantics { contentDescription = "$playLabel, $timestamp" }) {
                            TransportMark(active && playback.playing, active && playback.loading)
                        }
                        val trackModifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(28.dp)
                        val position = if (active && playback.duration > 0) playback.position.toFloat() / playback.duration else 0f
                        if (available && record.status != SavedRecordingStatus.CAPTURING) waveform(record.id, position, trackModifier)
                        else AudioTrack(null, position, trackModifier)
                    } else Text(stringResource(R.string.saved_text_only), Modifier.weight(1f).padding(8.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val hasText = text.isNotBlank() || draft != null
                    when {
                        canRetry -> RetryControl(record.id, record.status == SavedRecordingStatus.PENDING,
                            record.id in state.receivedTexts, enabled = !state.retrying && !owned,
                            onRetry = { onRetry(record.id) })
                        busy && record.kind == SavedRecordingKind.RECORD -> IconButton({}, enabled = false,
                            modifier = Modifier.size(48.dp).testTag("history_busy_${record.id}")
                                .semantics { contentDescription = busyLabel }) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                        hasText -> TranscriptToggle(expanded, { ui.toggle(record.id) }, "history_expand_${record.id}")
                        record.kind == SavedRecordingKind.RECORD -> Spacer(Modifier.size(48.dp))
                    }
                }
                if (record.kind == SavedRecordingKind.RECORD) {
                    Text(
                        if (!available) stringResource(R.string.history_missing_audio)
                        else if (active) "${clock(playback.position / 1000)} / ${clock(playback.duration / 1000)}" else clock(record.durationSeconds),
                        modifier = Modifier.padding(start = 56.dp, end = 56.dp).testTag("history_duration_${record.id}"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (expanded || (canRetry && text.isNotBlank())) {
                    if (record.processingStatus == TextProcessingStatus.NONE)
                        TranscriptBody(text, onEdit, { onCopy(text) }, "history_text_${record.id}", "history_copy_${record.id}")
                    else if (record.transcript.isNotBlank()) ProcessedTranscriptBody(record, onCopy)
                    else TranscriptBody(text, onEdit, { onCopy(text) }, "history_text_${record.id}", "history_copy_${record.id}")
                    if (record.transcript.isNotBlank() && record.status == SavedRecordingStatus.COMPLETE && onProcess != null) {
                        if (record.processingStatus == TextProcessingStatus.PROCESSING) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("history_processing_${record.id}"))
                            MessageNote(R.string.text_processing)
                            TextButton({ onCancelProcessing(record.id) }, Modifier.testTag("cancel_processing_${record.id}")) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        } else TextButton({ onProcess(record.id) }, enabled = !owned && !state.retrying,
                            modifier = Modifier.testTag("process_text_${record.id}")) { Text(stringResource(R.string.text_processing_repeat)) }
                    }
                }
                if (record.processingStatus in setOf(TextProcessingStatus.FAILED, TextProcessingStatus.INTERRUPTED) || processingFailure)
                    MessageNote(R.string.text_processing_failed, error = true)
                if (playback.failedId == record.id) MessageNote(R.string.history_playback_error, error = true)
                if (record.status == SavedRecordingStatus.INTERRUPTED && record.kind == SavedRecordingKind.RECORD) MessageNote(R.string.history_interrupted_audio)
                if (record.id in state.receivedTexts) MessageNote(R.string.saved_unsaved_text, error = true)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (status != null && largeText) Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        MessageStatus(status, record.status == SavedRecordingStatus.FAILED)
                        Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Box(Modifier.weight(1f).padding(start = 8.dp)) { if (status != null) MessageStatus(status, false) }
                        Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton({ ui.menuId = record.id }, Modifier.size(48.dp).testTag("history_menu_${record.id}")
                            .semantics { contentDescription = timestamp + ", " + moreLabel }) {
                            Text("⋮", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics { })
                        }
                        DropdownMenu(menu, { ui.menuId = null }) {
                            if (onClear != null) DropdownMenuItem(text = { Text(stringResource(R.string.history_clear_draft)) },
                                onClick = { ui.menuId = null; onClear() }, modifier = Modifier.testTag("clear"))
                            DropdownMenuItem(text = { Text(stringResource(R.string.history_delete)) }, enabled = !state.retrying && !owned && !busy,
                                onClick = { ui.menuId = null; onDelete() }, modifier = Modifier.testTag("history_delete_${record.id}"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessedTranscriptBody(record: SavedRecording, onCopy: (String) -> Unit) {
    var processed by rememberSaveable(record.id, record.processedText) { mutableStateOf(record.processedText.isNotBlank()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            val options = listOf(false to R.string.text_recognized, true to R.string.text_processed)
            options.forEach { (value, label) ->
                FilterChip(processed == value, { processed = value }, label = { Text(stringResource(label)) },
                    enabled = !value || record.processedText.isNotBlank(), modifier = Modifier.weight(1f)
                        .testTag("text_version_${if (value) "processed" else "original"}_${record.id}"))
            }
        }
        val text = if (processed) record.processedText else record.transcript
        SelectionContainer { Text(text, Modifier.testTag("history_text_${record.id}"), style = MaterialTheme.typography.bodyLarge) }
        TextButton({ onCopy(text) }, Modifier.testTag("history_copy_${record.id}")) {
            Text(stringResource(if (processed) R.string.text_processing_copy_processed else R.string.text_processing_copy_original))
        }
        if (processed && record.processingStatus != TextProcessingStatus.COMPLETE) Text(
            stringResource(R.string.text_processing_previous), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DraftMessage(draft: HistoryDraft, onEdit: ((String) -> Unit)?, onClear: (() -> Unit)?, onCopy: (String) -> Unit, ui: HistoryUiState) {
    val expanded = ui.draftExpanded
    val menu = ui.menuId == "draft"
    val options = stringResource(R.string.history_more)
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(.96f).testTag("history_draft").animateContentSize()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.history_draft), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TranscriptToggle(expanded, { ui.draftExpanded = !expanded }, "history_expand_draft")
                if (onClear != null) Box {
                    IconButton({ ui.menuId = "draft" }, Modifier.testTag("history_menu_draft").semantics { contentDescription = options }) {
                        Text("⋮", modifier = Modifier.clearAndSetSemantics { })
                    }
                    DropdownMenu(menu, { ui.menuId = null }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.history_clear_draft)) },
                            onClick = { ui.menuId = null; onClear() }, modifier = Modifier.testTag("clear"))
                    }
                }
            }
            if (expanded) TranscriptBody(draft.text, onEdit, { onCopy(draft.text) }, "history_text_draft", "copy")
        }
    }
}

@Composable
private fun TranscriptToggle(expanded: Boolean, onToggle: () -> Unit, tag: String) {
    val label = stringResource(if (expanded) R.string.history_less else R.string.history_full_text)
    FilledTonalIconButton(onToggle, Modifier.size(48.dp).testTag(tag).semantics { contentDescription = label }) {
        val color = LocalContentColor.current
        Canvas(Modifier.size(20.dp)) {
            val path = Path().apply {
                moveTo(size.width * .2f, size.height * if (expanded) .65f else .35f)
                lineTo(center.x, size.height * if (expanded) .35f else .65f)
                lineTo(size.width * .8f, size.height * if (expanded) .65f else .35f)
            }
            drawPath(path, color, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun RetryControl(id: String, pending: Boolean, saveReceived: Boolean, enabled: Boolean, onRetry: () -> Unit) {
    val label = stringResource(if (saveReceived) R.string.saved_save_received
        else if (pending) R.string.history_transcribe else R.string.history_retry)
    val colors = if (pending || saveReceived) IconButtonDefaults.filledIconButtonColors(
        containerColor = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer)
        else IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.error)
    FilledIconButton(onRetry, enabled = enabled, colors = colors,
        modifier = Modifier.size(48.dp).testTag("history_retry_$id").semantics { contentDescription = label }) {
        val color = LocalContentColor.current
        Canvas(Modifier.size(20.dp)) {
            drawArc(color, -40f, 280f, false, Offset(size.width * .15f, size.height * .15f),
                Size(size.width * .7f, size.height * .7f), style = Stroke(2.dp.toPx()))
            drawPath(Path().apply {
                moveTo(size.width * .8f, size.height * .05f)
                lineTo(size.width * .8f, size.height * .3f)
                lineTo(size.width * .55f, size.height * .3f)
            }, color, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun TranscriptBody(text: String, onEdit: ((String) -> Unit)?, onCopy: () -> Unit, textTag: String, copyTag: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        if (onEdit != null) BasicTextField(text, onEdit, Modifier.fillMaxWidth().testTag("transcript"),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
        else SelectionContainer { Text(text, Modifier.testTag(textTag), style = MaterialTheme.typography.bodyLarge) }
        TextButton(onCopy, Modifier.testTag(copyTag), enabled = text.isNotBlank(), contentPadding = PaddingValues(horizontal = 0.dp)) {
            Text(stringResource(R.string.copy), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun MessageNote(message: Int, error: Boolean = false) {
    Text(stringResource(message), Modifier.padding(horizontal = 8.dp, vertical = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodySmall, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun MessageStatus(message: Int, failed: Boolean) {
    Text(stringResource(message), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.labelSmall,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun TransportMark(showPause: Boolean, loading: Boolean) {
    val color = MaterialTheme.colorScheme.onPrimary
    Canvas(Modifier.size(20.dp)) {
        if (loading) drawLine(color, Offset(size.width * .2f, center.y), Offset(size.width * .8f, center.y), 2.dp.toPx())
        else if (showPause) {
            drawRoundRect(color, Offset(size.width * .18f, 0f), Size(size.width * .22f, size.height), CornerRadius(1.dp.toPx()))
            drawRoundRect(color, Offset(size.width * .6f, 0f), Size(size.width * .22f, size.height), CornerRadius(1.dp.toPx()))
        } else drawPath(Path().apply { moveTo(size.width * .18f, 0f); lineTo(size.width, center.y); lineTo(size.width * .18f, size.height); close() }, color)
    }
}

@Composable
private fun LoadedAudioTrack(id: String, file: File?, position: Float, modifier: Modifier) {
    val peaks by produceState<List<Float>?>(null, id, file?.length(), file?.lastModified()) {
        if (file != null) value = AndroidAudioWaveform.read(file)
    }
    AudioTrack(peaks, position, modifier.testTag(if (peaks != null) "history_waveform_$id" else "history_waveform_pending_$id"))
}

@Composable
private fun AudioTrack(peaks: List<Float>?, position: Float, modifier: Modifier) {
    val progress by animateFloatAsState(position.coerceIn(0f, 1f), tween(200), label = "Audio progress")
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f) }) {
        val values = peaks ?: List(40) { 0f }
        val step = size.width / values.size.coerceAtLeast(1)
        values.forEachIndexed { index, amplitude ->
            val height = maxOf(2.dp.toPx(), amplitude * size.height)
            val width = minOf(2.dp.toPx(), step * .65f)
            drawRoundRect(color.copy(alpha = if ((index + .5f) / values.size <= progress) 1f else .38f),
                Offset(index * step, (size.height - height) / 2), Size(width, height), CornerRadius(width / 2))
        }
    }
}

private fun clock(seconds: Int) = "%02d:%02d".format(seconds / 60, seconds % 60)
