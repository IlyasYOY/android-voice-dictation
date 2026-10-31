package io.github.ilyasyoy.voicedictation.floating

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal data class EditorSurrounding(val text: CharSequence, val offset: Int, val start: Int, val end: Int)
internal interface EditorConnection {
    suspend fun surrounding(before: Int): EditorSurrounding?
    fun commit(text: String)
}
internal enum class InsertionResult { INSERTED, COPY, UNCONFIRMED, CANCELLED }

/** Single attempt on the captured original connection. Never retries, rolls back, replaces or submits. */
internal object FinalTextInsertion {
    suspend fun insert(origin: EditorToken, text: String, target: EditorConnection,
                       environment: () -> FloatingEnvironment, onCommitAttempt: () -> Unit = {}, currentOwner: () -> Boolean): InsertionResult {
        var attempted = false
        try {
            if (!environment().visible || !currentOwner()) return InsertionResult.CANCELLED
            val before = target.surrounding(0) ?: return InsertionResult.COPY
            val fresh = environment()
            val editor = fresh.editor
            // Every environment gate is rechecked after IO, at the actual write boundary.
            if (!fresh.visible || !currentOwner()) return InsertionResult.CANCELLED
            if (editor == null || !EditorPolicy.canInsert(origin, editor, 33, text) || before.offset < 0 ||
                before.start != before.end || before.offset + before.start != origin.selectionStart) return InsertionResult.COPY
            attempted = true
            onCommitAttempt()
            target.commit(text)
            delay(250)
            val after = target.surrounding(minOf(text.length, 256))
            val confirmedEnvironment = environment()
            val now = confirmedEnvironment.editor
            if (!confirmedEnvironment.visible || !currentOwner()) return InsertionResult.UNCONFIRMED
            val confirmed = now != null && origin.sameEditor(now) && EditorPolicy.allowed(now) && after != null && after.offset >= 0 &&
                EditorPolicy.confirmed(origin.selectionStart, text, after.offset + after.start, after.offset + after.end, after.text)
            return if (confirmed) InsertionResult.INSERTED else InsertionResult.UNCONFIRMED
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { return if (attempted) InsertionResult.UNCONFIRMED else InsertionResult.COPY }
    }
}
