package io.github.ilyasyoy.voicedictation.fixture

import android.inputmethodservice.InputMethodService
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Separate validation-only IME; never packaged with or required by the product app. */
class FixtureKeyboard : InputMethodService() {
    override fun onCreateInputView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(8, 8, 8, 8); setBackgroundColor(android.graphics.Color.LTGRAY)
        addView(TextView(this@FixtureKeyboard).apply { setText(R.string.keyboard_banner); textSize = 18f })
        val row = LinearLayout(this@FixtureKeyboard)
        for (value in listOf("a", "space", "back", "hide")) row.addView(Button(this@FixtureKeyboard).apply {
            text = value; setOnClickListener {
                when (value) { "space" -> currentInputConnection?.commitText(" ", 1); "back" -> currentInputConnection?.deleteSurroundingText(1, 0)
                    "hide" -> requestHideSelf(0); else -> currentInputConnection?.commitText(value, 1) }
            }
        }, LinearLayout.LayoutParams(0, 64, 1f))
        addView(row); addView(TextView(this@FixtureKeyboard).apply { setText(R.string.keyboard_description); minHeight = 120 })
    }
}
