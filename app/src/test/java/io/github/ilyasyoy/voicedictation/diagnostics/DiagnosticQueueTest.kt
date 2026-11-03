package io.github.ilyasyoy.voicedictation.diagnostics

import java.util.concurrent.*
import org.junit.Test
import org.junit.Assert.*

class DiagnosticQueueTest {
    @Test fun overloadRejectsImmediatelyAndAcceptedEventsStayOrdered() {
        DiagnosticQueue(2).use { queue ->
            val started=CountDownLatch(1);val release=CountDownLatch(1);val finished=CountDownLatch(2)
            val result=java.util.Collections.synchronizedList(mutableListOf<Int>())
            queue.execute { started.countDown();release.await(5,TimeUnit.SECONDS) }
            assertTrue(started.await(5,TimeUnit.SECONDS))
            try {
                queue.execute { result+=1;finished.countDown() };queue.execute { result+=2;finished.countDown() }
                assertThrows(RejectedExecutionException::class.java) { queue.execute { error("Rejected event must never run") } }
            } finally { release.countDown() }
            assertTrue(finished.await(5,TimeUnit.SECONDS));assertEquals(listOf(1,2),result)
        }
    }
    @Test fun snapshotBarrierIncludesAcceptedWritesAndTimeoutRemovesQueuedSnapshot() {
        DiagnosticQueue(2).use { queue ->
            val result=mutableListOf<Int>()
            queue.execute { result+=1 };queue.execute { result+=2 }
            assertEquals(listOf(1,2),queue.await(FutureTask { result.toList() },5000))
            val started=CountDownLatch(1);val release=CountDownLatch(1)
            queue.execute { started.countDown();release.await(5,TimeUnit.SECONDS) };assertTrue(started.await(5,TimeUnit.SECONDS))
            val task=FutureTask { error("Timed out snapshot must not run") }
            try { assertThrows(TimeoutException::class.java) { queue.await(task,10) };assertTrue(task.isCancelled) }
            finally { release.countDown() }
            assertEquals(3,queue.await(FutureTask { 3 },5000))
        }
    }
}
