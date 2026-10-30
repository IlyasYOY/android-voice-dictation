package io.github.ilyasyoy.voicedictation.floating

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.InputMethod
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi

@RequiresApi(33)
internal class EditorInput(service: AccessibilityService, private val changed: () -> Unit) : InputMethod(service) {
    var serial = 0L; private set
    var editorPackage: String? = null; private set
    var inputType = 0; private set
    var imeOptions = 0; private set
    var selectionStart = -1; private set
    var selectionEnd = -1; private set
    var composingStart = -1; private set
    var composingEnd = -1; private set
    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        serial++
        // Extract metadata only. Never copy initial surrounding text, extras or hints.
        editorPackage = attribute.packageName; inputType = attribute.inputType; imeOptions = attribute.imeOptions
        selectionStart = attribute.initialSelStart; selectionEnd = attribute.initialSelEnd
        composingStart = -1; composingEnd = -1
        changed()
    }
    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        selectionStart = newSelStart; selectionEnd = newSelEnd
        composingStart = candidatesStart; composingEnd = candidatesEnd; changed()
    }
    override fun onFinishInput() {
        // Deliberately do not call super: its default clears the other keyboard's composing text.
        serial++; editorPackage = null; selectionStart = -1; selectionEnd = -1; composingStart = -1; composingEnd = -1; changed()
    }
}
