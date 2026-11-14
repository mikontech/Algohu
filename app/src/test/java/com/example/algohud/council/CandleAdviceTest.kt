package com.example.algohud.council
import com.example.algohud.core.Bar
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class CandleAdviceTest {
    @Test fun directionalScenarioRequiresValidLevelsAndCondition() {
        val good = JSONObject("""{"side":"LONG","entry":100,"invalidation":99,"condition":"zamknięcie M1 ponad 100","reason":"odbicie"}""")
        assertEquals("LONG", CandleAdvice.parse(good).side)
        assertThrows(IllegalArgumentException::class.java) { CandleAdvice.parse(good.put("invalidation", 101)) }
        assertThrows(IllegalArgumentException::class.java) { CandleAdvice.parse(good.put("side", "BUY")) }
    }
    @Test fun waitingDoesNotInventEntry() {
        val a = CandleAdvice.parse(JSONObject("""{"side":"WAIT","condition":"czekaj na zamknięcie M1","reason":"brak kierunku"}"""))
        assertNull(a.entry); assertNull(a.invalidation)
        assertTrue(a.display().startsWith("CZEKAM"))
    }
    @Test fun rejectsStaleGappedAndOpenCandles() {
        val bars = (1..30).map { Bar(it * 60000L, 100.0, 102.0, 99.0, 101.0) }
        val now = 31 * 60000L
        assertTrue(CandleAdvice.validBars(bars, 60000, now))
        assertFalse(CandleAdvice.validBars(bars, 60000, now + 120001))
        assertFalse(CandleAdvice.validBars(bars.filterIndexed { i, _ -> i != 15 }, 60000, now))
        assertFalse(CandleAdvice.validBars(bars, 60000, now - 1))
    }
}
