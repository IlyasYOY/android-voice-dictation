package io.github.ilyasyoy.voicedictation.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.ilyasyoy.voicedictation.R

/** A visible sheet lip, with equivalent tap and swipe actions and one accessible count. */
@Composable
internal fun HistoryEntrance(count: Int, enabled: Boolean, onOpen: () -> Unit) {
    val description = stringResource(R.string.history_open) + ", " + stringResource(R.string.saved_recordings, count)
    Box(Modifier.fillMaxWidth().testTag("history_swipe").pointerInput(enabled, onOpen) {
        var distance = 0f
        detectVerticalDragGestures(onDragStart = { distance = 0f }, onVerticalDrag = { change, amount ->
            distance += amount
            if (enabled) change.consume()
        }, onDragEnd = { if (enabled && distance < -32.dp.toPx()) onOpen() })
    }, contentAlignment = Alignment.BottomCenter) {
        Surface(onClick = onOpen, enabled = enabled, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp,
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("saved_recordings")
                .semantics { contentDescription = description; role = Role.Button }) {
            Column(Modifier.heightIn(min = 76.dp).padding(top = 10.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(36.dp, 4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f), RoundedCornerShape(2.dp)))
                Text(pluralStringResource(R.plurals.history_saved_count, count, count), Modifier.clearAndSetSemantics { },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .5f))
            }
        }
    }
}
