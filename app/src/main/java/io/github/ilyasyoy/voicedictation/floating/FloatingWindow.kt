package io.github.ilyasyoy.voicedictation.floating

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.core.view.ViewCompat
import io.github.ilyasyoy.voicedictation.R
import io.github.ilyasyoy.voicedictation.ui.design.*
import kotlin.math.abs

internal class FloatingWindow(
    private val context: Context, private val iconClick: () -> Unit,
    private val cancel: () -> Unit, copy: () -> Unit, close: () -> Unit, setup: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    private val dark get() = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val ink get() = if (dark) SoftCurrentPalette.NightText else SoftCurrentPalette.Plum
    private val paper get() = if (dark) SoftCurrentPalette.NightSurface else SoftCurrentPalette.Paper
    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val mark = CurrentDrawable()
    private val icon = object : ImageButton(context) { override fun performClick(): Boolean = super.performClick() }.apply {
        id = R.id.floating_start; setImageDrawable(mark); setPadding(dp(3),dp(3),dp(3),dp(3))
        setBackgroundColor(android.graphics.Color.TRANSPARENT); setOnClickListener { controlsOpen = false; iconClick() }
        setOnLongClickListener { if (active) { showControls(); true } else false }
    }
    private val heading = TextView(context).apply { textSize = 12f; setTextColor(ink) }
    private val status = TextView(context).apply { id = R.id.floating_status; textSize = 14f; setTextColor(ink) }
    private val preview = TextView(context).apply { id = R.id.floating_preview; textSize = 17f; setTextColor(ink); maxLines = 3 }
    private val controls = LinearLayout(context)
    private fun button(label: Int, id: Int, action: () -> Unit) = Button(context).apply {
        this.id = id; text = context.getString(label); setTextColor(ink); isAllCaps = false
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (dark) SoftCurrentPalette.Night else 0xFFE7DDF2.toInt())
        setOnClickListener { action() }
    }
    private val copyButton = button(R.string.copy, R.id.floating_copy, copy)
    private val closeButton = button(R.string.floating_close, R.id.floating_close, close)
    private val cancelButton = button(R.string.floating_cancel, R.id.floating_cancel) { controlsOpen = false; cancel() }
    private val setupButton = button(R.string.floating_open_setup, R.id.floating_open_setup, setup)
    private val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
    private var attached = false
    private var moved = false
    private var imeTop = 0
    private var active = false
    private var wasActive = false
    private var held = false
    private var cancelAction = -1
    private var controlsOpen = false
    private var panel = false
    private var lastNotice: String? = null
    private var wasProcessing = false
    private val hold = Runnable { held = true; icon.performLongClick() }
    private fun showControls() {
        controlsOpen = !controlsOpen
        layoutControls()
        clamp(); update()
    }
    private fun layoutControls() {
        val menu = active && controlsOpen
        params.width = when { panel -> minOf(dp(280),context.resources.displayMetrics.widthPixels-dp(16)); menu -> dp(176); else -> dp(64) }
        root.setPadding(if(panel || menu)dp(12) else 0, if(panel || menu)dp(8) else 0, if(panel || menu)dp(12) else 0, if(panel || menu)dp(12) else 0)
        heading.text = if(menu) context.getString(R.string.current_controls) else lastNotice ?: context.getString(R.string.app_name)
        heading.visibility = if(panel || menu)View.VISIBLE else View.GONE
        cancelButton.visibility = if(menu)View.VISIBLE else View.GONE
    }
    @SuppressLint("ClickableViewAccessibility") // Explicit click/long-click semantics and accessible Cancel action.
    private fun initialize() {
        root.addView(icon, LinearLayout.LayoutParams(dp(64), dp(64)))
        root.addView(heading); root.addView(status); root.addView(preview)
        controls.addView(copyButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(closeButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(controls); root.addView(cancelButton); root.addView(setupButton)
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        icon.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; dragging = false; held = false
                    if (active) handler.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX-downX; val dy = event.rawY-downY
                    if (abs(dx)+abs(dy)>slop) { dragging = true; handler.removeCallbacks(hold) }
                    if (dragging && !held) { moved = true; params.x=startX+dx.toInt(); params.y=startY+dy.toInt(); clamp(); update() }
                    true
                }
                MotionEvent.ACTION_UP -> { handler.removeCallbacks(hold); if (!dragging && !held) view.performClick(); true }
                MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(hold); true }
                else -> false
            }
        }
    }
    init { initialize() }
    fun render(imeTop: Int, expanded: Boolean, text: String, message: String, recording: Boolean, canCopy: Boolean, needsSetup: Boolean, notice: String?, processing: Boolean = false, error: Boolean = false) {
        this.imeTop = imeTop; active = recording
        panel = expanded && !active
        lastNotice = notice
        if (!active) controlsOpen = false
        layoutControls()
        root.background = GradientDrawable().apply { setColor(paper); cornerRadius = dp(32).toFloat(); setStroke(dp(1),SoftCurrentPalette.Outline) }
        root.elevation = dp(3).toFloat()
        mark.visual = when { processing -> CurrentVisual.PROCESSING; active -> CurrentVisual.RECORDING; needsSetup || error -> CurrentVisual.ERROR; else -> CurrentVisual.IDLE }
        icon.isLongClickable = active
        icon.id = if(active && !processing) R.id.floating_stop else R.id.floating_start
        icon.contentDescription = context.getString(if(!active)R.string.floating_start else if(processing)R.string.current_hold_hint else R.string.current_bubble_hint)
        ViewCompat.setStateDescription(icon, message)
        icon.tooltipText = icon.contentDescription
        if (active && cancelAction == -1) cancelAction = ViewCompat.addAccessibilityAction(icon, context.getString(R.string.floating_cancel)) { _, _ -> cancel(); true }
        if (!active && cancelAction != -1) { ViewCompat.removeAccessibilityAction(icon,cancelAction); cancelAction = -1 }
        if (active && (!wasActive || (wasProcessing && !processing))) Toast.makeText(context,if(processing)R.string.current_hold_hint else R.string.current_bubble_hint,Toast.LENGTH_LONG).show()
        wasActive = active; wasProcessing = processing
        status.text = message; status.visibility = if(panel)View.VISIBLE else View.GONE
        preview.text = text; preview.visibility = if(panel && text.isNotEmpty())View.VISIBLE else View.GONE
        controls.visibility = if(panel)View.VISIBLE else View.GONE
        copyButton.visibility = if(panel && canCopy)View.VISIBLE else View.GONE
        closeButton.visibility = if(panel)View.VISIBLE else View.GONE
        setupButton.visibility = if(panel && needsSetup)View.VISIBLE else View.GONE
        if(!moved || !attached) { params.x=context.resources.displayMetrics.widthPixels-params.width-dp(8); params.y=imeTop-if(panel)dp(260) else if(controlsOpen)dp(176) else dp(80) }
        clamp()
        if(!attached) { wm.addView(root,params); attached=true } else update()
    }
    private fun clamp() {
        params.x=params.x.coerceIn(0,maxOf(0,context.resources.displayMetrics.widthPixels-maxOf(params.width,dp(64))))
        params.y=params.y.coerceIn(dp(24),maxOf(dp(24),imeTop-dp(if(panel)260 else if(controlsOpen)176 else 64)))
    }
    private fun update() { if(attached) runCatching { wm.updateViewLayout(root,params) } }
    fun hide() { handler.removeCallbacks(hold); controlsOpen=false; if(attached)runCatching { wm.removeView(root) }; attached=false }
}
