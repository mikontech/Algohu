package com.example.algohud.council

import com.example.algohud.core.Side
import org.json.JSONArray
import org.json.JSONObject

/** Tolerancyjne parsowanie odpowiedzi agentów. Brakujące/złe pola → wartość bezpieczna (ostrożna). */
internal object CouncilParsing {
    private fun JSONObject.num(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k).takeIf { !it.isNaN() }
    private fun JSONObject.strList(k: String, max: Int = 6): List<String> {
        val a = optJSONArray(k) ?: JSONArray()
        return (0 until minOf(a.length(), max)).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }
    }

    fun observer(j: JSONObject) = ObserverReport(
        instrument = j.optString("instrument").takeIf { it.isNotBlank() && it != "null" },
        bid = j.num("bid"), ask = j.num("ask"),
        screenOk = j.optBoolean("screen_ok", false),
        openPositions = j.strList("open_positions"),
        priceAction = j.optString("price_action"),
        warnings = j.strList("warnings"),
        raw = j.toString()
    )

    fun strategist(j: JSONObject) = StrategistView(
        stance = runCatching { Stance.valueOf(j.optString("stance").uppercase()) }.getOrDefault(Stance.NEUTRALNIE),
        confidence = (j.num("confidence") ?: 0.0).coerceIn(0.0, 1.0),
        regime = j.optString("regime", "niejasny"),
        trapRisk = j.optString("trap_risk", "średnie"),
        arguments = j.strList("arguments", 4) + listOfNotNull(j.optString("reply_to_skeptic").takeIf { it.isNotBlank() && it != "null" }),
        raw = j.toString()
    )

    fun skeptic(j: JSONObject): SkepticView {
        val v = runCatching { SkepticVerdict.valueOf(j.optString("verdict").uppercase()) }.getOrDefault(SkepticVerdict.VETO)
        return SkepticView(v, (j.num("volume_factor") ?: 1.0).coerceIn(0.5, 1.0), j.strList("reasons", 4), j.toString())
    }

    fun commander(j: JSONObject) = CommanderOrder(
        execute = j.optBoolean("execute", false),
        side = when (j.optString("side").uppercase()) { "BUY" -> Side.BUY; "SELL" -> Side.SELL; else -> null },
        volume = j.num("volume"), sl = j.num("sl"), tp = j.num("tp"),
        summaryPl = j.optString("summary_pl").ifBlank { "—" },
        raw = j.toString()
    )
}
