package io.github.ilyasyoy.voicedictation.ui

import org.junit.Assert.*
import org.junit.Test

class HistoryDialogBoundsTest {
    @Test fun `vertical hinge dialog stays in history half and main dialog stays left`() {
        val plan = HistoryLayoutPolicy.calculate(1000f, 700f, 1f, HistoryHinge(480f, 0f, 520f, 700f, true))
        val window = HistoryRegion(0f, 0f, 1000f, 700f)
        val history = historyDialogBounds(window, HistoryRegion(plan.mainWidth + plan.gap, 0f, plan.historyWidth, 700f))
        assertEquals(HistoryRegion(520f, 0f, 480f, 700f), history)
        val main = historyDialogBounds(window, HistoryRegion(0f, 0f, plan.mainWidth, 700f))
        assertTrue(main.left + main.width <= 480f)
    }
    @Test fun `tabletop dialog remains below hinge and intersects keyboard safely`() {
        val plan = HistoryLayoutPolicy.calculate(1000f, 700f, 1f, HistoryHinge(0f, 300f, 1000f, 340f, false))
        val visible = HistoryRegion(0f, 24f, 1000f, 500f)
        val bounds = historyDialogBounds(visible, plan.region)
        assertEquals(HistoryRegion(0f, 340f, 1000f, 184f), bounds)
        assertEquals(0f, historyDialogBounds(HistoryRegion(0f, 0f, 1000f, 300f), plan.region).height)
    }
    @Test fun `compact and standalone dialogs respect inset bounds`() {
        val safe = HistoryRegion(20f, 30f, 380f, 600f)
        assertEquals(safe, historyDialogBounds(safe, null))
        assertEquals(HistoryRegion(20f, 30f, 280f, 270f), historyDialogBounds(safe, HistoryRegion(0f, 0f, 300f, 300f)))
    }
}
