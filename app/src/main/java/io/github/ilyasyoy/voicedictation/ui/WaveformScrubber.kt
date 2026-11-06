package io.github.ilyasyoy.voicedictation.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.ilyasyoy.voicedictation.R

/** Preview-only drag: cancellation never issues a seek or changes the transport. */
@Composable
internal fun WaveformScrubber(id: String, selection: String?, position: Float, duration: Int, timestamp: String,
    enabled: Boolean, onPreview: (Float?) -> Unit, onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier, waveform: @Composable (Modifier) -> Unit) {
    val seek by rememberUpdatedState(onSeek)
    val preview by rememberUpdatedState(onPreview)
    DisposableEffect(id, selection, enabled) { onDispose { preview(null) } }
    val value = position.coerceIn(0f, 1f)
    val label = stringResource(R.string.history_seek)
    val back = stringResource(R.string.history_seek_back)
    val forward = stringResource(R.string.history_seek_forward)
    val description = stringResource(R.string.history_seek_position,
        time((value * duration).toInt()), time(duration))
    val color = MaterialTheme.colorScheme.primary
    val touch = if (enabled) Modifier
        .pointerInput(id, selection) {
            detectTapGestures(onTap = { seek((it.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)) })
        }
        .pointerInput(id, selection) {
            var target: Float? = null
            detectHorizontalDragGestures(
                onDragStart = { target = (it.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f); preview(target) },
                onHorizontalDrag = { change, _ ->
                    change.consume(); target = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f); preview(target)
                },
                onDragEnd = { target?.let(seek); target = null; preview(null) },
                onDragCancel = { target = null; preview(null) })
        }
        .semantics(mergeDescendants = true) {
            contentDescription = "$label, $timestamp"
            stateDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f)
            setProgress { requested ->
                if (!requested.isFinite()) false else { seek(requested.coerceIn(0f, 1f)); true }
            }
            if (duration > 0) customActions = listOf(
                CustomAccessibilityAction(back) { seek((value - 10_000f / duration).coerceAtLeast(0f)); true },
                CustomAccessibilityAction(forward) { seek((value + 10_000f / duration).coerceAtMost(1f)); true })
        }.focusable()
    else Modifier
    Box(modifier.then(touch).testTag("history_seek_$id"), contentAlignment = Alignment.Center) {
        waveform(Modifier.fillMaxWidth().height(28.dp))
        if (enabled) Canvas(Modifier.fillMaxWidth().height(28.dp)) {
            val radius = 3.dp.toPx()
            val x = (size.width * value).coerceIn(radius, size.width.coerceAtLeast(radius * 2) - radius)
            drawLine(color, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
            drawCircle(color, radius, Offset(x, size.height / 2))
        }
    }
}

private fun time(milliseconds: Int) = "%02d:%02d".format(milliseconds.coerceAtLeast(0) / 60_000, milliseconds.coerceAtLeast(0) / 1000 % 60)
