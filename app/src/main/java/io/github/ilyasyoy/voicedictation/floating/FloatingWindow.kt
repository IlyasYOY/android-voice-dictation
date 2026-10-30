package io.github.ilyasyoy.voicedictation.floating

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import io.github.ilyasyoy.voicedictation.R
import kotlin.math.abs

internal class FloatingWindow(
    private val context: Context,
    private val iconClick: () -> Unit,
    stop: () -> Unit, cancel: () -> Unit, copy: () -> Unit, close: () -> Unit, setup: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
    private val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
    private val icon = object : ImageButton(context) {
        override fun performClick(): Boolean = super.performClick()
    }.apply {
        id = R.id.floating_start; contentDescription = context.getString(R.string.floating_start)
        setImageResource(R.drawable.ic_launcher); setPadding(dp(4), dp(4), dp(4), dp(4)); setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { iconClick() }
    }
    private val heading = TextView(context).apply { textSize = 13f; setTextColor(Color.BLACK) }
    private val status = TextView(context).apply { id = R.id.floating_status; textSize = 14f; setTextColor(Color.BLACK) }
    private val preview = TextView(context).apply { id = R.id.floating_preview; textSize = 16f; setTextColor(Color.BLACK); maxLines = 3 }
    private val controls = LinearLayout(context)
    private val stopButton = Button(context).apply { id = R.id.floating_stop; text = context.getString(R.string.stop_live); setOnClickListener { stop() } }
    private val cancelButton = Button(context).apply { id = R.id.floating_cancel; text = context.getString(R.string.floating_cancel); setOnClickListener { cancel() } }
    private val copyButton = Button(context).apply { id = R.id.floating_copy; text = context.getString(R.string.copy); setOnClickListener { copy() } }
    private val closeButton = Button(context).apply { id = R.id.floating_close; text = context.getString(R.string.floating_close); setOnClickListener { close() } }
    private val setupButton = Button(context).apply { id = R.id.floating_open_setup; text = context.getString(R.string.floating_open_setup); setOnClickListener { setup() } }
    private val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SECURE,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }
    private var attached = false
    private var moved = false
    private var imeTop = 0
    @SuppressLint("ClickableViewAccessibility") // Listener calls performClick on ACTION_UP; overridden above for accessibility.
    private fun initialize() {
        row.addView(icon, LinearLayout.LayoutParams(dp(48), dp(48))); row.addView(heading)
        root.addView(row); root.addView(status); root.addView(preview)
        controls.addView(stopButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(cancelButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(copyButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(closeButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(controls); root.addView(setupButton)
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragging = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        icon.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y; dragging = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) + abs(dy) > slop) dragging = true
                    if (dragging) { moved = true; params.x = startX + dx.toInt(); params.y = startY + dy.toInt(); clamp(); update() }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!dragging) view.performClick(); true }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }
    init { initialize() }
    fun render(imeTop: Int, expanded: Boolean, text: String, message: String, recording: Boolean, canCopy: Boolean, needsSetup: Boolean, notice: String?) {
        this.imeTop = imeTop
        params.width = if (expanded) minOf(dp(300), context.resources.displayMetrics.widthPixels - dp(16)) else dp(64)
        root.background = GradientDrawable().apply { setColor(if (recording) Color.rgb(255, 221, 216) else Color.rgb(226, 240, 232)); cornerRadius = dp(if (expanded) 24 else 32).toFloat() }
        heading.text = notice ?: context.getString(R.string.app_name)
        heading.visibility = if (expanded) View.VISIBLE else View.GONE
        status.text = message; status.visibility = if (expanded) View.VISIBLE else View.GONE
        preview.text = text; preview.visibility = if (expanded && text.isNotEmpty()) View.VISIBLE else View.GONE
        controls.visibility = if (expanded) View.VISIBLE else View.GONE
        stopButton.visibility = if (recording) View.VISIBLE else View.GONE
        cancelButton.visibility = if (recording) View.VISIBLE else View.GONE
        copyButton.visibility = if (!recording && canCopy) View.VISIBLE else View.GONE
        closeButton.visibility = if (!recording) View.VISIBLE else View.GONE
        setupButton.visibility = if (expanded && needsSetup) View.VISIBLE else View.GONE
        params.flags = if (notice != null) params.flags and WindowManager.LayoutParams.FLAG_SECURE.inv() else params.flags or WindowManager.LayoutParams.FLAG_SECURE
        if (!moved || !attached) {
            params.x = context.resources.displayMetrics.widthPixels - params.width - dp(8)
            params.y = imeTop - if (expanded) dp(240) else dp(72)
        }
        clamp()
        if (!attached) { wm.addView(root, params); attached = true } else update()
    }
    private fun clamp() {
        params.x = params.x.coerceIn(0, maxOf(0, context.resources.displayMetrics.widthPixels - maxOf(params.width, dp(64))))
        params.y = params.y.coerceIn(dp(24), maxOf(dp(24), imeTop - dp(64)))
    }
    private fun update() { if (attached) runCatching { wm.updateViewLayout(root, params) } }
    fun hide() { if (attached) runCatching { wm.removeView(root) }; attached = false }
}
