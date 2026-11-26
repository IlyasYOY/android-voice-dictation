package io.github.ilyasyoy.voicedictation.floating

internal data class FloatingKeyboardGeometry(
    val windowId: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val usable get() = right > left && bottom > top
}

/** Vertically matches the display used to place the floating window. */
internal data class FloatingDisplayHeight(val top: Int, val bottom: Int) {
    fun contains(keyboard: FloatingKeyboardGeometry) =
        keyboard.usable && keyboard.top >= top && keyboard.bottom <= bottom
}

/** Keeps transient IME bounds out of the first visible overlay frame. */
internal class FloatingKeyboardOpening {
    private var candidate: FloatingKeyboardGeometry? = null
    private var unchangedSince = 0L

    fun canShow(geometry: FloatingKeyboardGeometry, nowMs: Long, alreadyShowing: Boolean,
                display: FloatingDisplayHeight?): Boolean {
        if (alreadyShowing) {
            reset()
            return true
        }
        // Some IMEs publish a complete but translated rectangle below the display.
        // Its stale coordinates can remain identical for longer than SETTLE_MS.
        if (display?.contains(geometry) != true) {
            reset()
            return false
        }
        if (candidate != geometry) {
            candidate = geometry
            unchangedSince = nowMs
            return false
        }
        return nowMs - unchangedSince >= SETTLE_MS
    }

    fun reset() {
        candidate = null
    }

    companion object {
        const val SAMPLE_INTERVAL_MS = 40L
        private const val SETTLE_MS = 120L
    }
}
