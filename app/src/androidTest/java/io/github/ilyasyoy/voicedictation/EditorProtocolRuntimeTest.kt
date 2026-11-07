package io.github.ilyasyoy.voicedictation

import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.floating.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android EditText/InputConnection, including styled text. API-33 accessibility transport
 * is covered separately by the cross-UID fixture; optional read responses here are controlled.
 */
@RunWith(AndroidJUnit4::class)
class EditorProtocolRuntimeTest {
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun insertion(protocol: String) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var field: EditText
            lateinit var native: InputConnection
            var token = EditorToken("fixture.editor", 1, "body", 1, 7, 7, false, false, 0x20001, 0, -1, -1)
            scenario.onActivity { activity ->
                field = EditText(activity).apply {
                    inputType = 0x20001
                    setText(SpannableString("Before  after").apply { setSpan(StyleSpan(Typeface.BOLD), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) })
                }
                activity.setContentView(field); field.requestFocus(); field.setSelection(7)
                native = checkNotNull(field.onCreateInputConnection(EditorInfo()))
            }
            var writes = 0
            val target = object : EditorConnection {
                override suspend fun surrounding(before: Int): EditorSurrounding? {
                    if (protocol == "noReads") return null
                    var answer: EditorSurrounding? = null
                    onMain {
                        val start = field.selectionStart
                        val from = maxOf(0, start - before)
                        answer = EditorSurrounding(field.text.subSequence(from, start).toString(),
                            if (protocol == "unknownOffset") -1 else from, start - from, field.selectionEnd - from)
                    }
                    return answer
                }
                override fun commit(text: String) {
                    onMain {
                        writes++
                        assertTrue(native.commitText(text, 1))
                        token = token.copy(selectionStart = field.selectionStart, selectionEnd = field.selectionEnd)
                    }
                }
            }
            val result = runBlocking { FinalTextInsertion.insert(token, "voice\nline", target,
                { FloatingEnvironment(token, true, true, true, true, true) }) { true } }
            assertEquals(if (protocol == "noReads") InsertionResult.UNCONFIRMED else InsertionResult.INSERTED, result)
            assertEquals(1, writes)
            onMain {
                assertEquals("Before voice\nline after", field.text.toString())
                assertEquals(17, field.selectionStart)
                val spans = field.text.getSpans(0, 6, StyleSpan::class.java)
                assertEquals(1, spans.size); assertEquals(Typeface.BOLD, spans.single().style)
                assertEquals(0, field.text.getSpanStart(spans.single())); assertEquals(6, field.text.getSpanEnd(spans.single()))
            }
        }
    }
    @Test fun normalEditorPreservesPrefixSuffixAndFormatting() = insertion("normal")
    @Test fun richEditorWithUnknownOffsetStillInserts() = insertion("unknownOffset")
    @Test fun editorWithoutSurroundingReadsStillReceivesOneCommit() = insertion("noReads")
}
