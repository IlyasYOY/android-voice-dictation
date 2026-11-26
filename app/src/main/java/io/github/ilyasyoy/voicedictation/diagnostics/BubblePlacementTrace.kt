package io.github.ilyasyoy.voicedictation.diagnostics

/** In-memory, opt-in trace for one keyboard opening. No free text, editor IDs, or network. */
internal class BubblePlacementCapture {
    private var armedAt: Long? = null
    private var startedAt: Long? = null
    private val rows = ArrayList<String>(MAX_ROWS)

    @Synchronized fun arm(now: Long) {
        armedAt = now
        startedAt = null
        rows.clear()
    }

    @Synchronized fun isCapturing(now: Long): Boolean {
        val armed = armedAt ?: return false
        val started = startedAt
        return if (started == null) now - armed in 0..ARM_MS
        else now - started in 0..CAPTURE_MS && rows.size < MAX_ROWS
    }

    @Synchronized fun sample(now: Long, stage: BubblePlacementStage, a: Int, b: Int, c: Int, d: Int) {
        if (!isCapturing(now)) return
        val start = startedAt ?: run {
            if (stage != BubblePlacementStage.IME_SAMPLE) return
            now.also { startedAt = it }
        }
        rows.add("${now - start},${stage.name},$a,$b,$c,$d")
    }

    @Synchronized fun snapshot(now: Long): ByteArray? {
        val start = startedAt ?: return null
        if (now - start !in 0..EXPORT_MS || rows.isEmpty()) return null
        return rows.joinToString("\n", prefix = "elapsed_ms,stage,a,b,c,d\n", postfix = "\n").toByteArray(Charsets.UTF_8)
    }

    companion object {
        private const val ARM_MS = 90_000L
        private const val CAPTURE_MS = 2_500L
        private const val EXPORT_MS = 600_000L
        private const val MAX_ROWS = 128
    }
}

internal enum class BubblePlacementStage {
    IME_SAMPLE, IME_ACCEPTED, ADD_REQUEST, SERVICE_REQUEST, INSETS_REQUEST,
    FIRST_PRE_DRAW, LATER_PRE_DRAW
}

internal val bubblePlacementTrace = BubblePlacementCapture()

internal fun bubblePlacementNow(): Long = System.nanoTime() / 1_000_000L
