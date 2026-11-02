package io.github.ilyasyoy.voicedictation.ui.design

import android.animation.ValueAnimator
import android.graphics.*
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.SystemClock
import kotlin.math.*
import io.github.ilyasyoy.voicedictation.audio.*

internal enum class CurrentVisual { IDLE, CONNECTING, RECORDING, PROCESSING, ATTENTION, ERROR }

/** Shared state palette and PCM-reactive shape. No microphone, network, or PCM ownership here. */
internal class CurrentDrawable : Drawable(), Animatable {
    var visual = CurrentVisual.IDLE
        set(value) { if (field != value) {
            field = value
            if (value != CurrentVisual.RECORDING) { voice = VoiceLevels.Silence; freshness = 0f }
            if (!ValueAnimator.areAnimatorsEnabled()) {
                tint=if(value==CurrentVisual.RECORDING)1f else if(value==CurrentVisual.PROCESSING).65f else 0f
                attention=if(value==CurrentVisual.ERROR)1f else 0f
            }
            invalidateSelf()
        } }
    var levels: VoiceLevelSource = VoiceLevelSource.Silent
    private var voice = VoiceLevels.Silence
    private var freshness = 0f
    private var voicePhase = 0.0
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
            advanceFrame(dt, System.nanoTime(), enabled)
            invalidateSelf()
            if (enabled) scheduleSelf(this, now + 33) else stop()
        }
    }
    /** Explicit clock keeps normalized motion identical and independently verifiable in both hosts. */
    internal fun advanceFrame(dt: Float, nowNanos: Long, motionEnabled: Boolean) {
        if (motionEnabled) {
            val target = when (visual) {
                CurrentVisual.RECORDING -> .12f
                CurrentVisual.PROCESSING -> .48f
                CurrentVisual.CONNECTING -> .28f
                else -> .12f
            }
            // Blend velocity and pigment as well as the outline: state changes never jump to a new frame.
            energy += (target - energy) * (1 - exp(-dt * 4))
            val pigment = when (visual) { CurrentVisual.RECORDING -> 1f; CurrentVisual.PROCESSING -> .65f; else -> 0f }
            tint += (pigment - tint) * (1 - exp(-dt * 4))
            val warm = if (visual == CurrentVisual.ERROR) 1f else 0f
            attention += (warm - attention) * (1 - exp(-dt * 4))
            voice = if (visual == CurrentVisual.RECORDING) levels.current() else VoiceLevels.Silence
            freshness = voice.freshness(nowNanos)
            voicePhase += dt * (voice.loudness.safeUnit() * freshness * 7.0)
            phase += dt * (.28 + energy * 3.4)
        } else {
            voice = VoiceLevels.Silence; freshness = 0f
            // Reduced motion still communicates the actual state through a static, distinct palette.
            tint = if (visual == CurrentVisual.RECORDING) 1f else if (visual == CurrentVisual.PROCESSING) .65f else 0f
            attention = if (visual == CurrentVisual.ERROR) 1f else 0f
        }
    }
    override fun start() {
        if (running || !ValueAnimator.areAnimatorsEnabled()) return
        running = true; lastFrame = SystemClock.uptimeMillis(); scheduleSelf(frame, lastFrame)
    }
    override fun stop() { running = false; voice = VoiceLevels.Silence; freshness = 0f; unscheduleSelf(frame) }
    override fun isRunning() = running
    private fun blend(a: Int, b: Int, amount: Float): Int {
        fun channel(shift: Int) = (((a ushr shift) and 255) * (1-amount) + ((b ushr shift) and 255) * amount).toInt()
        return Color.argb(channel(24),channel(16),channel(8),channel(0))
    }
    override fun draw(canvas: Canvas) {
        if (!ValueAnimator.areAnimatorsEnabled()) { voice = VoiceLevels.Silence; freshness = 0f }
        val w = min(bounds.width(), bounds.height()).toFloat()
        val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
        paint.style = Paint.Style.FILL
        SoftCurrentPalette.Layers.forEachIndexed { layer, color ->
            // Even the strongest morph plus drift stays inside the control's touch bounds.
            val radius = w * (.40f - layer * .019f)
            for (i in 0 until 72) {
                val angle = i * 2.0 * PI / 72
                val r = radius * CurrentGeometry.radius(angle, layer, phase, voicePhase, voice, freshness)
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
            val neutral = SoftCurrentPalette.NeutralLayers[layer]
            val base = blend(blend(neutral,color,tint),SoftCurrentPalette.ErrorLayers[layer],attention)
            val glow = blend(base,SoftCurrentPalette.Surface,.16f)
            val travel = (sin(phase*.6)*w*.15f).toFloat()
            paint.shader = LinearGradient(x-w*.45f+travel,y-w*.35f,x+w*.45f+travel,y+w*.4f, intArrayOf(base,glow,base),floatArrayOf(0f,.55f,1f),Shader.TileMode.CLAMP)
            paint.alpha = opacity
            canvas.drawPath(path,paint)
        }
        paint.shader = null
        paint.color = blend(blend(0xFF5B5666.toInt(),0xFF542194.toInt(),tint),0xFFFFE8ED.toInt(),attention)
        paint.alpha = opacity; paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = max(1f,w*.006f)
        repeat(4) { line ->
            path.reset()
            for (i in 0..64) {
                val t=i/64f
                val px=x+(t-.5f)*w*.48f
                val py=y+sin(t*PI*2+line*.48+phase*.45)*sin(t*PI)*w*(.075f+energy*.035f+voice.loudness.safeUnit()*freshness*.02f)
                if (i==0) path.moveTo(px,py.toFloat()) else path.lineTo(px,py.toFloat())
            }
            canvas.drawPath(path,paint)
        }
    }
    override fun setAlpha(alpha: Int) { opacity=alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter=colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Android") override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
