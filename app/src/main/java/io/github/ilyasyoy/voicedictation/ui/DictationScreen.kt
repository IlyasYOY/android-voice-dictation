package io.github.ilyasyoy.voicedictation.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.ilyasyoy.voicedictation.R
import io.github.ilyasyoy.voicedictation.domain.TranscriptDraft
import io.github.ilyasyoy.voicedictation.ui.theme.VoiceDictationTheme

/** Stateless screen: no recorder, permissions, network client, or provider-specific state. */
@Composable
fun DictationScreen(
    draft: TranscriptDraft,
    onTextChanged: (String) -> Unit,
    onLoadExample: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { insets ->
        Box(
            modifier = Modifier.fillMaxSize().padding(insets).imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth()
                    .verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    stringResource(R.string.workspace_eyebrow),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.workspace_title),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.workspace_subtitle),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    shape = RoundedCornerShape(24.dp),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(50),
                        ) {
                            Text(
                                stringResource(R.string.scaffold_badge),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Text(stringResource(R.string.recording_title), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.recording_description), style = MaterialTheme.typography.bodyMedium)
                        Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth().testTag("start_dictation"),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        ) { Text(stringResource(R.string.start_dictation)) }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.transcript_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(
                            when {
                                draft.isExample -> R.string.sample_label
                                draft.canClear -> R.string.draft_label
                                else -> R.string.empty_label
                            },
                        ),
                        modifier = Modifier.testTag("draft_status"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = draft.text,
                        onValueChange = onTextChanged,
                        modifier = Modifier.fillMaxWidth().testTag("transcript"),
                        label = { Text(stringResource(R.string.transcript_title)) },
                        placeholder = { Text(stringResource(R.string.transcript_hint)) },
                        minLines = 5,
                        shape = RoundedCornerShape(16.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onLoadExample,
                            modifier = Modifier.weight(1f).testTag("load_example"),
                        ) { Text(stringResource(R.string.load_example)) }
                        TextButton(
                            onClick = onClear,
                            enabled = draft.canClear,
                            modifier = Modifier.weight(1f).testTag("clear_draft"),
                        ) { Text(stringResource(R.string.clear_draft)) }
                    }
                }
                Text(
                    stringResource(R.string.privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 393)
@Composable
private fun DictationPreview() {
    VoiceDictationTheme {
        DictationScreen(TranscriptDraft(), {}, {}, {})
    }
}

@Preview(showBackground = true, widthDp = 393, fontScale = 1.5f)
@Composable
private fun DictationLargeTextPreview() {
    VoiceDictationTheme {
        DictationScreen(TranscriptDraft(), {}, {}, {})
    }
}
