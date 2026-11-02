package io.github.ilyasyoy.voicedictation.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import io.github.ilyasyoy.voicedictation.R

/** Content of the drawer's only handle. Its parent standard sheet owns all vertical gestures. */
@Composable
internal fun HistoryEntrance(count: Int, enabled: Boolean, expanded: Boolean, onToggle: () -> Unit,
    modifier: Modifier = Modifier) {
    val description = stringResource(if (expanded) R.string.history_close else R.string.history_open) + ", " + stringResource(R.string.saved_recordings, count)
    val position = stringResource(if (expanded) R.string.history_drawer_expanded else R.string.history_drawer_collapsed)
    BoxWithConstraints(modifier.fillMaxWidth().testTag("history_swipe"), contentAlignment = Alignment.TopCenter) {
            val showCount = maxHeight >= 44.dp * LocalDensity.current.fontScale
            Column(Modifier.fillMaxSize().clickable(enabled = enabled, role = Role.Button, onClick = onToggle)
                .testTag("saved_recordings").semantics {
                    contentDescription = description
                    stateDescription = position
                }.padding(top = if (showCount) 10.dp else 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (showCount) Arrangement.spacedBy(8.dp) else Arrangement.Center) {
                Box(Modifier.size(36.dp, 4.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f), RoundedCornerShape(2.dp)))
                if (showCount) Text(pluralStringResource(R.plurals.history_saved_count, count, count), Modifier.clearAndSetSemantics { },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else .5f))
            }
    }
}
