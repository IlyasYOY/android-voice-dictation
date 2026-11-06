package io.github.ilyasyoy.voicedictation.ui.design

import android.content.Context
import android.view.View
import android.widget.ImageView
import androidx.compose.foundation.combinedClickable
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
internal fun CurrentControl(label: String, tag: String, visual: CurrentVisual, enabled: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, size: Dp = 228.dp,
    levels: io.github.ilyasyoy.voicedictation.audio.VoiceLevelSource = io.github.ilyasyoy.voicedictation.audio.VoiceLevelSource.Silent,
    onLongClick: (() -> Unit)? = null, onLongClickLabel: String? = null) {
    Box(modifier.size(size)
        .testTag(tag).semantics { contentDescription = label }
        .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
            enabled = enabled, role = Role.Button, onClick = onClick,
            onLongClick = onLongClick, onLongClickLabel = onLongClickLabel)) {
        AndroidView(factory = { CurrentMarkView(it) }, update = { it.mark.visual=visual; it.mark.levels=levels; if(it.isAttachedToWindow && it.windowVisibility==View.VISIBLE) it.mark.start() }, modifier = Modifier.fillMaxSize())
    }
}

/** Decorative idle sample of the overlay: same drawable, 64dp bounds and 3dp content padding.
 * It has no click/long-click action, service, recorder or network callback.
 */
@Composable
internal fun FloatingButtonPreview(modifier: Modifier = Modifier) {
    AndroidView(factory = { context -> CurrentMarkView(context).apply {
        val inset = (3 * context.resources.displayMetrics.density).toInt()
        setPadding(inset, inset, inset, inset)
    } }, update = { it.mark.visual = CurrentVisual.IDLE }, modifier = modifier.size(64.dp))
}
