package com.example.algohud.research
import com.example.algohud.core.*
import org.junit.Assert.*
import org.junit.Test

class ResearchTest {
    private fun order() = OrderRequest("GOLD",Side.BUY,1.0,100.0,90.0,"impulse", takeProfit=120.0,signalAt=1000,brokerReferencePrice=200.0)
    private fun quote(bid: Double=199.0, ask: Double=200.0, at: Long=1000) = ScreenQuote("com.xtb.xmobile2","XTB","GOLD",bid,ask,at,accountCurrency="PLN")
    @Test fun differentFeedPriceDoesNotLookLikeSlippage() {
        assertNull(EntryTimingGuard.check(order(),quote(),1000))
        assertNotNull(EntryTimingGuard.check(order(),quote(202.0,203.0),1000))
    }
    @Test fun exitsBypassAndSignalsExpire() {
        assertNull(EntryTimingGuard.check(order().copy(reduceOnly=true),null,200000))
        assertNotNull(EntryTimingGuard.check(order(),quote(at=92000),92000))
        assertNotNull(EntryTimingGuard.check(order(),quote().copy(instrument="SILVER"),1000))
        assertNotNull(EntryTimingGuard.check(order().copy(takeProfit=105.0),quote(),1000))
    }
    @Test fun shadowUsesSpreadAndNeverDoubleOpensCandidate() {
        val events = mutableListOf<Pair<String,Map<String,Any?>>>()
        val book = ShadowBook { e,d -> events.add(e to d) }
        val o=order()
        book.candidate(o,quote(),1000); book.candidate(o,quote(),1000)
        assertEquals(3,events.count { it.first=="shadow_open" })
        book.quote(quote(at=301001),301001)
        val closed=events.filter { it.first=="shadow_close" }
        assertEquals(3,closed.size)
        assertTrue(closed.all { it.second["pnl_estimate"] == -1.0 })
    }
    @Test fun missingOrStaleQuotesDoNotProducePaperProfit() {
        val events = mutableListOf<String>()
        val book=ShadowBook { e,_ -> events.add(e) }
        book.candidate(order(),quote(),1000)
        book.quote(quote(220.0,221.0,at=1000),10000)
        assertEquals(0,events.count { it=="shadow_close" })
    }
}
