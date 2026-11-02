package io.github.ilyasyoy.voicedictation

import android.content.Context
import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.floating.*
import io.github.ilyasyoy.voicedictation.session.*
import io.github.ilyasyoy.voicedictation.ui.DictationScreen
import io.github.ilyasyoy.voicedictation.ui.theme.VoiceDictationTheme
import org.junit.*
import org.junit.Assert.*

class SoftCurrentAcceptanceTest {
    @get:org.junit.Rule val existingFeatureSetup: org.junit.rules.TestRule = ExistingFeatureSetupRule()
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun capture(name: String) {
        compose.waitForIdle()
        for (cmd in listOf("mkdir -p /sdcard/Download/soft-current-acceptance", "screencap -p /sdcard/Download/soft-current-acceptance/$name.png"))
            instrumentation.uiAutomation.executeShellCommand(cmd).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }
    @Test fun compactViewportAndLargeFontKeepControlsAccessibleWithoutDecorativeLabels() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for ((width,height,font) in listOf(Triple(320,520,1f),Triple(240,360,2f))) {
                var state by mutableStateOf(DictationState(key="synthetic-key",text="Synthetic final words",phase=Phase.RESULT))
                var cancelled=false
                scenario.onActivity { activity -> activity.setContent {
                    val density=LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density,font)) {
                        VoiceDictationTheme {
                            Box(Modifier.fillMaxSize()) { Box(Modifier.size(width.dp,height.dp)) {
                                DictationScreen(state,{}, {}, {}, { cancelled=true; state=state.copy(phase=Phase.RESULT) },{}, {}, {}, {}, {},
                                    { state=state.copy(mode=it) },{})
                            } }
                        }
                    }
                } }
                compose.onNodeWithText("Voice Dictation").assertDoesNotExist()
                compose.onNodeWithText("Make room for a thought").assertDoesNotExist()
                compose.onNodeWithText("Your words are ready").assertDoesNotExist()
                compose.onNodeWithText("Confirmed segments",substring=true).assertDoesNotExist()
                compose.onNodeWithText("Change").assertDoesNotExist()
                compose.onNodeWithTag("record").assertIsDisplayed().assertContentDescriptionEquals("Record")
                val bounds=compose.onNodeWithTag("record").getUnclippedBoundsInRoot()
                assertTrue("Wave must fit the compact viewport: $bounds",bounds.left>=0.dp && bounds.right<=width.dp && bounds.top>=0.dp && bounds.bottom<=height.dp)
                fun assertModeLabelsFit() {
                    for (label in listOf("Live","Record")) compose.onNodeWithText(label,useUnmergedTree=true)
                        .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                            val layouts=mutableListOf<TextLayoutResult>(); action(layouts)
                            assertEquals("Mode labels must not break into fragments",1,layouts.single().lineCount)
                        }
                }
                assertModeLabelsFit()
                compose.onNodeWithTag("mode_live").performScrollTo().performClick().assertIsSelected()
                assertEquals(Mode.LIVE,state.mode)
                compose.onNodeWithTag("mode_record").performScrollTo().performClick().assertIsSelected()
                assertEquals(Mode.RECORD,state.mode)
                capture("main-$width-font-$font")
                compose.onNodeWithTag("open_settings").performScrollTo().performClick()
                compose.onNodeWithText("A little space for the details.").assertDoesNotExist()
                compose.onNodeWithTag("api_key").performScrollTo().assertExists()
                capture("settings-$width-font-$font")
                compose.onNodeWithTag("close_settings").performScrollTo().performClick()
                compose.runOnIdle { state=state.copy(mode=Mode.LIVE,phase=Phase.RECORDING,livePartial=true) }
                compose.onNodeWithTag("mode_live").assertIsNotEnabled()
                compose.onNodeWithTag("mode_record").assertIsNotEnabled()
                assertModeLabelsFit()
                compose.onNodeWithTag("stop").performScrollTo().assertContentDescriptionEquals("Stop")
                val stopBounds=compose.onNodeWithTag("stop").getUnclippedBoundsInRoot()
                assertTrue("Full Stop target must fit even with large recording status text: $stopBounds",stopBounds.left>=0.dp && stopBounds.right<=width.dp && stopBounds.top>=0.dp && stopBounds.bottom<=height.dp)
                capture("recording-$width-font-$font")
                compose.onNodeWithText("Cancel · keep received text").assertDoesNotExist()
                compose.onNodeWithTag("session_controls").performScrollTo().performClick()
                compose.onNodeWithTag("cancel").performClick()
                assertTrue(cancelled)
                assertEquals("Synthetic final words",state.text)
            }
        }
    }
    @Test fun floatingRecoveryOpensOnlyOnIntentAndDismissKeepsUninsertedText() {
        val base=instrumentation.targetContext
        var root: View?=null
        var attached=0
        val manager=object : WindowManager by base.getSystemService(WindowManager::class.java) {
            override fun addView(view: View, params: ViewGroup.LayoutParams) { root=view; attached++ }
            override fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) { root=view }
            override fun removeView(view: View) = Unit
        }
        val context=object : ContextWrapper(base) {
            override fun getSystemService(name: String): Any?=if (name==Context.WINDOW_SERVICE) manager else super.getSystemService(name)
        }
        instrumentation.runOnMainSync {
            val feedback=FloatingFeedback()
            var copies=0
            lateinit var window: FloatingWindow
            fun render()=window.render(900,feedback.expanded,feedback.text,feedback.message?:context.getString(R.string.floating_start),false,feedback.text.isNotBlank(),false,null)
            window=FloatingWindow(context,{feedback.expanded=!feedback.expanded; render()},{},{copies++},{feedback.dismiss();render()},{})
            try {
                assertFalse(feedback.complete(InsertionResult.COPY,"Synthetic uninserted text","Copy required","Check field first"))
                render()
                val view=checkNotNull(root)
                assertEquals(View.GONE,view.findViewById<View>(R.id.floating_preview).visibility)
                assertEquals(View.GONE,view.findViewById<View>(R.id.floating_status).visibility)
                assertEquals(0,copies)
                view.findViewById<View>(R.id.floating_start).performClick()
                assertEquals(View.VISIBLE,view.findViewById<View>(R.id.floating_preview).visibility)
                view.findViewById<View>(R.id.floating_close).performClick()
                assertEquals("Synthetic uninserted text",feedback.text)
                assertEquals(View.GONE,view.findViewById<View>(R.id.floating_preview).visibility)
                assertTrue(feedback.complete(InsertionResult.INSERTED,feedback.text,"Copy required","Check field first"))
                render()
                assertEquals("Synthetic uninserted text",feedback.text)
                assertEquals(View.GONE,view.findViewById<View>(R.id.floating_preview).visibility)
                assertEquals(1,attached) // Confirmed success settles the existing mark instead of flashing a report/window.
                assertEquals(0,copies)
            } finally { window.hide() }
        }
    }
    @Test fun largeFontRecoveryScrollsWithinSmallSpaceAboveKeyboard() {
        val config=android.content.res.Configuration(instrumentation.targetContext.resources.configuration).apply { fontScale=2f }
        val base=instrumentation.targetContext.createConfigurationContext(config)
        var root: View?=null
        var params: WindowManager.LayoutParams?=null
        val manager=object : WindowManager by base.getSystemService(WindowManager::class.java) {
            override fun addView(view: View, layout: ViewGroup.LayoutParams) { root=view; params=layout as WindowManager.LayoutParams }
            override fun updateViewLayout(view: View, layout: ViewGroup.LayoutParams) { root=view; params=layout as WindowManager.LayoutParams }
            override fun removeView(view: View) = Unit
        }
        val context=object : ContextWrapper(base) {
            override fun getResources()=base.resources
            override fun getSystemService(name: String): Any?=if(name==Context.WINDOW_SERVICE)manager else super.getSystemService(name)
        }
        instrumentation.runOnMainSync {
            val window=FloatingWindow(context,{}, {}, {}, {}, {})
            try {
                window.render(180,true,"Synthetic words ".repeat(60),"Check the field before Copy. Insertion could not be confirmed.",false,true,false,null)
                val scroll=checkNotNull(root) as android.widget.ScrollView
                val layout=checkNotNull(params)
                scroll.layout(0,0,scroll.measuredWidth,scroll.measuredHeight)
                assertTrue(layout.y>=24 && layout.y+layout.height<=180)
                assertTrue("Long recovery must scroll rather than clip its actions",scroll.canScrollVertically(1))
                assertTrue(scroll.findViewById<android.widget.TextView>(R.id.floating_status).textSize>=27f)
                scroll.scrollTo(0,scroll.getChildAt(0).height)
                assertTrue(scroll.scrollY>0)
                val discard=scroll.findViewById<View>(R.id.floating_discard)
                assertTrue("Last explicit action is reachable",discard.bottom-scroll.scrollY+scroll.paddingTop<=scroll.height-scroll.paddingBottom)
            } finally { window.hide() }
        }
    }

}
