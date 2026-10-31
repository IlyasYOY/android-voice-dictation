package io.github.ilyasyoy.voicedictation.ui.design

import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import kotlin.math.*

internal enum class CurrentVisual { IDLE, CONNECTING, RECORDING, PROCESSING, ATTENTION, ERROR }

/** Shared continuous vector motion. It indicates state, never claims to measure voice amplitude. */
internal class CurrentDrawable : Drawable(), Animatable {
    var visual = CurrentVisual.IDLE
        set(value) { if (field != value) {
            field = value
            if (!ValueAnimator.areAnimatorsEnabled()) {
                tint=if(value==CurrentVisual.RECORDING)1f else if(value==CurrentVisual.PROCESSING).65f else 0f
                attention=if(value in setOf(CurrentVisual.ATTENTION,CurrentVisual.ERROR))1f else 0f
            }
            invalidateSelf()
        } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var opacity = 255
    private var running = false
    private var lastFrame = 0L
    private var phase = 0.0
    private var energy = .12f
    private var tint = 0f
    private var attention = 0f
    private val xs=FloatArray(72)
    private val ys=FloatArray(72)
    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val dt = ((now - lastFrame).coerceIn(0, 64) / 1000f)
            lastFrame = now
            val enabled = ValueAnimator.areAnimatorsEnabled()
            if (enabled) {
                val target = when (visual) {
                    CurrentVisual.RECORDING -> 1f
                    CurrentVisual.PROCESSING -> .48f
                    CurrentVisual.CONNECTING -> .28f
                    else -> .12f
                }
                // Blend velocity and pigment as well as the outline: state changes never jump to a new frame.
                energy += (target - energy) * (1 - exp(-dt * 4))
                val pigment = when (visual) { CurrentVisual.RECORDING -> 1f; CurrentVisual.PROCESSING -> .65f; else -> 0f }
                tint += (pigment - tint) * (1 - exp(-dt * 4))
                val warm = if (visual in setOf(CurrentVisual.ATTENTION,CurrentVisual.ERROR)) 1f else 0f
                attention += (warm - attention) * (1 - exp(-dt * 4))
                phase += dt * (.38 + energy * 1.3)
            } else {
                // Reduced motion still communicates the actual state through a static, distinct palette.
                tint = if (visual == CurrentVisual.RECORDING) 1f else if (visual == CurrentVisual.PROCESSING) .65f else 0f
                attention = if (visual in setOf(CurrentVisual.ATTENTION,CurrentVisual.ERROR)) 1f else 0f
            }
            invalidateSelf()
            if (enabled) scheduleSelf(this, now + 16) else stop()
        }
    }
    override fun start() {
        if (running || !ValueAnimator.areAnimatorsEnabled()) return
        running = true; lastFrame = SystemClock.uptimeMillis(); scheduleSelf(frame, lastFrame)
    }
    override fun stop() { running = false; unscheduleSelf(frame) }
    override fun isRunning() = running
    private fun blend(a: Int, b: Int, amount: Float): Int {
        fun channel(shift: Int) = (((a ushr shift) and 255) * (1-amount) + ((b ushr shift) and 255) * amount).toInt()
        return Color.argb(channel(24),channel(16),channel(8),channel(0))
    }
    override fun draw(canvas: Canvas) {
        val w = min(bounds.width(), bounds.height()).toFloat()
        val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
        paint.style = Paint.Style.FILL
        SoftCurrentPalette.Layers.forEachIndexed { layer, color ->
            val radius = w * (.415f - layer * .019f)
            for (i in 0 until 72) {
                val angle = i * 2.0 * PI / 72
                val r = radius * (1 + (.065 + energy*.025) * sin(3*angle + phase*.75 + layer*.18) + .045*cos(2*angle - phase*.48))
                xs[i]=(x+r*cos(angle)+sin(phase+layer*.7)*w*.007).toFloat()
                ys[i]=(y+r*.97*sin(angle)+cos(phase*.65+layer*.5)*w*.007).toFloat()
            }
            path.reset()
            path.moveTo((xs.last()+xs.first())/2,(ys.last()+ys.first())/2)
            for (i in xs.indices) {
                val next=(i+1)%xs.size
                path.quadTo(xs[i],ys[i],(xs[i]+xs[next])/2,(ys[i]+ys[next])/2)
            }
            path.close()
            val neutral = blend(color,0xFFDAD7E2.toInt(),.60f)
            val base = blend(blend(neutral,color,tint),0xFFD6B89F.toInt(),attention*.45f)
            val glow = blend(base,SoftCurrentPalette.Surface,.16f)
            val travel = (sin(phase*.6)*w*.15f).toFloat()
            paint.shader = LinearGradient(x-w*.45f+travel,y-w*.35f,x+w*.45f+travel,y+w*.4f, intArrayOf(base,glow,base),floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
            paint.alpha = opacity
            canvas.drawPath(path,paint)
        }
        paint.shader = null
        paint.color = if (visual == CurrentVisual.ERROR || visual == CurrentVisual.ATTENTION) SoftCurrentPalette.Plum else blend(0xFF9690A4.toInt(),SoftCurrentPalette.Accent,tint)
        paint.alpha = opacity; paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = max(1f,w*.006f)
        repeat(4) { line ->
            path.reset()
            for (i in 0..64) {
                val t=i/64f
                val px=x+(t-.5f)*w*.48f
                val py=y+sin(t*PI*2+line*.48+phase*.45)*sin(t*PI)*w*(.075f+energy*.035f)
                if (i==0) path.moveTo(px,py.toFloat()) else path.lineTo(px,py.toFloat())
            }
            canvas.drawPath(path,paint)
        }
    }
    override fun setAlpha(alpha: Int) { opacity=alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter=colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
