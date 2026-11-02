package io.github.ilyasyoy.voicedictation.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Window-coordinate safe region supplied by the actual main/history slot. */
internal val LocalHistoryDialogRegion = compositionLocalOf<HistoryRegion?> { null }

internal fun historyDialogBounds(available: HistoryRegion, preferred: HistoryRegion?): HistoryRegion {
    val target = preferred ?: available
    val left = maxOf(available.left, target.left).coerceAtMost(available.left + available.width)
    val top = maxOf(available.top, target.top).coerceAtMost(available.top + available.height)
    val right = minOf(available.left + available.width, target.left + target.width)
    val bottom = minOf(available.top + available.height, target.top + target.height)
    return HistoryRegion(left, top, (right - left).coerceAtLeast(0f), (bottom - top).coerceAtLeast(0f))
}

/** Dialog content, including its buttons, stays inside one unobstructed region and can scroll. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HistorySafeDialog(onDismiss: () -> Unit, title: String? = null,
    content: @Composable () -> Unit, buttons: @Composable FlowRowScope.() -> Unit) {
    val preferred = LocalHistoryDialogRegion.current
    val currentDismiss by rememberUpdatedState(onDismiss)
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val density = LocalDensity.current
        val direction = LocalLayoutDirection.current
        val insets = WindowInsets.safeDrawing.union(WindowInsets.ime)
        BoxWithConstraints(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { currentDismiss() } }) {
            val left = insets.getLeft(density, direction) / density.density
            val top = insets.getTop(density) / density.density
            val available = HistoryRegion(left, top,
                (maxWidth.value - left - insets.getRight(density, direction) / density.density).coerceAtLeast(0f),
                (maxHeight.value - top - insets.getBottom(density) / density.density).coerceAtLeast(0f))
            val region = historyDialogBounds(available, preferred)
            Box(Modifier.absoluteOffset(region.left.dp, region.top.dp).width(region.width.dp).height(region.height.dp)
                .padding(16.dp), contentAlignment = Alignment.Center) {
                Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().testTag("history_safe_dialog")
                    .pointerInput(Unit) { detectTapGestures { /* Consume the card's blank space. */ } },
                    shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    tonalElevation = 6.dp) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (title != null) Text(title, style = MaterialTheme.typography.headlineSmall)
                        content()
                        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, content = buttons)
                    }
                }
            }
        }
    }
}
