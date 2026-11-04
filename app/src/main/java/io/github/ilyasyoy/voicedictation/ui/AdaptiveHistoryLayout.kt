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

/** Keep a book fold's geometry in FLAT too; posture alone must not move the workspace. */
internal fun selectHistoryFoldingFeature(features: List<FoldingFeature>): FoldingFeature? =
    features.firstOrNull { it.isSeparating || it.occlusionType == FoldingFeature.OcclusionType.FULL }
        ?: features.firstOrNull { it.orientation == FoldingFeature.Orientation.VERTICAL }

/** Main stays in one scaffold body while pane bounds and sheet visibility change around it. */
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
    val canExpand by rememberUpdatedState(drawerEnabled)
    val drawer = rememberStandardBottomSheetState(initialValue = if (drawerOpen) SheetValue.Expanded else SheetValue.PartiallyExpanded,
        confirmValueChange = { it != SheetValue.Expanded || canExpand })
    var compactTarget by remember { mutableStateOf<Boolean?>(null) }
    Surface(Modifier.fillMaxSize().testTag("adaptive_root"), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()
            .onGloballyPositioned { origin = it.positionInWindow() }) {
            val fold = hinge?.let {
                val bounds = it.bounds
                HistoryHinge((bounds.left - origin.x) / density.density, (bounds.top - origin.y) / density.density,
                    (bounds.right - origin.x) / density.density, (bounds.bottom - origin.y) / density.density,
                    it.orientation == FoldingFeature.Orientation.VERTICAL,
                    separating = it.isSeparating, occluding = it.occlusionType == FoldingFeature.OcclusionType.FULL)
            }
            val plan = HistoryLayoutPolicy.calculate(maxWidth.value, maxHeight.value, density.fontScale, fold, allowHistoryPane = historyEnabled)
            val region = plan.region
            val showDrawer = historyEnabled && !plan.wide && drawerVisible
            val visibleTarget = if (showDrawer) drawer.targetValue == SheetValue.Expanded else null
            SideEffect {
                // Capture intent while the real sheet still has anchors. Its empty wide host can
                // change anchors during measurement; that is not a user's collapse/dismissal.
                if (visibleTarget != null) compactTarget = visibleTarget
                else {
                    compactTarget?.let { target ->
                        if (plan.wide && target != drawerOpen) {
                            if (target) onDrawerExpanded() else onDrawerCollapsed()
                        }
                    }
                    compactTarget = null
                }
            }
            Box(Modifier.absoluteOffset(region.left.dp, region.top.dp).width(plan.mainWidth.dp).height(region.height.dp)
                .testTag("dictation_pane")) {
                CompositionLocalProvider(LocalHistoryDialogRegion provides region.copy(
                    left = region.left + origin.x / density.density, top = region.top + origin.y / density.density,
                    width = plan.mainWidth)) {
                    // Keep the body in a single subcomposition, including Settings/setup. Moving an
                    // AndroidView-bearing body in/out of Material's subcomposition corrupts slot state.
                    HistoryDrawer(drawer, drawerOpen, drawerEnabled, drawerCount,
                        onDrawerReveal, onDrawerExpanded, onDrawerCollapsed,
                        visible = showDrawer,
                        main = { padding -> Box(Modifier.fillMaxSize().padding(padding)) { main(plan.wide) } },
                        content = { history(false, region.copy(left = region.left + origin.x / density.density,
                            top = region.top + origin.y / density.density), window.width / density.density, window.height / density.density) })
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
