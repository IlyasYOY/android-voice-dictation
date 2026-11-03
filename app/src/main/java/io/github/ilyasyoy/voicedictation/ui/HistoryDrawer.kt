package io.github.ilyasyoy.voicedictation.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.ilyasyoy.voicedictation.ui.design.HistoryEntrance
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

internal val LocalHistoryContentVisible = staticCompositionLocalOf { true }

/** One persistent surface; Material owns drag, velocity settling, nested scroll and motion scale. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryDrawer(state: SheetState, open: Boolean, enabled: Boolean, count: Int,
    onReveal: () -> Unit, onExpanded: () -> Unit, onCollapsed: () -> Unit,
    modifier: Modifier = Modifier, main: @Composable (PaddingValues) -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val reveal by rememberUpdatedState(onReveal)
    val expanded by rememberUpdatedState(onExpanded)
    val collapsed by rememberUpdatedState(onCollapsed)
    val requestedOpen by rememberUpdatedState(open)
    val revealState = remember(state) { booleanArrayOf(false) }
    LaunchedEffect(open, enabled, state) {
        if (!enabled) state.partialExpand()
        else if (open && state.currentValue != SheetValue.Expanded) state.expand()
        else if (!open && state.currentValue != SheetValue.PartiallyExpanded) state.partialExpand()
    }
    LaunchedEffect(state) {
        var previous = state.currentValue
        snapshotFlow { state.currentValue }.distinctUntilChanged().collect { value ->
            if (value != previous) {
                previous = value
                if (value == SheetValue.Expanded && !requestedOpen) expanded()
                else if (value == SheetValue.PartiallyExpanded) {
                    focus.clearFocus(); keyboard?.hide()
                    if (requestedOpen) collapsed()
                }
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val peek = minOf(76.dp, maxHeight / 2)
        val closedOffset = with(density) { (maxHeight - peek).toPx() }
        val contentVisible by remember(state, closedOffset, open) { derivedStateOf {
            open || runCatching { state.requireOffset() < closedOffset - 1f }.getOrDefault(false)
        } }
        LaunchedEffect(state, closedOffset) {
            snapshotFlow { runCatching { state.requireOffset() }.getOrNull() }.collect { offset ->
                val moved = offset != null && offset < closedOffset - 1f
                if (moved && !revealState[0]) reveal()
                revealState[0] = moved
            }
        }
        BottomSheetScaffold(scaffoldState = rememberBottomSheetScaffoldState(state),
            modifier = Modifier.fillMaxSize().testTag("history_drawer"),
            sheetPeekHeight = peek, sheetMaxWidth = 640.dp, sheetSwipeEnabled = enabled,
            sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            sheetContentColor = MaterialTheme.colorScheme.onSurface, containerColor = Color.Transparent,
            sheetDragHandle = {
                HistoryEntrance(count, enabled, state.currentValue == SheetValue.Expanded,
                    onToggle = { if (enabled) {
                        scope.launch {
                            if (state.currentValue == SheetValue.Expanded) state.partialExpand() else state.expand()
                        }
                    } }, modifier = Modifier.height(peek))
            }, sheetContent = {
                Box(Modifier.fillMaxWidth().weight(1f).testTag("history_sheet")) {
                    CompositionLocalProvider(LocalHistoryContentVisible provides contentVisible) { content() }
                }
            }, content = main)
        // Native confirmation dialogs receive Back first; then the drawer, then main/capture handlers.
        BackHandler(open || state.currentValue == SheetValue.Expanded) {
            focus.clearFocus(); keyboard?.hide()
            if (state.currentValue == SheetValue.PartiallyExpanded) collapsed()
            else scope.launch { state.partialExpand() }
        }
    }
}
