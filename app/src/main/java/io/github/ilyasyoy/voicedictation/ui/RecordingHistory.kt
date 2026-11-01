package io.github.ilyasyoy.voicedictation.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.ilyasyoy.voicedictation.R
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.floating.DebugFloatingConfiguration
import io.github.ilyasyoy.voicedictation.floating.FloatingFeedback
import io.github.ilyasyoy.voicedictation.playback.*
import io.github.ilyasyoy.voicedictation.session.*
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

internal data class HistoryDraft(val text: String, val sourceId: String?, val visible: Boolean = text.isNotBlank()) {
    override fun toString() = "HistoryDraft(textLength=${text.length}, sourceId=$sourceId, visible=$visible)"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordingHistorySheet(onDismiss: () -> Unit, onRecordsChanged: () -> Unit = {},
    draft: HistoryDraft? = null, onDraftEdit: ((String) -> Unit)? = null, onDraftClear: (() -> Unit)? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val owner = RecordingRecovery.processOwned
    val recovery by owner.state.collectAsStateWithLifecycle()
    val playback = remember { AndroidRecordingPlayback.create(context) }
    val playing by playback.state.collectAsStateWithLifecycle()
    val repository = remember { runCatching { AndroidRecordingStorage.repository(context) }.getOrNull() }
    var storedDraft by remember { mutableStateOf<HistoryDraft?>(null) }
    // An emptied editor keeps its visual place until Close/Clear; the session still owns persistence.
    val editorSource = remember { draft?.sourceId }
    LaunchedEffect(recovery.records) {
        if (draft == null) storedDraft = runCatching {
            repository?.savedDraft(RecordingTextSlot.MAIN)?.let { HistoryDraft(it.text, it.sourceId) }
        }.getOrNull()
    }
    val currentDraft = draft?.copy(sourceId = draft.sourceId ?: editorSource) ?: storedDraft
    DisposableEffect(lifecycle, playback) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) AndroidRecordingPlayback.release(playback)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); AndroidRecordingPlayback.release(playback) }
    }
    LaunchedEffect(playing.playing) { while (playing.playing) { delay(250); playback.tick() } }
    LaunchedEffect(recovery.records, recovery.ownedIds) {
        if (recovery.records.any { it.status == SavedRecordingStatus.CAPTURING } ||
            playing.id?.let { id -> recovery.records.none { it.id == id } } == true) playback.stop()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.testTag("history_sheet")) {
        RecordingHistory(recovery, playing, audioAvailable = { id -> repository?.audio(id)?.let { it.isFile && it.length() > 0 } == true },
            onPlay = { id -> repository?.audio(id)?.let { AndroidRecordingPlayback.claim(playback); playback.toggle(id,it) } },
            onRetry = { id ->
                playback.stop()
                scope.launch {
                    try {
                        owner.retry(id) { audio -> DebugFloatingConfiguration.transcribeRetained(context,audio)
                            ?: DictationSessions.transcribeRetained(context,audio) }?.let {
                            FloatingFeedback.processOwned.retain(it); FloatingFeedback.processOwned.resetSignal()
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Typed ledger state is rendered below; no content or diagnostics logged. */ }
                }
            }, onDelete = { id -> playback.stop(); owner.discard(id); onRecordsChanged() }, onCopy = { text ->
                val clip = ClipData.newPlainText(context.getString(R.string.transcript),text)
                if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE",true)
                }
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            }, onClose = onDismiss, draft = currentDraft, onDraftEdit = onDraftEdit, onDraftClear = onDraftClear,
            waveform = { id ->
                val file = repository?.audio(id)
                val peaks by produceState<List<Float>?>(null, id, file?.length(), file?.lastModified()) {
                    if (file != null) value = AndroidAudioWaveform.read(file)
                }
                peaks
            })
    }
}

@Composable
internal fun RecordingHistory(state: RecordingRecoveryState, playback: PlaybackState,
    audioAvailable: (String) -> Boolean, onPlay: (String) -> Unit, onRetry: (String) -> Unit,
    onDelete: (String) -> Unit, onCopy: (String) -> Unit, onClose: () -> Unit,
    draft: HistoryDraft? = null, onDraftEdit: ((String) -> Unit)? = null, onDraftClear: (() -> Unit)? = null,
    waveform: @Composable (String) -> List<Float>? = { null }) {
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val deleteRecord = state.records.firstOrNull { it.id == deleteId }
    if (deleteRecord != null) AlertDialog(onDismissRequest = { deleteId = null },
        text = { Text(stringResource(R.string.saved_delete_confirm)) },
        confirmButton = { TextButton({ onDelete(deleteRecord.id); deleteId = null },
            enabled = !state.retrying && deleteRecord.id !in state.ownedIds, modifier = Modifier.testTag("history_confirm_delete")) {
            Text(stringResource(R.string.saved_delete))
        } }, dismissButton = { TextButton({ deleteId = null }) { Text(stringResource(android.R.string.cancel)) } })
    val currentDraft = draft?.takeIf { it.visible }
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.history_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium)
            TextButton(onClose, Modifier.testTag("history_close")) { Text(stringResource(R.string.history_close)) }
        }
        LazyColumn(Modifier.fillMaxWidth().testTag("history_list"),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.storageProblem) item("storage") {
                Text(stringResource(R.string.error), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (currentDraft != null && currentDraft.sourceId == null) item("current-text") {
                DraftMessage(currentDraft, onDraftEdit, onDraftClear, onCopy)
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
                    val peaks = if (available && record.kind == SavedRecordingKind.RECORD && record.status != SavedRecordingStatus.CAPTURING) waveform(record.id) else null
                    HistoryMessage(record, state, playback, available, peaks,
                        rowDraft, if (rowDraft != null) onDraftEdit else null, if (rowDraft != null) onDraftClear else null,
                        onPlay, onRetry, { deleteId = record.id }, onCopy)
                }
            }
            item("privacy") {
                Text(stringResource(R.string.saved_retention), Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HistoryMessage(record: SavedRecording, state: RecordingRecoveryState, playback: PlaybackState,
    available: Boolean, peaks: List<Float>?, draft: HistoryDraft?, onEdit: ((String) -> Unit)?, onClear: (() -> Unit)?,
    onPlay: (String) -> Unit, onRetry: (String) -> Unit, onDelete: () -> Unit, onCopy: (String) -> Unit) {
    var expanded by rememberSaveable(record.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
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
        SavedRecordingStatus.COMPLETE -> if (record.partial) R.string.partial_text else R.string.saved_transcribed
        SavedRecordingStatus.CAPTURING -> R.string.recording
        SavedRecordingStatus.UPLOADING -> R.string.transcribing
        SavedRecordingStatus.FAILED -> R.string.history_failed
        SavedRecordingStatus.INTERRUPTED -> R.string.saved_interrupted
        SavedRecordingStatus.UNAVAILABLE, SavedRecordingStatus.DELETING -> R.string.saved_unavailable
        SavedRecordingStatus.PENDING -> R.string.saved_pending
    }
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
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            AudioTrack(peaks, if (active && playback.duration > 0) playback.position.toFloat() / playback.duration else 0f,
                                Modifier.fillMaxWidth().height(28.dp).testTag("history_waveform_${record.id}"))
                            Text(if (!available) stringResource(R.string.history_missing_audio)
                                else if (active) "${clock(playback.position / 1000)} / ${clock(playback.duration / 1000)}" else clock(record.durationSeconds),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else Text(stringResource(R.string.saved_live_text), Modifier.weight(1f).padding(8.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (text.isNotBlank() || draft != null) TranscriptToggle(expanded, { expanded = !expanded }, "history_expand_${record.id}")
                }
                if (expanded) TranscriptBody(text, onEdit, { onCopy(text) }, "history_text_${record.id}", "history_copy_${record.id}")
                if (playback.failedId == record.id) MessageNote(R.string.history_playback_error, error = true)
                if (record.status == SavedRecordingStatus.INTERRUPTED && record.kind == SavedRecordingKind.RECORD) MessageNote(R.string.history_interrupted_audio)
                if (record.id in state.receivedTexts) MessageNote(R.string.saved_unsaved_text, error = true)
                if (canRetry) TextButton({ onRetry(record.id) }, enabled = !state.retrying && !owned,
                    modifier = Modifier.testTag("history_retry_${record.id}")) {
                    Text(stringResource(if (record.id in state.receivedTexts) R.string.saved_save_received else if (record.status == SavedRecordingStatus.PENDING) R.string.history_transcribe else R.string.retry))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (largeText) Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        MessageStatus(status, record.status == SavedRecordingStatus.FAILED)
                        Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Box(Modifier.weight(1f).padding(start = 8.dp)) { MessageStatus(status, record.status == SavedRecordingStatus.FAILED) }
                        Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        IconButton({ menu = true }, Modifier.size(48.dp).testTag("history_menu_${record.id}")
                            .semantics { contentDescription = timestamp + ", " + moreLabel }) {
                            Text("⋮", style = MaterialTheme.typography.titleLarge, modifier = Modifier.clearAndSetSemantics { })
                        }
                        DropdownMenu(menu, { menu = false }) {
                            if (onClear != null) DropdownMenuItem(text = { Text(stringResource(R.string.history_clear_draft)) },
                                onClick = { menu = false; onClear() }, modifier = Modifier.testTag("clear"))
                            DropdownMenuItem(text = { Text(stringResource(R.string.history_delete)) }, enabled = !state.retrying && !owned && !busy,
                                onClick = { menu = false; onDelete() }, modifier = Modifier.testTag("history_delete_${record.id}"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DraftMessage(draft: HistoryDraft, onEdit: ((String) -> Unit)?, onClear: (() -> Unit)?, onCopy: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val options = stringResource(R.string.history_more)
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(.96f).testTag("history_draft").animateContentSize()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.history_draft), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TranscriptToggle(expanded, { expanded = !expanded }, "history_expand_draft")
                if (onClear != null) Box {
                    IconButton({ menu = true }, Modifier.testTag("history_menu_draft").semantics { contentDescription = options }) {
                        Text("⋮", modifier = Modifier.clearAndSetSemantics { })
                    }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.history_clear_draft)) },
                            onClick = { menu = false; onClear() }, modifier = Modifier.testTag("clear"))
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
    IconButton(onToggle, Modifier.size(48.dp).testTag(tag).semantics { contentDescription = label }) {
        Text(if (expanded) "⌃" else "⌄", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clearAndSetSemantics { })
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
