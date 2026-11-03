package io.github.ilyasyoy.voicedictation.diagnostics

import java.io.IOException
import java.io.OutputStream

internal object DiagnosticExport {
    /** A newly selected document only; cleanup never touches earlier user-saved copies. */
    fun save(snapshot: () -> ByteArray, open: () -> OutputStream?, discardPartial: () -> Unit,
             appVersion: Int, androidApi: Int): Boolean {
        return try {
            val events = snapshot()
            require(events.size <= DiagnosticStore.MAX_BYTES)
            val output = open() ?: throw IOException("Document unavailable")
            DiagnosticArchive.write(output, events, appVersion, androidApi, Diagnostics.dropped.get(), Diagnostics.writeFailures.get())
            true
        } catch (error: Exception) {
            Diagnostics.failure(DiagnosticArea.EXPORT, DiagnosticAction.SAVE, error)
            runCatching(discardPartial)
            false
        }
    }
}
