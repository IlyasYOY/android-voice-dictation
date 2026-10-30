package io.github.ilyasyoy.voicedictation.floating

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FinalTextInsertionTest {
    private fun editor() = EditorToken("test.editor", 1, "body", 1, 7, 7, false, false, 0x20001, 0, -1, -1)
    @Test fun `lock keyboard hide disable or owner invalidation during preflight never commits`() = runTest {
        for (gate in listOf("lock", "keyboard", "disable", "owner")) {
            val read = CompletableDeferred<EditorSurrounding?>(); var allowed = true; var writes = 0
            val target = object : EditorConnection {
                override suspend fun surrounding(before: Int) = read.await()
                override fun commit(text: String) { writes++ }
            }
            val task = async { FinalTextInsertion.insert(editor(), "voice", target, ::editor) { allowed } }
            runCurrent(); allowed = false; read.complete(EditorSurrounding("", 7, 0, 0)); runCurrent()
            assertEquals(gate, InsertionResult.CANCELLED, task.await()); assertEquals(0, writes)
        }
    }
    @Test fun `field or caret changed during suspended preflight never commits`() = runTest {
        for (changed in listOf(editor().copy(viewId = "other"), editor().copy(selectionStart = 8, selectionEnd = 8))) {
            val read = CompletableDeferred<EditorSurrounding?>(); var current = editor(); var writes = 0
            val target = object : EditorConnection {
                override suspend fun surrounding(before: Int) = read.await()
                override fun commit(text: String) { writes++ }
            }
            val task = async { FinalTextInsertion.insert(editor(), "voice", target, { current }) { true } }
            runCurrent(); current = changed; read.complete(EditorSurrounding("", 7, 0, 0)); runCurrent()
            assertEquals(InsertionResult.COPY, task.await()); assertEquals(0, writes)
        }
    }
    @Test fun `native insertion confirms original caret and bounded suffix with one write`() = runTest {
        var current = editor(); var writes = 0; val lengths = mutableListOf<Int>()
        val target = object : EditorConnection {
            override suspend fun surrounding(before: Int): EditorSurrounding {
                lengths += before
                return if (writes == 0) EditorSurrounding("", 7, 0, 0) else EditorSurrounding("voice", 7, 5, 5)
            }
            override fun commit(text: String) { writes++; current = current.copy(selectionStart = 12, selectionEnd = 12) }
        }
        val task = async { FinalTextInsertion.insert(editor(), "voice", target, { current }) { true } }
        runCurrent(); advanceTimeBy(250); runCurrent()
        assertEquals(InsertionResult.INSERTED, task.await()); assertEquals(1, writes); assertEquals(listOf(0, 5), lengths)
    }
    @Test fun `uncertain commit or confirmation never retries or reports safe Copy`() = runTest {
        for (throwOnCommit in listOf(true, false)) {
            var writes = 0
            val target = object : EditorConnection {
                override suspend fun surrounding(before: Int): EditorSurrounding? = if (writes == 0) EditorSurrounding("", 7, 0, 0) else null
                override fun commit(text: String) { writes++; if (throwOnCommit) error("IPC response lost") }
            }
            val task = async { FinalTextInsertion.insert(editor(), "voice", target, ::editor) { true } }
            runCurrent(); advanceTimeBy(250); runCurrent()
            assertEquals(InsertionResult.UNCONFIRMED, task.await()); assertEquals(1, writes)
        }
    }
    @Test fun `unsupported preflight offers Copy without attempting commit`() = runTest {
        val target = object : EditorConnection {
            override suspend fun surrounding(before: Int): EditorSurrounding? = null
            override fun commit(text: String) = fail("unsupported editor must not receive a write")
        }
        assertEquals(InsertionResult.COPY, FinalTextInsertion.insert(editor(), "voice", target, ::editor) { true })
    }
}
