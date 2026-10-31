package io.github.ilyasyoy.voicedictation

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.data.AndroidAudioRecorder
import io.github.ilyasyoy.voicedictation.data.OpenAiTranscriber
import io.github.ilyasyoy.voicedictation.floating.FloatingWindow
import io.github.ilyasyoy.voicedictation.ui.DictationViewModel
import java.io.File
import org.junit.*
import org.junit.Assert.*

/** No secure-flag override: exercise the same capture policy as the installed app. */
class ScreenshotPolicyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun mainScreenCanBeCapturedWhileApiKeyStaysMasked() {
        MainActivity.testNotice = "SCREENSHOT TEST · synthetic key · no API call"
        MainActivity.testFactory = { context ->
            DictationViewModel(AndroidAudioRecorder(context), OpenAiTranscriber())
                .also { it.setKey("synthetic-screen-key") }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { assertEquals(0, it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) }
                compose.onNodeWithTag("open_settings").performClick()
                compose.onNodeWithTag("api_key").performScrollTo()
                    .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
                compose.waitForIdle()
                val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                val out = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshot-policy").apply { mkdirs() }
                File(out, "masked-key-capturable.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally { MainActivity.testFactory = null; MainActivity.testNotice = null }
    }

    @Test fun floatingBubbleLiveAndResultStayCapturableWithoutTestNotice() {
        val base = instrumentation.targetContext
        var flags: Int? = null
        var root: View? = null
        var width = 0
        val manager = object : WindowManager by base.getSystemService(WindowManager::class.java) {
            override fun addView(view: View, params: ViewGroup.LayoutParams) { flags = (params as WindowManager.LayoutParams).flags; root = view; width = params.width }
            override fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) { flags = (params as WindowManager.LayoutParams).flags; root = view; width = params.width }
            override fun removeView(view: View) = Unit
        }
        val context = object : ContextWrapper(base) {
            override fun getSystemService(name: String): Any? = if (name == Context.WINDOW_SERVICE) manager else super.getSystemService(name)
        }
        instrumentation.runOnMainSync {
            val window = FloatingWindow(context, {}, {}, {}, {}, {})
            try {
                for (notice in listOf(null, "SYNTHETIC TEST")) {
                    for (stage in 0..2) {
                        window.render(800, stage > 0, if (stage > 0) "Synthetic words" else "", "Status",
                            stage == 1, stage == 2, false, notice)
                        assertEquals("Capture must work at stage $stage with notice=$notice", 0,
                            checkNotNull(flags) and WindowManager.LayoutParams.FLAG_SECURE)
                        if (stage == 1) {
                            assertEquals((64 * base.resources.displayMetrics.density).toInt(), width)
                            assertEquals(View.GONE, root!!.findViewById<View>(R.id.floating_preview).visibility)
                        }
                        assertTrue(checkNotNull(flags) and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
                    }
                }
            } finally { window.hide() }
        }
    }
}
