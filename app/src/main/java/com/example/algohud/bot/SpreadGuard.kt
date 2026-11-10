package com.example.algohud.bot

import com.example.algohud.core.ScreenQuote
import java.util.concurrent.ConcurrentHashMap

/** Pilnuje spreadu: pomija sygnał, gdy bieżący spread jest wyraźnie szerszy niż zwykle dla instrumentu. */
object SpreadGuard {
    private class St(var ema: Double = 0.0, var n: Int = 0, var last: Double = 0.0, var lastAt: Long = 0L)

    private val states = ConcurrentHashMap<String, St>()
    private const val WIDE_FACTOR = 2.0
    private const val MIN_OBS = 20
    private const val STALE_MS = 15_000L

    fun observeQuote(q: ScreenQuote) {
        val inst = q.instrument ?: return
        val bid = q.bid ?: return
        val ask = q.ask ?: return
        val sp = ask - bid
        if (!sp.isFinite() || sp <= 0.0) return
        val st = states.getOrPut(inst) { St() }
        synchronized(st) {
            st.last = sp
            st.lastAt = System.currentTimeMillis()
            if (st.n == 0) {
                st.ema = sp
                st.n = 1
                return
            }
            if (st.n >= MIN_OBS && sp > st.ema * 3.0) return
            st.ema = st.ema * 0.98 + sp * 0.02
            st.n++
        }
    }

    fun wideReason(inst: String): String? {
        val st = states[inst] ?: return null
        synchronized(st) {
            if (st.n < MIN_OBS) return null
            if (System.currentTimeMillis() - st.lastAt > STALE_MS) return null
            if (st.last > st.ema * WIDE_FACTOR) {
                return "spread %.2f > %.1f× normy %.2f".format(st.last, WIDE_FACTOR, st.ema)
            }
            return null
        }
    }
}
