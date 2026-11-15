package com.example.algohud.execution
import com.example.algohud.core.ExecutionResult
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
class OperationQueueTest {
    @Test fun duplicateClicksAndIdsCannotExecuteTwice() = runTest {
        val q = OperationQueue { testScheduler.currentTime }
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val first = async { q.run("id", "GOLD:close", 1000) { calls++; gate.await(); ExecutionResult.Submitted("ok") } }
        runCurrent()
        assertTrue(q.run("another", "GOLD:close", 1000) { error("duplicate") } is ExecutionResult.Rejected)
        gate.complete(Unit); first.await()
        assertTrue(q.run("id", "GOLD:close", 1000) { error("replay") } is ExecutionResult.Rejected)
        assertEquals(1, calls)
    }
    @Test fun expiredQueueAndCancellationReleaseLocks() = runTest {
        val q = OperationQueue { testScheduler.currentTime }
        val first = launch { q.run("a", "a", 1000) { delay(200); ExecutionResult.Submitted("ok") } }
        runCurrent()
        val second = async { q.run("b", "b", 100) { error("stale entry") } }
        advanceUntilIdle(); first.join()
        assertTrue(second.await() is ExecutionResult.Rejected)
        val third = launch { q.run("c", "c", 1000) { awaitCancellation() } }
        runCurrent(); third.cancelAndJoin()
        assertTrue(q.run("d", "c", 1000) { ExecutionResult.Submitted("ok") } is ExecutionResult.Submitted)
    }
}
