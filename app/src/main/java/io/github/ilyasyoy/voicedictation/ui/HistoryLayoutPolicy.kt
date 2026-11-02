package io.github.ilyasyoy.voicedictation.ui

/** Coordinates are dp in the inset-safe content area, never physical display dimensions. */
internal data class HistoryRegion(val left: Float, val top: Float, val width: Float, val height: Float)
internal data class HistoryHinge(val left: Float, val top: Float, val right: Float, val bottom: Float, val vertical: Boolean)
internal data class HistoryLayout(val region: HistoryRegion, val mainWidth: Float, val gap: Float, val historyWidth: Float) {
    val wide get() = historyWidth > 0f
}
internal object HistoryLayoutPolicy {
    fun calculate(width: Float, height: Float, fontScale: Float, hinge: HistoryHinge? = null): HistoryLayout {
        val w = width.coerceAtLeast(0f); val h = height.coerceAtLeast(0f)
        val scale = fontScale.takeIf { it.isFinite() && it > 0f }?.coerceAtLeast(1f) ?: 1f
        val mainMin = 320f * scale; val historyMin = 360f * scale
        val threshold = maxOf(840f, mainMin + historyMin + 24f)
        val full = HistoryRegion(0f, 0f, w, h)
        fun single(region: HistoryRegion) = HistoryLayout(region, region.width, 0f, 0f)
        fun split(region: HistoryRegion): HistoryLayout {
            if (region.width < threshold) return single(region)
            val history = (region.width * .46f).coerceIn(historyMin, region.width - mainMin - 24f)
            return HistoryLayout(region, region.width - history - 24f, 24f, history)
        }
        val fold = hinge ?: return split(full)
        // Ignore features outside this app window; never infer a hinge from device identity.
        if (fold.vertical) {
            if (fold.right < 0 || fold.left > w || fold.bottom <= 0 || fold.top >= h) return split(full)
            val left = fold.left.coerceIn(0f, w); val right = fold.right.coerceIn(left, w)
            if (w >= threshold && left >= mainMin && w - right >= historyMin)
                return HistoryLayout(full, left, right - left, w - right)
            return single(if (left >= w - right) HistoryRegion(0f, 0f, left, h) else HistoryRegion(right, 0f, w - right, h))
        }
        if (fold.bottom < 0 || fold.top > h || fold.right <= 0 || fold.left >= w) return split(full)
        val top = fold.top.coerceIn(0f, h); val bottom = fold.bottom.coerceIn(top, h)
        // Tabletop: use the larger unobstructed region, rather than span controls across the fold.
        return split(if (top >= h - bottom) HistoryRegion(0f, 0f, w, top) else HistoryRegion(0f, bottom, w, h - bottom))
    }
}
