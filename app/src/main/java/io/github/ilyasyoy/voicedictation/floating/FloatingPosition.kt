package io.github.ilyasyoy.voicedictation.floating

import kotlin.math.roundToInt

/** Circle anchor, independent of temporary menu size, pixels, orientation and IME height. */
internal data class FloatingPosition(val horizontalFraction: Float, val keyboardGapDp: Float) {
    fun encode() = "1|$horizontalFraction|$keyboardGapDp"
    companion object {
        val DEFAULT = FloatingPosition(1f, 0f)
        fun decode(value: String?): FloatingPosition? {
            val parts = value?.split('|') ?: return null
            if (parts.size != 3 || parts[0] != "1") return null
            val x = parts[1].toFloatOrNull() ?: return null
            val gap = parts[2].toFloatOrNull() ?: return null
            return if (x.isFinite() && x in 0f..1f && gap.isFinite() && gap >= 0f) FloatingPosition(x, gap) else null
        }
    }
}

internal data class FloatingPoint(val x: Int, val y: Int)

/** Absolute display coordinates, with system bars/cutouts and the IME already excluded. */
internal data class FloatingArea(val left: Int, val top: Int, val right: Int, val bottom: Int,
    val density: Float, val diameter: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun place(position: FloatingPosition): FloatingPoint = FloatingPoint(
        (left + (width - diameter) * position.horizontalFraction.toDouble()).roundToInt(),
        (bottom.toDouble() - diameter - position.keyboardGapDp.toDouble() * density)
            .coerceIn(top.toDouble(), (bottom - diameter).toDouble()).roundToInt())
    fun remember(point: FloatingPoint) = FloatingPosition(
        if (width == diameter) .5f else ((point.x.toDouble() - left) / (width - diameter)).coerceIn(0.0, 1.0).toFloat(),
        ((bottom.toDouble() - diameter - point.y.coerceIn(top, bottom - diameter)) / density).toFloat())

    companion object {
        fun create(width: Int, height: Int, imeTop: Int, density: Float,
            insetLeft: Int = 0, insetTop: Int = 0, insetRight: Int = 0, insetBottom: Int = 0): FloatingArea? {
            if (width <= 0 || height <= 0 || !density.isFinite() || density <= 0) return null
            val diameter = (64.0 * density).toLong()
            if (diameter !in 1..Int.MAX_VALUE.toLong()) return null
            val margin = (8.0 * density).toLong()
            val left = insetLeft.coerceAtLeast(0).toLong() + margin
            val right = width.toLong() - insetRight.coerceAtLeast(0) - margin
            val top = maxOf((24.0 * density).toLong(), insetTop.coerceAtLeast(0).toLong())
            val bottom = minOf(imeTop.toLong(), height.toLong() - insetBottom.coerceAtLeast(0)) - margin
            if (right - left < diameter || bottom - top < diameter) return null
            return FloatingArea(left.toInt(), top.toInt(), right.toInt(), bottom.toInt(), density, diameter.toInt())
        }
    }
}

internal interface FloatingPositionStore {
    fun load(): String?
    fun save(value: String): Boolean
}

/** Only completed user drags mutate preference; projecting a cramped layout never does. */
internal class FloatingPositionState(private val store: FloatingPositionStore) {
    private var preferred = FloatingPosition.decode(store.load()) ?: FloatingPosition.DEFAULT
    fun place(area: FloatingArea) = area.place(preferred)
    fun dragged(point: FloatingPoint, area: FloatingArea): Boolean {
        preferred = area.remember(point)
        return store.save(preferred.encode())
    }
}
