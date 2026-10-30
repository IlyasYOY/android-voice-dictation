package io.github.ilyasyoy.voicedictation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ilyasyoy.voicedictation.ui.DictationScreen
import io.github.ilyasyoy.voicedictation.ui.DictationViewModel
import io.github.ilyasyoy.voicedictation.ui.theme.VoiceDictationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoiceDictationTheme {
                val viewModel: DictationViewModel = viewModel(factory = DictationViewModel.Factory)
                val draft by viewModel.draft.collectAsStateWithLifecycle()
                DictationScreen(
                    draft = draft,
                    onTextChanged = viewModel::edit,
                    onLoadExample = viewModel::loadExample,
                    onClear = viewModel::clear,
                )
            }
        }
    }
}
