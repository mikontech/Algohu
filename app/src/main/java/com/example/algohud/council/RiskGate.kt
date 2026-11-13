package com.example.algohud.council

import com.example.algohud.core.OrderRequest
import com.example.algohud.core.Side
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.round

/**
 * Deterministyczna ostatnia linia obrony po debacie AI (czysta logika – testowalna, bez sieci).
 * LLM może tylko: zatwierdzić kandydata, zmniejszyć wolumen albo zawetować.
 * Każda próba zmiany kierunku / SL / TP albo zwiększenia wolumenu = WETO (traktowane jak halucynacja).
 */
object RiskGate {
    const val MIN_LOT = 0.006
    const val LOT_STEP = 0.001
    const val MIN_RR = 2.5
    /** Cena może uciec w trakcie debaty najwyżej o tyle × odległość SL. */
    const val MAX_DRIFT_OF_SL = 0.25
    const val MAX_AGE_MS = 45_000L
    private const val PRICE_EPS = 0.05

    data class Inputs(
        val candidate: OrderRequest,
        val observer: ObserverReport?,
        val skeptic: SkepticView,
        val commander: CommanderOrder,
        /** Cena bota teraz (po debacie). */
        val priceNow: Double,
        /** Cena XTB = cena bota + shift. */
        val xtbShift: Double,
        val instrument: String,
        val startedAt: Long,
        val now: Long,
        /** Pozycje na tym instrumencie widoczne w XTB, ale spoza księgi bota (np. otwarte ręcznie). */
        val untrackedXtbRows: Int = 0
    )

    fun check(i: Inputs): Result<OrderRequest> {
        val o = i.candidate
        fun fail(why: String) = Result.failure<OrderRequest>(IllegalStateException(why))

        if (i.untrackedXtbRows > 0)
            return fail("w XTB jest już ${i.untrackedXtbRows} pozycja(e) ${i.instrument} spoza księgi bota – jedna pozycja naraz")
        if (i.now - i.startedAt > MAX_AGE_MS) return fail("debata trwała ${(i.now - i.startedAt) / 1000} s – za długo, rynek się zmienił")

        i.observer?.let { ob ->
            if (!ob.screenOk) return fail("obserwator: ekran XTB niepewny (${ob.warnings.firstOrNull() ?: "brak szczegółów"})")
            ob.instrument?.let { seen ->
                if (!sameInstrument(seen, i.instrument)) return fail("obserwator widzi $seen zamiast ${i.instrument}")
            }
            val mid = when {
                ob.bid != null && ob.ask != null -> (ob.bid + ob.ask) / 2
                else -> ob.bid ?: ob.ask
            }
            val slDist = abs(o.referencePrice - o.stopPrice)
            if (mid != null && slDist > 0) {
                val botXtb = i.priceNow + i.xtbShift
                if (abs(mid - botXtb) > maxOf(1.0, 0.3 * slDist))
                    return fail("cena na ekranie %.2f ≠ cena bota %.2f – dane się rozjechały".format(mid, botXtb))
            }
        }

        if (i.skeptic.verdict == SkepticVerdict.VETO) return fail("WETO Sceptyka: ${i.skeptic.reasons.firstOrNull() ?: "bez powodu"}")
        val c = i.commander
        if (!c.execute) return fail("Dowódca: nie wykonuję – ${c.summaryPl}")
        if (c.side != o.side) return fail("Dowódca podał inny kierunek (${c.side}) niż kandydat (${o.side}) – odrzucam")
        if (c.sl == null || abs(c.sl - o.stopPrice) > PRICE_EPS) return fail("Dowódca przekręcił SL (${c.sl} vs ${"%.2f".format(o.stopPrice)})")
        val tp = o.takeProfit
        if (tp != null && (c.tp == null || abs(c.tp - tp) > PRICE_EPS)) return fail("Dowódca przekręcił TP (${c.tp} vs ${"%.2f".format(tp)})")

        val slDist = abs(o.referencePrice - o.stopPrice)
        if (slDist <= 0) return fail("kandydat bez odległości SL")
        if (tp != null) {
            val rr = abs(tp - o.referencePrice) / slDist
            if (rr + 1e-9 < MIN_RR) return fail("R:R 1:%.2f < 1:%.1f".format(rr, MIN_RR))
        }
        val drift = (i.priceNow - o.referencePrice) * o.side.sign
        if (abs(drift) > MAX_DRIFT_OF_SL * slDist)
            return fail("cena uciekła o %.2f w trakcie debaty (limit %.2f)".format(abs(drift), MAX_DRIFT_OF_SL * slDist))

        val factor = if (i.skeptic.verdict == SkepticVerdict.ADJUST) i.skeptic.volumeFactor.coerceIn(0.5, 1.0) else 1.0
        val cap = floorStep(o.quantity * factor)
        val asked = c.volume ?: return fail("Dowódca nie podał wolumenu")
        if (asked > o.quantity + 1e-9) return fail("Dowódca chciał ZWIĘKSZYĆ wolumen (%.3f > %.3f) – odrzucam".format(asked, o.quantity))
        val vol = floorStep(minOf(asked, cap))
        if (vol + 1e-9 < MIN_LOT) return fail("po redukcji wolumen %.3f < %.3f".format(vol, MIN_LOT))

        return Result.success(o.copy(quantity = vol, reason = o.reason + " • RADA AI ✓"))
    }

    fun floorStep(v: Double, step: Double = LOT_STEP): Double = round(floor((v + 1e-9) / step) * step * 1000) / 1000

    fun sameInstrument(a: String, b: String): Boolean {
        fun n(s: String) = s.uppercase().replace(Regex("[^A-Z]"), "")
        val x = n(a); val y = n(b)
        val gold = setOf("GOLD", "XAUUSD", "XAU")
        return x == y || (x in gold && y in gold) || (x.contains("GOLD") && y.contains("GOLD"))
    }

    fun sideName(s: Side) = if (s == Side.BUY) "LONG" else "SHORT"
}
