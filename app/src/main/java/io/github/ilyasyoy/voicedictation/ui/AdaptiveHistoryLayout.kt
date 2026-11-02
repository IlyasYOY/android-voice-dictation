package io.github.ilyasyoy.voicedictation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature

/** Stable main slot: changing width does not recreate Settings or the dictation controls. */
@Composable
internal fun AdaptiveHistoryLayout(hinge: FoldingFeature? = null, historyEnabled: Boolean = true,
    main: @Composable (wide: Boolean) -> Unit,
    history: @Composable (wide: Boolean, region: HistoryRegion, windowWidth: Float, windowHeight: Float) -> Unit) {
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    var origin by remember { mutableStateOf(Offset.Zero) }
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
            Box(Modifier.absoluteOffset(region.left.dp, region.top.dp).width(plan.mainWidth.dp).height(region.height.dp)
                .testTag("dictation_pane")) {
                CompositionLocalProvider(LocalHistoryDialogRegion provides region.copy(
                    left = region.left + origin.x / density.density, top = region.top + origin.y / density.density,
                    width = plan.mainWidth)) { main(plan.wide) }
            }
            if (historyEnabled) Box(Modifier.absoluteOffset((region.left + if (plan.wide) plan.mainWidth + plan.gap else 0f).dp, region.top.dp)
                .width((if (plan.wide) plan.historyWidth else region.width).dp).height(region.height.dp)
                .padding(vertical = if (plan.wide) 12.dp else 0.dp, horizontal = if (plan.wide) 8.dp else 0.dp)) {
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
