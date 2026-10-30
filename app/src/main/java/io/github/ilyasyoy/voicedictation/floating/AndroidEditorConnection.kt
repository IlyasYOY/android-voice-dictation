package io.github.ilyasyoy.voicedictation.floating

import android.accessibilityservice.InputMethod
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@RequiresApi(33)
internal class AndroidEditorConnection(private val connection: InputMethod.AccessibilityInputConnection) : EditorConnection {
    override suspend fun surrounding(before: Int): EditorSurrounding? = withContext(Dispatchers.IO) {
        connection.getSurroundingText(before, 0, 0)?.let { EditorSurrounding(it.text, it.offset, it.selectionStart, it.selectionEnd) }
    }
    override fun commit(text: String) { connection.commitText(text, 1, null) }
}
