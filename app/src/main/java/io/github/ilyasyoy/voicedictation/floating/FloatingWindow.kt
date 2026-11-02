package io.github.ilyasyoy.voicedictation.floating

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.ilyasyoy.voicedictation.R
import io.github.ilyasyoy.voicedictation.ui.design.*
import kotlin.math.abs

internal class FloatingWindow(
    private val context: Context, private val iconClick: () -> Unit,
    private val cancel: () -> Unit, copy: () -> Unit, close: () -> Unit, private val setup: () -> Unit, private val discard: () -> Unit = {}, private val recover: () -> Unit = {},
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    private val dark get() = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val ink get() = if (dark) SoftCurrentPalette.NightText else SoftCurrentPalette.Plum
    private val paper get() = if (dark) SoftCurrentPalette.NightSurface else SoftCurrentPalette.Paper
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val root = ScrollView(context).apply {
        isFillViewport = false; clipToOutline = true
        addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private val mark = CurrentDrawable()
    private val icon = object : ImageButton(context) {
        override fun performClick(): Boolean = super.performClick()
        override fun onAttachedToWindow() { super.onAttachedToWindow(); mark.start() }
        override fun onDetachedFromWindow() { mark.stop(); super.onDetachedFromWindow() }
        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (isAttachedToWindow && visibility == VISIBLE) mark.start() else mark.stop()
        }
    }.apply {
        id = R.id.floating_start; setImageDrawable(mark); setPadding(dp(3),dp(3),dp(3),dp(3))
        setBackgroundColor(android.graphics.Color.TRANSPARENT); setOnClickListener { controlsOpen = false; iconClick() }
        setOnLongClickListener { when { active -> { showControls(); true }; hasRecovery -> { recover(); true }; requiresSetup -> { setup(); true }; else -> false } }
    }
    private val heading = TextView(context).apply { textSize = 12f; setTextColor(ink) }
    private val status = TextView(context).apply { id = R.id.floating_status; textSize = 14f; setTextColor(ink) }
    private val preview = TextView(context).apply { id = R.id.floating_preview; textSize = 17f; setTextColor(ink) }
    private val controls = LinearLayout(context)
    private fun button(label: Int, id: Int, action: () -> Unit) = Button(context).apply {
        this.id = id; text = context.getString(label); setTextColor(ink); isAllCaps = false; minimumHeight = dp(48)
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (dark) SoftCurrentPalette.Night else 0xFFE7DDF2.toInt())
        setOnClickListener { action() }
    }
    private val copyButton = button(R.string.copy, R.id.floating_copy, copy)
    private val closeButton = button(R.string.current_dismiss, R.id.floating_close, close)
    private val cancelButton = button(R.string.floating_cancel, R.id.floating_cancel) { controlsOpen = false; cancel() }
    private val discardButton = button(R.string.current_discard, R.id.floating_discard, discard)
    private val setupButton = button(R.string.floating_open_setup, R.id.floating_open_setup, setup)
    @SuppressLint("RtlHardcoded") // rawX and persisted anchors use physical display-left pixels, also in RTL.
    private val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT }
    private var attached = false
    private val position = FloatingPositionState(FloatingPositionPreferences(context))
    private var dragging = false
    private var imeTop = 0
    private var active = false
    private var hasRecovery = false
    private var requiresSetup = false
    private var held = false
    private var cancelAction = -1
    private var recoverAction = -1
    private var controlsOpen = false
    private var panel = false
    private var lastNotice: String? = null
    private val hold = Runnable { held = true; icon.performLongClick() }
    private fun showControls() {
        controlsOpen = !controlsOpen
        layoutControls()
        if (clamp()) update() else hide()
    }
    private fun layoutControls() {
        val menu = active && controlsOpen
        root.background = if (panel || controlsOpen) GradientDrawable().apply { setColor(paper); cornerRadius = dp(32).toFloat(); setStroke(dp(1),SoftCurrentPalette.Outline) } else null
        root.elevation = if (panel || controlsOpen) dp(3).toFloat() else 0f
        params.width = when { panel -> minOf(dp(280),context.resources.displayMetrics.widthPixels-dp(16)); menu -> dp(176); else -> dp(64) }
        root.setPadding(if(panel || menu)dp(12) else 0, if(panel || menu)dp(8) else 0, if(panel || menu)dp(12) else 0, if(panel || menu)dp(12) else 0)
        heading.text = if(menu) context.getString(R.string.current_controls) else lastNotice ?: context.getString(R.string.app_name)
        heading.visibility = if(panel || menu)View.VISIBLE else View.GONE
        cancelButton.visibility = if(menu)View.VISIBLE else View.GONE
    }
    @SuppressLint("ClickableViewAccessibility") // Explicit click/long-click semantics and accessible Cancel action.
    private fun initialize() {
        content.addView(icon, LinearLayout.LayoutParams(dp(64), dp(64)))
        content.addView(heading); content.addView(status); content.addView(preview)
        controls.addView(copyButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(closeButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        content.addView(controls); content.addView(cancelButton); content.addView(setupButton); content.addView(discardButton)
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragged = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        icon.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; dragging = false; dragged = false; held = false
                    if (active || hasRecovery || requiresSetup) handler.postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX-downX; val dy = event.rawY-downY
                    if (abs(dx)+abs(dy)>slop) { dragged = true; handler.removeCallbacks(hold) }
                    if (dragged && !held && attached) {
                        dragging = true; params.x=startX+dx.toInt(); params.y=startY+dy.toInt()
                        if (clamp(applyPreference = false)) update() else hide()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(hold)
                    if (dragging && dragged && !held && attached) safeArea()?.let { area ->
                        val movedX = abs(event.rawX - downX) > slop
                        val movedY = abs(event.rawY - downY) > slop
                        val diagonal = !movedX && !movedY && abs(event.rawX-downX)+abs(event.rawY-downY)>slop
                        position.dragged(FloatingPoint(params.x + (params.width - dp(64)) / 2, params.y + root.paddingTop), area,
                            horizontalMoved = movedX || diagonal, verticalMoved = movedY || diagonal)
                    }
                    dragging = false
                    if (!dragged && !held && attached) view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(hold); dragging = false
                    if (attached) { if (clamp()) update() else hide() }
                    true
                }
                else -> false
            }
        }
    }
    init {
        initialize()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            root.post {
                if (attached) {
                    val old = listOf(params.x, params.y, params.width, params.height)
                    if (!clamp()) hide()
                    else if (old != listOf(params.x, params.y, params.width, params.height)) update()
                }
            }
            insets
        }
    }
    fun render(imeTop: Int, expanded: Boolean, text: String, message: String, recording: Boolean, canCopy: Boolean, needsSetup: Boolean, notice: String?, processing: Boolean = false, error: Boolean = false, connecting: Boolean = false, historyFull: Boolean = false) {
        this.imeTop = imeTop; active = recording; hasRecovery = canCopy || historyFull; requiresSetup = needsSetup
        // The service survives configuration changes; every reused view must follow the current palette.
        for (view in listOf(heading, status, preview, copyButton, closeButton, setupButton, cancelButton, discardButton)) view.setTextColor(ink)
        val buttonColor = android.content.res.ColorStateList.valueOf(if (dark) SoftCurrentPalette.Night else 0xFFE7DDF2.toInt())
        for (button in listOf(copyButton, closeButton, setupButton, cancelButton, discardButton)) button.backgroundTintList = buttonColor
        val opening = expanded && !active && !panel
        panel = expanded && !active
        if (opening || !panel) root.scrollTo(0,0)
        lastNotice = notice
        if (!active) controlsOpen = false
        layoutControls()
        val recoverable = !active && (canCopy || needsSetup || error || message != context.getString(R.string.floating_start))
        mark.visual = when { processing && connecting -> CurrentVisual.CONNECTING; processing -> CurrentVisual.PROCESSING; active -> CurrentVisual.RECORDING; needsSetup || error -> CurrentVisual.ERROR; historyFull -> CurrentVisual.ATTENTION; recoverable -> CurrentVisual.ATTENTION; else -> CurrentVisual.IDLE }
        icon.isLongClickable = active || canCopy || needsSetup || historyFull
        icon.id = if(active && !processing) R.id.floating_stop else R.id.floating_start
        icon.contentDescription = context.getString(if (historyFull && !active) R.string.history_full_bubble else if(!active)R.string.floating_start else if(processing)R.string.current_hold_hint else R.string.current_bubble_hint)
        ViewCompat.setStateDescription(icon, message)
        if (active && cancelAction == -1) cancelAction = ViewCompat.addAccessibilityAction(icon, context.getString(R.string.floating_cancel)) { _, _ -> cancel(); true }
        if (!active && cancelAction != -1) { ViewCompat.removeAccessibilityAction(icon,cancelAction); cancelAction = -1 }
        if (!active && (canCopy || needsSetup) && recoverAction == -1) recoverAction = ViewCompat.addAccessibilityAction(icon,context.getString(R.string.current_recover)) { _, _ ->
            if (hasRecovery) recover() else setup(); true
        }
        if ((active || (!canCopy && !needsSetup)) && recoverAction != -1) { ViewCompat.removeAccessibilityAction(icon,recoverAction); recoverAction = -1 }
        status.text = message; status.visibility = if(panel)View.VISIBLE else View.GONE
        preview.text = text; preview.visibility = if(panel && text.isNotEmpty())View.VISIBLE else View.GONE
        controls.visibility = if(panel)View.VISIBLE else View.GONE
        copyButton.visibility = if(panel && canCopy)View.VISIBLE else View.GONE
        closeButton.visibility = if(panel)View.VISIBLE else View.GONE
        setupButton.visibility = if(panel && needsSetup)View.VISIBLE else View.GONE
        discardButton.visibility = if(panel)View.VISIBLE else View.GONE
        if (!clamp()) { hide(); return }
        if(!attached) { wm.addView(root,params); attached=true; ViewCompat.requestApplyInsets(root) } else update()
        mark.start()
    }
    private fun safeArea(): FloatingArea? {
        val screen: android.graphics.Rect
        val insets: WindowInsetsCompat?
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = wm.maximumWindowMetrics
            screen = metrics.bounds
            insets = WindowInsetsCompat.toWindowInsetsCompat(metrics.windowInsets)
        } else {
            val legacy = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(legacy)
            screen = android.graphics.Rect(0, 0, legacy.widthPixels, legacy.heightPixels)
            insets = ViewCompat.getRootWindowInsets(root)
        }
        val bars = insets?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        return FloatingArea.create(screen.width(), screen.height(), imeTop, context.resources.displayMetrics.density,
            bars?.left ?: 0, bars?.top ?: 0, bars?.right ?: 0, bars?.bottom ?: 0)
    }
    private fun clamp(applyPreference: Boolean = !dragging): Boolean {
        val area = safeArea() ?: return false
        val available = area.height
        val minimumWidth = area.diameter + root.paddingLeft + root.paddingRight
        if (area.width < minimumWidth) return false
        params.width = params.width.coerceIn(minimumWidth, area.width)
        // Measure real wrapped text/buttons, then give WM an exact bounded height. The ScrollView
        // exposes overflow intentionally; no fixed panel estimate can push actions under the IME.
        root.measure(View.MeasureSpec.makeMeasureSpec(params.width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(available,View.MeasureSpec.AT_MOST))
        params.height = root.measuredHeight.coerceAtMost(available)
        if (params.height < area.diameter + root.paddingTop + root.paddingBottom) return false
        if (applyPreference) {
            val anchor = position.place(area)
            params.x = anchor.x - (params.width - dp(64)) / 2
            params.y = anchor.y - root.paddingTop
        }
        params.x=params.x.coerceIn(area.left,area.right-params.width)
        params.y=params.y.coerceIn(area.top,maxOf(area.top,area.bottom-params.height))
        return true
    }
    private fun update() { if(attached) runCatching { wm.updateViewLayout(root,params) } }
    fun hide() { mark.stop(); handler.removeCallbacks(hold); controlsOpen=false; dragging=false; if(attached)runCatching { wm.removeView(root) }; attached=false }
}
