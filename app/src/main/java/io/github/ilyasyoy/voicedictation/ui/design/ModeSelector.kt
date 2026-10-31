package io.github.ilyasyoy.voicedictation.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.ilyasyoy.voicedictation.R
import io.github.ilyasyoy.voicedictation.session.Mode

/** One moving highlight; large text/narrow space stacks the segments rather than truncating their labels. */
@Composable
internal fun ModeSelector(mode: Mode, enabled: Boolean, onMode: (Mode) -> Unit, modifier: Modifier = Modifier) {
    val selection by animateFloatAsState(if (mode == Mode.LIVE) 0f else 1f, tween(350), label = "Selected mode")
    val fontScale=LocalDensity.current.fontScale
    BoxWithConstraints(modifier.widthIn(max = 240.dp).fillMaxWidth().clip(RoundedCornerShape(28.dp))
        .background(MaterialTheme.colorScheme.surfaceContainer).padding(4.dp).selectableGroup()) {
        val vertical=maxWidth < 140.dp * fontScale
        Box(Modifier.matchParentSize()) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val travel=if(vertical) maxHeight/2 else maxWidth/2
                Box(Modifier.offset { if(vertical)IntOffset(0,(travel*selection).roundToPx()) else IntOffset((travel*selection).roundToPx(),0) }
                    .fillMaxWidth(if(vertical)1f else .5f).fillMaxHeight(if(vertical).5f else 1f)
                    .clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = .14f)))
            }
        }
        if (vertical) Column {
            for (option in listOf(Mode.LIVE,Mode.RECORD)) ModeOption(option,mode,enabled,onMode,Modifier.fillMaxWidth())
        } else Row {
            for (option in listOf(Mode.LIVE,Mode.RECORD)) ModeOption(option,mode,enabled,onMode,Modifier.weight(1f))
        }
    }
}

@Composable
private fun ModeOption(option: Mode, selected: Mode, enabled: Boolean, onMode: (Mode) -> Unit, modifier: Modifier) {
    Box(modifier.heightIn(min = 48.dp).testTag(if(option==Mode.LIVE)"mode_live" else "mode_record")
        .selectable(selected==option,enabled,Role.Tab) { onMode(option) },contentAlignment=Alignment.Center) {
        Text(stringResource(if(option==Mode.LIVE)R.string.mode_live else R.string.current_record_mode),
            Modifier.padding(horizontal=4.dp,vertical=12.dp),style=MaterialTheme.typography.labelLarge,
            color=MaterialTheme.colorScheme.onSurface.copy(alpha=if(enabled)1f else .5f))
    }
}
