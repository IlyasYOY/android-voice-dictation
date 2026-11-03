package io.github.ilyasyoy.voicedictation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature

/** Main composition moves intact between the compact scaffold body and the wide main pane. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AdaptiveHistoryLayout(hinge: FoldingFeature? = null, historyEnabled: Boolean = true,
    drawerOpen: Boolean = false, drawerEnabled: Boolean = true, drawerVisible: Boolean = true, drawerCount: Int = 0,
    onDrawerReveal: () -> Unit = {}, onDrawerExpanded: () -> Unit = {}, onDrawerCollapsed: () -> Unit = {},
    main: @Composable (wide: Boolean) -> Unit,
    history: @Composable (wide: Boolean, region: HistoryRegion, windowWidth: Float, windowHeight: Float) -> Unit) {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    var origin by remember { mutableStateOf(Offset.Zero) }
    val currentMain by rememberUpdatedState(main)
    val mainContent = remember { movableContentOf<Boolean> { wide -> currentMain(wide) } }
    val canExpand by rememberUpdatedState(drawerEnabled)
    val drawer = rememberStandardBottomSheetState(initialValue = if (drawerOpen) SheetValue.Expanded else SheetValue.PartiallyExpanded,
        confirmValueChange = { it != SheetValue.Expanded || canExpand })
    Surface(Modifier.fillMaxSize().testTag("adaptive_root"), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()
            .onGloballyPositioned { origin = it.positionInWindow() }) {
            val fold = hinge?.takeIf { it.isSeparating }?.let {
                val bounds = it.bounds
                HistoryHinge((bounds.left - origin.x) / density.density, (bounds.top - origin.y) / density.density,
                    (bounds.right - origin.x) / density.density, (bounds.bottom - origin.y) / density.density,
                    it.orientation == FoldingFeature.Orientation.VERTICAL)
            }
            val plan = HistoryLayoutPolicy.calculate(maxWidth.value, maxHeight.value, density.fontScale, fold, allowHistoryPane = historyEnabled)
            val region = plan.region
            LaunchedEffect(plan.wide) {
                // A resize during a drag retains its settling intent; ordinary resize does not dismiss playback/state.
                if (plan.wide && (drawer.targetValue == SheetValue.Expanded) != drawerOpen) {
                    if (drawer.targetValue == SheetValue.Expanded) onDrawerExpanded() else onDrawerCollapsed()
                }
            }
            Box(Modifier.absoluteOffset(region.left.dp, region.top.dp).width(plan.mainWidth.dp).height(region.height.dp)
                .testTag("dictation_pane")) {
                CompositionLocalProvider(LocalHistoryDialogRegion provides region.copy(
                    left = region.left + origin.x / density.density, top = region.top + origin.y / density.density,
                    width = plan.mainWidth)) {
                    if (historyEnabled && !plan.wide && drawerVisible) HistoryDrawer(drawer, drawerOpen, drawerEnabled, drawerCount,
                        onDrawerReveal, onDrawerExpanded, onDrawerCollapsed,
                        main = { padding -> Box(Modifier.fillMaxSize().padding(padding)) { mainContent(false) } },
                        content = { history(false, region.copy(left = region.left + origin.x / density.density,
                            top = region.top + origin.y / density.density), window.width / density.density, window.height / density.density) })
                    else mainContent(plan.wide)
                }
            }
            if (historyEnabled && plan.wide) Box(Modifier.absoluteOffset((region.left + plan.mainWidth + plan.gap).dp, region.top.dp)
                .width(plan.historyWidth.dp).height(region.height.dp).padding(vertical = 12.dp, horizontal = 8.dp)) {
                val historyRegion = region.copy(
                    left = region.left + (if (plan.wide) plan.mainWidth + plan.gap else 0f) + origin.x / density.density,
                    top = region.top + origin.y / density.density,
                    width = if (plan.wide) plan.historyWidth else region.width)
                CompositionLocalProvider(LocalHistoryDialogRegion provides historyRegion) {
                    history(plan.wide, historyRegion, window.width / density.density, window.height / density.density)
                }
            }
        }
    }
}
