package io.github.ilyasyoy.voicedictation.ui.design

import android.graphics.*
import android.graphics.drawable.Drawable
import kotlin.math.*

internal enum class CurrentVisual { IDLE, RECORDING, PROCESSING, ERROR }

/** The same organic membrane at home and above the keyboard; decorative, not a volume meter. */
internal class CurrentDrawable : Drawable() {
    var visual = CurrentVisual.IDLE
        set(value) { field = value; invalidateSelf() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var opacity = 255
    override fun draw(canvas: Canvas) {
        val w = min(bounds.width(), bounds.height()).toFloat()
        val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
        paint.style = Paint.Style.FILL
        SoftCurrentPalette.Layers.forEachIndexed { layer, color ->
            path.reset()
            val radius = w * (.425f - layer * .019f)
            for (i in 0..120) {
                val angle = i * 2.0 * PI / 120
                val r = radius * (1 + .09 * sin(3 * angle + .8 + layer * .2) + .045 * cos(2 * angle - .6))
                val px = x + r * cos(angle) + sin(layer * .7) * w * .008
                val py = y + r * .96 * sin(angle) + cos(layer * .5) * w * .008
                if (i == 0) path.moveTo(px.toFloat(), py.toFloat()) else path.lineTo(px.toFloat(), py.toFloat())
            }
            path.close(); paint.color = color; paint.alpha = opacity; canvas.drawPath(path, paint)
        }
        paint.color = SoftCurrentPalette.Accent; paint.alpha = opacity
        paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = max(1f, w * .006f)
        if (visual != CurrentVisual.ERROR) repeat(4) { line ->
            path.reset()
            for (i in 0..48) {
                val t = i / 48f
                val px = x + (t - .5f) * w * .50f
                val py = y + sin(t * PI * 2 + line * .48) * sin(t * PI) * w * .105f
                if (i == 0) path.moveTo(px, py.toFloat()) else path.lineTo(px, py.toFloat())
            }
            canvas.drawPath(path, paint)
        }
        when (visual) {
            CurrentVisual.RECORDING -> {
                paint.strokeWidth = w * .018f
                canvas.drawCircle(x, y, w * .468f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x + w * .33f, y - w * .34f, w * .04f, paint)
            }
            CurrentVisual.PROCESSING -> {
                paint.strokeWidth = w * .026f
                canvas.drawArc(x-w*.46f, y-w*.46f, x+w*.46f, y+w*.46f, -90f, 265f, false, paint)
            }
            CurrentVisual.ERROR -> {
                paint.color = SoftCurrentPalette.Plum; paint.strokeWidth = w * .035f
                canvas.drawLine(x, y-w*.12f, x, y+w*.045f, paint)
                paint.style = Paint.Style.FILL; canvas.drawCircle(x, y+w*.12f, w*.018f, paint)
            }
            else -> Unit
        }
    }
    override fun setAlpha(alpha: Int) { opacity = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
