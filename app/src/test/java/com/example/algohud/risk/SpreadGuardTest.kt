package com.example.algohud.risk
import com.example.algohud.core.*
import org.junit.Assert.*
import org.junit.Test
class SpreadGuardTest {
    private val order = OrderRequest("GOLD", Side.BUY, .01, 100.0, 90.0, "test", pointValue = 400.0)
    private val quote = ScreenQuote("xtb", "XTB", "GOLD", 100.0, 102.0, 10000, accountCurrency = "PLN")
    @Test fun computesActualSpreadCostAndRiskRatio() {
        val a = SpreadGuard.assess(order, quote, 25.0, 10000)
        assertEquals(8.0, a.amount!!, 1e-9); assertEquals(20.0, a.percent!!, 1e-9); assertNull(a.block)
        assertNotNull(SpreadGuard.assess(order, quote, 15.0, 10000).block)
    }
    @Test fun staleFutureWrongSymbolFailClosedButExitAlwaysAllowed() {
        for (q in listOf(null, quote.copy(timestamp = 20000), quote.copy(timestamp = 1), quote.copy(instrument = "SILVER")))
            assertNotNull(SpreadGuard.assess(order, q, 25.0, 10000).block)
        assertNull(SpreadGuard.assess(order.copy(reduceOnly = true), null, 25.0, 10000).block)
    }
}
