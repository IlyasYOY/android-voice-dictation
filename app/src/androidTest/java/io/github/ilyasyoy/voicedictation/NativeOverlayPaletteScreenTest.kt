package io.github.ilyasyoy.voicedictation

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.floating.FloatingWindow
import io.github.ilyasyoy.voicedictation.ui.design.*
import org.junit.*
import org.junit.Assert.*

/** Actual FloatingWindow view in an Activity-owned panel above a real IME.
 * Synthetic visual states only: no accessibility-service, microphone or HTTP claims.
 */
class NativeOverlayPaletteScreenTest {
    @get:Rule val setup: org.junit.rules.TestRule = ExistingFeatureSetupRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
    }
    @Test fun sharedNativeOverlayShowsFourPalettesAboveKeyboardOnBothSurfaces() {
        shell("mkdir -p /sdcard/Download/vivid-current")
        try {
            shell("settings put global animator_duration_scale 0")
            shell("settings put secure show_ime_with_hard_keyboard 1")
            for (theme in listOf("no", "yes")) {
                shell("cmd uimode night $theme")
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    lateinit var editor: EditText
                    lateinit var window: FloatingWindow
                    lateinit var root: LinearLayout
                    var overlay: View?=null
                    scenario.onActivity { activity ->
                        val dark=theme=="yes"
                        val ink=if(dark) SoftCurrentPalette.NightText else SoftCurrentPalette.Plum
                        root=LinearLayout(activity).apply {
                            orientation=LinearLayout.VERTICAL
                            setPadding(16,32,16,16)
                            setBackgroundColor(if(dark)SoftCurrentPalette.Night else SoftCurrentPalette.Paper)
                            addView(TextView(activity).apply {
                                text="VISUAL TEST · shared native overlay\nSynthetic states · no microphone / API"
                                textSize=14f; setTextColor(ink)
                            })
                        }
                        editor=EditText(activity).apply { setText("Harmless test editor"); setTextColor(ink); textSize=18f }
                        root.addView(editor,LinearLayout.LayoutParams(-1,80))
                        activity.setContentView(root)
                        val actual=activity.getSystemService(WindowManager::class.java)
                        val manager=object : WindowManager by actual {
                            fun panel(params: ViewGroup.LayoutParams): WindowManager.LayoutParams =
                                WindowManager.LayoutParams().apply {
                                    copyFrom(params as WindowManager.LayoutParams)
                                    type=WindowManager.LayoutParams.TYPE_APPLICATION_PANEL
                                    token=activity.window.decorView.windowToken
                                    // Activity panels use the host's logical coordinates; the
                                    // production accessibility overlay uses screen coordinates.
                                    val visible=Rect();activity.window.decorView.getWindowVisibleDisplayFrame(visible)
                                    x=(activity.window.decorView.width-width-16).coerceAtLeast(0)
                                    y=(visible.bottom-height-8).coerceAtLeast(0)
                                }
                            override fun addView(view: View, params: ViewGroup.LayoutParams) {
                                overlay=view; actual.addView(view,panel(params))
                            }
                            override fun updateViewLayout(view: View, params: ViewGroup.LayoutParams) = actual.updateViewLayout(view,panel(params))
                        }
                        val context=object : ContextWrapper(activity) {
                            override fun getSystemService(name: String): Any? = if(name==Context.WINDOW_SERVICE)manager else super.getSystemService(name)
                        }
                        window=FloatingWindow(context,{}, {}, {}, {}, {})
                        editor.requestFocus()
                        editor.post { activity.getSystemService(InputMethodManager::class.java).showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT) }
                    }
                    var imeTop: Int?=null
                    val deadline=SystemClock.elapsedRealtime()+20_000
                    while(imeTop==null && SystemClock.elapsedRealtime()<deadline) {
                        scenario.onActivity { activity ->
                            val bounds=Rect();editor.getWindowVisibleDisplayFrame(bounds)
                            if(bounds.bottom<activity.window.decorView.height-80)imeTop=bounds.bottom
                        }
                        if(imeTop==null)SystemClock.sleep(100)
                    }
                    assertNotNull("A real IME must be visible",imeTop)
                    try {
                        for ((name,visual) in listOf("idle" to CurrentVisual.IDLE,"recording" to CurrentVisual.RECORDING,
                            "processing" to CurrentVisual.PROCESSING,"error" to CurrentVisual.ERROR)) {
                            val drawn=java.util.concurrent.CountDownLatch(1)
                            scenario.onActivity { activity ->
                                val message = activity.getString(when (visual) {
                                    CurrentVisual.PROCESSING -> R.string.text_processing
                                    CurrentVisual.ERROR -> R.string.text_processing_failed
                                    else -> R.string.floating_start
                                })
                                window.render(checkNotNull(imeTop),false,"",message,
                                    visual==CurrentVisual.RECORDING || visual==CurrentVisual.PROCESSING,false,false,null,
                                    processing=visual==CurrentVisual.PROCESSING,error=visual==CurrentVisual.ERROR)
                                val id=if(visual==CurrentVisual.RECORDING)R.id.floating_stop else R.id.floating_start
                                val image=checkNotNull(overlay).findViewById<ImageButton>(id)
                                assertEquals(visual,(image.drawable as CurrentDrawable).visual)
                                val view=checkNotNull(overlay)
                                val observer=view.viewTreeObserver
                                observer.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
                                    override fun onDraw() {
                                        drawn.countDown()
                                        view.post { if(observer.isAlive)observer.removeOnDrawListener(this) }
                                    }
                                })
                                view.invalidate()
                            }
                            assertTrue("Native panel must draw before screenshot",drawn.await(10,java.util.concurrent.TimeUnit.SECONDS))
                            SystemClock.sleep(300)
                            shell("screencap -p /sdcard/Download/vivid-current/native-overlay-$name-$theme.png")
                            run {
                                val bytes=shell("cat /sdcard/Download/vivid-current/native-overlay-$name-$theme.png")
                                val image=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size)
                                try {
                                    val hsv=FloatArray(3);var colored=0
                                    for(y in 0 until image.height step 2)for(x in 0 until image.width step 2) {
                                        android.graphics.Color.colorToHSV(image.getPixel(x,y),hsv)
                                        val hue=when(visual) {
                                            CurrentVisual.IDLE -> hsv[1]<.15f && hsv[2] in .4f.. .9f &&
                                                x>image.width*.7 && y in (checkNotNull(imeTop)-100) until checkNotNull(imeTop)
                                            CurrentVisual.RECORDING -> hsv[0] in 260f..290f
                                            CurrentVisual.PROCESSING -> hsv[0] in 30f..60f
                                            else -> hsv[0]>345f || hsv[0]<10f
                                        }
                                        if(hue && (visual==CurrentVisual.IDLE || hsv[1]>.35f && hsv[2]>.35f))colored++
                                    }
                                    assertTrue("$name pigment must be visible in the actual window screenshot",colored>20)
                                } finally {image.recycle()}
                            }
                        }
                    } finally { scenario.onActivity { window.hide() } }
                }
            }
        } finally {shell("settings put global animator_duration_scale 1");shell("cmd uimode night no")}
    }
}
