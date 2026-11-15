package com.example.algohud.execution
import com.example.algohud.core.*
import com.example.algohud.risk.RiskManager
import com.example.algohud.council.memoryPrefs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
class ExecutionSafetyTest {
    private val entry = OrderRequest("GOLD", Side.BUY, 1.0, 100.0, 90.0, "test")
    private class FakeExecutor : OrderExecutor {
        var calls = 0
        override suspend fun cancelAll() {}
        override suspend fun submit(order: OrderRequest): ExecutionResult { calls++; return ExecutionResult.Filled(order.clientOrderId, order.referencePrice, order.quantity) }
    }
    @Test fun stopBlocksEntryButAllowsCloseAndCannotReplayClose() = runTest {
        val risk = RiskManager(memoryPrefs())
        risk.updateEquity(10000.0, "XTB:PLN")
        val exec = FakeExecutor()
        val engine = ExecutionEngine(risk, exec, ExecutionMode.AUTO)
        assertTrue(engine.submit(entry) is ExecutionResult.Filled)
        engine.stopEntries()
        val close = entry.copy(side = Side.SELL, reduceOnly = true, clientOrderId = "close")
        assertTrue(engine.submit(close) is ExecutionResult.Filled)
        assertTrue(engine.submit(close) is ExecutionResult.Rejected)
        assertTrue(engine.submit(entry.copy(clientOrderId = "new")) is ExecutionResult.Rejected)
        assertNull(risk.position("GOLD")); assertEquals(2, exec.calls)
    }
    @Test fun budgetUsesLedgerAndAppliesConfiguredLimit() = runTest {
        val prefs = memoryPrefs(mutableMapOf("session_loss_limit" to "20"))
        val risk = RiskManager(prefs)
        risk.updateEquity(10000.0, "XTB:PLN")
        assertEquals(20.0, risk.remainingBudget()!!, 0.0)
        risk.onFill(entry, 100.0, 1.0)
        risk.onFill(entry.copy(side = Side.SELL, reduceOnly = true), 95.0, 1.0)
        assertEquals(15.0, risk.remainingBudget()!!, 0.0)
        assertTrue(risk.evaluate(entry.copy(stopPrice = 80.0)) is RiskManager.Decision.Rejected)
    }
    @Test fun staleExpectedPositionNeverClosesReplacement() = runTest {
        val risk = RiskManager(memoryPrefs()); risk.updateEquity(10000.0, "XTB:PLN")
        val exec = FakeExecutor(); val engine = ExecutionEngine(risk, exec, ExecutionMode.AUTO)
        engine.submit(entry)
        val result = engine.submit(entry.copy(side = Side.SELL, reduceOnly = true, clientOrderId = "wrong", expectedPositionAt = 1))
        assertTrue(result is ExecutionResult.Rejected); assertEquals(1, exec.calls)
    }
}
