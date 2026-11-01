package io.github.ilyasyoy.voicedictation.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecordingHistorySheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val owner = RecordingRecovery.processOwned
    val recovery by owner.state.collectAsStateWithLifecycle()
    val playback = remember { AndroidRecordingPlayback.create(context) }
    val playing by playback.state.collectAsStateWithLifecycle()
    val repository = remember { runCatching { AndroidRecordingStorage.repository(context) }.getOrNull() }
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
            }, onDelete = { id -> playback.stop(); owner.discard(id) }, onCopy = { text ->
                val clip = ClipData.newPlainText(context.getString(R.string.transcript),text)
                if (Build.VERSION.SDK_INT >= 33) clip.description.extras = PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE",true)
                }
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
            }, onClose = onDismiss)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RecordingHistory(state: RecordingRecoveryState, playback: PlaybackState,
    audioAvailable: (String) -> Boolean, onPlay: (String) -> Unit, onRetry: (String) -> Unit,
    onDelete: (String) -> Unit, onCopy: (String) -> Unit, onClose: () -> Unit) {
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    val deleteRecord = state.records.firstOrNull { it.id == deleteId }
    if (deleteRecord != null) AlertDialog(onDismissRequest = { deleteId = null },
        text = { Text(stringResource(R.string.saved_delete_confirm)) },
        confirmButton = { TextButton({ onDelete(deleteRecord.id); deleteId = null },
            enabled = !state.retrying && deleteRecord.id !in state.ownedIds, modifier = Modifier.testTag("history_confirm_delete")) {
            Text(stringResource(R.string.saved_delete))
        } }, dismissButton = { TextButton({ deleteId = null }) { Text(stringResource(android.R.string.cancel)) } })
    Column(Modifier.fillMaxWidth().widthIn(max=640.dp).padding(horizontal=20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.history_title), style=MaterialTheme.typography.headlineSmall, fontWeight=FontWeight.Medium)
                Text(stringResource(R.string.history_count,state.records.size),style=MaterialTheme.typography.labelMedium,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClose,Modifier.testTag("history_close")) { Text(stringResource(R.string.history_close)) }
        }
        LazyColumn(Modifier.fillMaxWidth().testTag("history_list"),
            contentPadding=PaddingValues(top=16.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item("privacy") { Text(stringResource(R.string.saved_retention),style=MaterialTheme.typography.bodySmall,
                color=MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.storageProblem) item("storage") { Text(stringResource(R.string.error),color=MaterialTheme.colorScheme.error,
                modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite }) }
            if (state.records.isEmpty()) item("empty") {
                Surface(shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.saved_empty),style=MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.history_empty_hint),style=MaterialTheme.typography.bodyMedium,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(state.records,key={it.id}) { record ->
                var expanded by rememberSaveable(record.id) { mutableStateOf(false) }
                var overflow by remember(record.transcript, state.receivedTexts[record.id]) { mutableStateOf(false) }
                val text=state.receivedTexts[record.id] ?: record.transcript
                val available=audioAvailable(record.id)
                val owned=record.id in state.ownedIds
                val active=playback.id==record.id
                val timestamp=DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(record.createdAt))
                val playLabel=stringResource(if(active && playback.loading) R.string.history_loading else if(active && playback.playing) R.string.history_pause else R.string.history_play)
                val copyLabel=stringResource(R.string.copy)
                val deleteLabel=stringResource(R.string.history_delete)
                val busy=record.status in setOf(SavedRecordingStatus.CAPTURING,SavedRecordingStatus.UPLOADING)
                val canRetry=record.kind==SavedRecordingKind.RECORD && available && record.status in
                    setOf(SavedRecordingStatus.PENDING,SavedRecordingStatus.FAILED,SavedRecordingStatus.INTERRUPTED)
                Surface(shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.surface,
                    modifier=Modifier.fillMaxWidth().testTag("history_card_${record.id}")) {
                    Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(timestamp,
                            style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        val status=when(record.status) {
                            SavedRecordingStatus.COMPLETE -> if(record.partial) R.string.partial_text else R.string.saved_transcribed
                            SavedRecordingStatus.CAPTURING -> R.string.recording
                            SavedRecordingStatus.UPLOADING -> R.string.transcribing
                            SavedRecordingStatus.FAILED -> R.string.history_failed
                            SavedRecordingStatus.INTERRUPTED -> R.string.saved_interrupted
                            SavedRecordingStatus.UNAVAILABLE,SavedRecordingStatus.DELETING -> R.string.saved_unavailable
                            SavedRecordingStatus.PENDING -> R.string.saved_pending
                        }
                        Text(stringResource(status),style=MaterialTheme.typography.labelMedium,
                            color=if(record.status==SavedRecordingStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
                        if (record.kind==SavedRecordingKind.RECORD && !busy) {
                            Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainer) {
                                Row(Modifier.fillMaxWidth().padding(horizontal=8.dp,vertical=4.dp),verticalAlignment=Alignment.CenterVertically) {
                                    TextButton({onPlay(record.id)},enabled=available && state.records.none {it.status==SavedRecordingStatus.CAPTURING},
                                        modifier=Modifier.testTag("history_play_${record.id}").semantics {contentDescription="$playLabel, $timestamp"}) {
                                        Text(playLabel)
                                    }
                                    Column(Modifier.weight(1f).padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                        Text(if(available) (if(active) "${clock(playback.position/1000)} / ${clock(playback.duration/1000)}" else clock(record.durationSeconds))
                                            else stringResource(R.string.history_missing_audio), style=MaterialTheme.typography.labelMedium)
                                        LinearProgressIndicator(progress={if(active && playback.duration>0) (playback.position.toFloat()/playback.duration).coerceIn(0f,1f) else 0f},
                                            modifier=Modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                        if (record.kind==SavedRecordingKind.LIVE) Text(stringResource(R.string.saved_live_text),style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(playback.failedId==record.id) Text(stringResource(R.string.history_playback_error),color=MaterialTheme.colorScheme.error,
                            style=MaterialTheme.typography.bodySmall,modifier=Modifier.semantics {liveRegion=LiveRegionMode.Polite})
                        if(record.status==SavedRecordingStatus.INTERRUPTED && record.kind==SavedRecordingKind.RECORD)
                            Text(stringResource(R.string.history_interrupted_audio),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        if(text.isNotBlank()) {
                            SelectionContainer { Text(text,style=MaterialTheme.typography.bodyLarge,maxLines=if(expanded) Int.MAX_VALUE else 6,
                                overflow=TextOverflow.Ellipsis,onTextLayout={ if(!expanded) overflow=it.hasVisualOverflow },modifier=Modifier.testTag("history_text_${record.id}")) }
                            if(overflow || expanded) TextButton({expanded=!expanded},Modifier.testTag("history_expand_${record.id}")) {
                                Text(stringResource(if(expanded) R.string.history_less else R.string.history_full_text))
                            }
                        }
                        if(record.id in state.receivedTexts) Text(stringResource(R.string.saved_unsaved_text),style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.error)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            if(canRetry) FilledTonalButton({onRetry(record.id)},enabled=!state.retrying && !owned,
                                modifier=Modifier.testTag("history_retry_${record.id}")) {
                                Text(stringResource(if(record.id in state.receivedTexts) R.string.saved_save_received else if(record.status==SavedRecordingStatus.PENDING) R.string.history_transcribe else R.string.retry))
                            }
                            if(text.isNotBlank()) TextButton({onCopy(text)},Modifier.testTag("history_copy_${record.id}").semantics {contentDescription="$copyLabel, $timestamp"}) { Text(copyLabel) }
                            TextButton({deleteId=record.id},enabled=!state.retrying && !owned && !busy,
                                modifier=Modifier.testTag("history_delete_${record.id}").semantics {contentDescription="$deleteLabel, $timestamp"}) { Text(deleteLabel) }
                        }
                    }
                }
            }
        }
    }
}
private fun clock(seconds: Int) = "%02d:%02d".format(seconds/60,seconds%60)
