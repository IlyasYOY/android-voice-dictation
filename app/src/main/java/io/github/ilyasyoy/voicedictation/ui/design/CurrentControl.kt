package io.github.ilyasyoy.voicedictation.ui.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun CurrentControl(label: String, tag: String, visual: CurrentVisual, enabled: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (visual == CurrentVisual.RECORDING) .96f else 1f, tween(500), label = "Current state")
    val drawable = remember { CurrentDrawable() }
    Box(Modifier.size(228.dp).alpha(if (enabled || visual == CurrentVisual.PROCESSING) 1f else .5f)
        .testTag(tag).semantics { contentDescription = label }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { canvas ->
                drawable.visual = visual
                val inset = (size.minDimension * (1 - scale) / 2).toInt()
                drawable.setBounds(inset, inset, size.width.toInt()-inset, size.height.toInt()-inset)
                drawable.draw(canvas.nativeCanvas)
            }
        }
        if (visual == CurrentVisual.PROCESSING) CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 2.dp)
        else Text(label, Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}
