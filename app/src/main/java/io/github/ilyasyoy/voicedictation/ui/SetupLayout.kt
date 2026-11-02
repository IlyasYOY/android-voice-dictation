package io.github.ilyasyoy.voicedictation.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.ilyasyoy.voicedictation.R

/** Shared first-run and later floating setup surfaces; navigation stays separate from permission actions. */
@Composable
internal fun SetupLayout(title: String, modifier: Modifier = Modifier, titleTag: String = "setup_title",
    onBack: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding(),
            contentAlignment = if (onBack == null) Alignment.Center else Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onBack != null) {
                        val description = stringResource(R.string.setup_back)
                        val ink = MaterialTheme.colorScheme.onSurface
                        IconButton(onBack, Modifier.semantics { contentDescription = description }.testTag("setup_back")) {
                            Canvas(Modifier.size(24.dp)) {
                                val stroke = 2.dp.toPx()
                                drawLine(ink, Offset(size.width * .75f, size.height * .5f), Offset(size.width * .2f, size.height * .5f), stroke)
                                drawLine(ink, Offset(size.width * .2f, size.height * .5f), Offset(size.width * .5f, size.height * .2f), stroke)
                                drawLine(ink, Offset(size.width * .2f, size.height * .5f), Offset(size.width * .5f, size.height * .8f), stroke)
                            }
                        }
                    }
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).testTag(titleTag))
                }
                content()
            }
        }
    }
}

@Composable
internal fun SetupCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
internal fun SetupPrimary(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick, modifier.fillMaxWidth(), enabled = enabled) { Text(label) }
}
