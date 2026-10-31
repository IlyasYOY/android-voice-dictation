package io.github.ilyasyoy.voicedictation.ui.design

import android.content.Context
import android.view.View
import android.widget.ImageView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Native drawing lets both entry points use exactly the same frame clock and renderer. */
internal class CurrentMarkView(context: Context) : ImageView(context) {
    val mark = CurrentDrawable()
    init { setImageDrawable(mark); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (windowVisibility == VISIBLE) mark.start() }
    override fun onDetachedFromWindow() { mark.stop(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow && visibility == VISIBLE) mark.start() else mark.stop()
    }
}

@Composable
internal fun CurrentControl(label: String, tag: String, visual: CurrentVisual, enabled: Boolean, onClick: () -> Unit, size: Dp = 228.dp) {
    Box(Modifier.size(size)
        .testTag(tag).semantics { contentDescription = label }
        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            enabled = enabled, role = Role.Button, onClick = onClick)) {
        AndroidView(factory = { CurrentMarkView(it) }, update = { it.mark.visual=visual; if(it.isAttachedToWindow && it.windowVisibility==View.VISIBLE) it.mark.start() }, modifier = Modifier.fillMaxSize())
    }
}
