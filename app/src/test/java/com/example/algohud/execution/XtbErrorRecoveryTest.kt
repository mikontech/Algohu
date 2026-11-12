package com.example.algohud.execution

import com.example.algohud.execution.XtbErrorRecovery.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XtbErrorRecoveryTest {
    /** Symuluje skan co 1 s przez [seconds] s; zwraca akcje inne niż NONE z ich sekundą. */
    private fun run(r: XtbErrorRecovery, seconds: Int, error: (Int) -> Boolean): List<Pair<Int, Action>> =
        (0..seconds).mapNotNull { s -> r.onScan(error(s), s * 1000L).takeIf { it != Action.NONE }?.let { s to it } }

    @Test fun detectsPolishAndEnglishErrorScreen() {
        assertTrue(XtbErrorRecovery.isErrorText(listOf("Coś poszło nie tak", "Odśwież")))
        assertTrue(XtbErrorRecovery.isErrorText(listOf("Wystąpił nieoczekiwany błąd! Pracujemy nad rozwiązaniem problemu.")))
        assertTrue(XtbErrorRecovery.isErrorText(listOf("Something went wrong")))
        assertFalse(XtbErrorRecovery.isErrorText(listOf("GOLD", "Sell", "Buy", "Zlecenie Market")))
    }

    @Test fun tapsRefreshThenRestartsThenRetries() {
        val a = run(XtbErrorRecovery(), 75) { true }
        assertEquals(Action.TAP_REFRESH, a[0].second); assertEquals(2, a[0].first)
        assertEquals(Action.TAP_REFRESH, a[1].second); assertEquals(8, a[1].first)
        assertEquals(Action.RESTART_APP, a[2].second); assertEquals(14, a[2].first)
        // po restarcie znów „Odśwież”, a kolejny restart najwcześniej po minucie
        assertEquals(Action.TAP_REFRESH, a[3].second)
        assertEquals(1, a.count { it.second == Action.RESTART_APP && it.first < 74 })
        assertTrue(a.any { it.second == Action.RESTART_APP && it.first >= 74 })
    }

    @Test fun refreshFixesItWithoutRestart() {
        val a = run(XtbErrorRecovery(), 30) { it < 4 } // błąd znika po pierwszym „Odśwież”
        assertEquals(listOf(Action.TAP_REFRESH, Action.RECOVERED), a.map { it.second })
    }

    @Test fun noErrorNoAction() {
        assertTrue(run(XtbErrorRecovery(), 60) { false }.isEmpty())
    }

    @Test fun shortFlickerIsIgnored() {
        // błąd widoczny 1 s (np. przejście ekranu) – bez dotykania, tylko RECOVERED
        val a = run(XtbErrorRecovery(), 10) { it == 3 }
        assertEquals(listOf(Action.RECOVERED), a.map { it.second })
    }
}
