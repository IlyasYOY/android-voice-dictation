package io.github.ilyasyoy.voicedictation

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.floating.*
import io.github.ilyasyoy.voicedictation.session.*
import io.github.ilyasyoy.voicedictation.ui.DictationScreen
import io.github.ilyasyoy.voicedictation.ui.theme.VoiceDictationTheme
import org.junit.*
import org.junit.Assert.*

class SecondRoundAcceptanceTest {
    @get:org.junit.Rule val existingFeatureSetup: org.junit.rules.TestRule = ExistingFeatureSetupRule()
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    @Test fun cloudSelectorAndSettingsStayInTheSamePositionAcrossSessionStatesWithoutSquareRipple() {
        var state by mutableStateOf(DictationState(key="synthetic-key"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { VoiceDictationTheme {
                DictationScreen(state,{}, {}, {}, {}, {}, {}, {}, {}, {}, {state=state.copy(mode=it)}, {})
            } } }
            val gear=compose.onNodeWithTag("open_settings").getUnclippedBoundsInRoot()
            val cloud=compose.onNodeWithTag("record").getUnclippedBoundsInRoot()
            val live=compose.onNodeWithTag("mode_live").getUnclippedBoundsInRoot()
            val record=compose.onNodeWithTag("mode_record").getUnclippedBoundsInRoot()
            assertTrue(live.top>=cloud.bottom)
            assertEquals(((cloud.left+cloud.right)/2).value,((live.left+record.right)/2).value,1f)
            compose.onNodeWithTag("mode_record").assertIsSelected()
            val before=compose.onNodeWithTag("record").captureToImage().toPixelMap()
            compose.onNodeWithTag("record").performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(400)
            val pressed=compose.onNodeWithTag("record").captureToImage().toPixelMap()
            for((x,y) in listOf(2 to 2,(before.width-3) to 2,2 to (before.height-3),(before.width-3) to (before.height-3))) {
                assertEquals("Organic control must not paint a square press ripple",before[x,y],pressed[x,y])
            }
            compose.onNodeWithTag("record").performTouchInput { up() }
            for(phase in listOf(Phase.CONNECTING,Phase.RECORDING,Phase.FINALIZING,Phase.RESULT)) {
                compose.runOnIdle { state=state.copy(mode=Mode.LIVE,phase=phase) }
                assertEquals(gear,compose.onNodeWithTag("open_settings").getUnclippedBoundsInRoot())
                assertEquals(live,compose.onNodeWithTag("mode_live").getUnclippedBoundsInRoot())
                compose.onNodeWithTag("open_settings").assertContentDescriptionEquals("Settings")
            }
            compose.onNodeWithTag("open_settings").performClick()
            compose.onNodeWithText("Settings").assertExists()
        }
    }
    @Test fun unconfirmedInsertionAndDefiniteNetworkFailureNeverInterceptTheNextBubbleTap() {
        val base=instrumentation.targetContext
        var root: View?=null
        val manager=object : WindowManager by base.getSystemService(WindowManager::class.java) {
            override fun addView(view: View,params: ViewGroup.LayoutParams) {root=view}
            override fun updateViewLayout(view: View,params: ViewGroup.LayoutParams) {root=view}
            override fun removeView(view: View)=Unit
        }
        val context=object : ContextWrapper(base) {
            override fun getSystemService(name: String):Any?=if(name==Context.WINDOW_SERVICE)manager else super.getSystemService(name)
        }
        instrumentation.runOnMainSync {
            val feedback=FloatingFeedback(); var starts=0; var recoveries=0
            lateinit var window:FloatingWindow
            fun render()=window.render(900,false,feedback.text,feedback.message?:context.getString(R.string.floating_start),false,feedback.text.isNotBlank(),false,null,error=feedback.error)
            window=FloatingWindow(context,{starts++;feedback.resetSignal();render()},{},{},{},{},recover={recoveries++})
            try {
                feedback.complete(InsertionResult.UNCONFIRMED,"Possibly inserted","Copy required","Unconfirmed")
                render(); assertEquals("Possibly inserted",feedback.text); assertNull(feedback.message); assertFalse(feedback.error)
                val view=checkNotNull(root)
                view.findViewById<View>(R.id.floating_start).performClick(); assertEquals(1,starts)
                feedback.offer("Definitely unsent","Network failure",error=true); render()
                assertEquals(View.GONE,view.findViewById<View>(R.id.floating_status).visibility)
                assertEquals(View.GONE,view.findViewById<View>(R.id.floating_preview).visibility)
                view.findViewById<View>(R.id.floating_start).performClick(); assertEquals(2,starts)
                assertTrue(feedback.text.endsWith("Definitely unsent")); assertFalse(feedback.error)
                view.findViewById<View>(R.id.floating_start).performLongClick(); assertEquals(1,recoveries)
                assertEquals(2,starts)
            } finally {window.hide()}
        }
    }
    @android.annotation.SuppressLint("UseKtx")
    @Test fun recordIsDefaultAndAnExplicitLiveChoiceSurvivesReload() {
        val context=instrumentation.targetContext
        val prefs=context.getSharedPreferences("dictation_mode",Context.MODE_PRIVATE)
        val previous=prefs.getString("explicit_mode",null)
        try {
            prefs.edit().clear().commit()
            assertEquals(Mode.RECORD,ModePreferences.load(context))
            ModePreferences.save(context,Mode.LIVE)
            assertEquals(Mode.LIVE,ModePreferences.load(context))
            ModePreferences.save(context,Mode.RECORD)
            assertEquals(Mode.RECORD,ModePreferences.load(context))
        } finally {
            if(previous==null)prefs.edit().clear().commit() else prefs.edit().putString("explicit_mode",previous).commit()
        }
    }
}
