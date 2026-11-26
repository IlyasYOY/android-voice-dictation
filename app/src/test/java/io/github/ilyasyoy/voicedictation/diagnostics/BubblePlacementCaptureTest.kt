package io.github.ilyasyoy.voicedictation.diagnostics

import org.junit.Assert.*
import org.junit.Test

class BubblePlacementCaptureTest {
    private val capture = BubblePlacementCapture()
    private fun sample(now: Long, stage: BubblePlacementStage = BubblePlacementStage.IME_SAMPLE) =
        capture.sample(now, stage, 11, 22, 33, 44)

    @Test fun requiresExplicitArmingAndStartsOnImeSample() {
        sample(20)
        assertNull(capture.snapshot(21))
        capture.arm(100)
        sample(110, BubblePlacementStage.ADD_REQUEST)
        assertNull(capture.snapshot(111))
        sample(120)
        sample(128, BubblePlacementStage.FIRST_PRE_DRAW)
        assertEquals("elapsed_ms,stage,a,b,c,d\n0,IME_SAMPLE,11,22,33,44\n8,FIRST_PRE_DRAW,11,22,33,44\n",
            capture.snapshot(130)?.decodeToString())
    }

    @Test fun traceIsBoundedAndCanBeRearmed() {
        capture.arm(10)
        sample(90_011)
        assertNull(capture.snapshot(90_012))
        capture.arm(100_000)
        repeat(150) { sample(100_001L + it) }
        sample(103_000)
        assertEquals(129, capture.snapshot(103_000)!!.decodeToString().trimEnd().lines().size)
        assertNull(capture.snapshot(700_002))
        capture.arm(700_003)
        assertNull(capture.snapshot(700_004))
        sample(700_005)
        sample(702_506)
        assertEquals(2, capture.snapshot(702_507)!!.decodeToString().trimEnd().lines().size)
    }
}
