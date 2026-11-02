package io.github.ilyasyoy.voicedictation.data

/** Every retained ledger row counts, including active Record capture and legacy text-only rows. */
data class HistoryCapacity(val limit: Int = DEFAULT_LIMIT, val stored: Int = 0,
    val readable: Boolean = true) {
    val used get() = stored
    val full get() = readable && used >= limit
    val canStart get() = readable && used < limit
    companion object {
        const val DEFAULT_LIMIT = 100
        fun parse(value: String): Int? = value.trim().takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
            ?.toIntOrNull()?.takeIf { it > 0 }
    }
}

internal class HistoryCapacityReached(val capacity: HistoryCapacity) : IllegalStateException("History capacity reached")
