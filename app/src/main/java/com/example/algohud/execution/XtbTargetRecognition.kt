package com.example.algohud.execution

/** Rozpoznanie biletu niezależne od chwilowej widoczności notowań. */
object XtbTargetRecognition {
    val orderControlIds = listOf("TradingBuyButton", "TradingSellButton", "MarketSizeInput")
    val orderControlLabels = listOf("Zlecenie Market", "Market Order")

    /** Rozpoznanie ekranu nie zależy od ceny ani enabled przycisków handlu. */
    fun hasOrderControls(visibleIds: List<String>, visibleLabels: List<String>): Boolean =
        visibleIds.any { it in orderControlIds } || visibleLabels.any { it in orderControlLabels }

    fun matches(pkg: String?, title: String?, target: String, ticketMarkers: Boolean): Boolean {
        if (pkg != "com.xtb.xmobile2" || !ticketMarkers || title == null) return false
        val normalized = title.trim().replace(Regex("\\s+"), " ")
        if (normalized.contains("24/7")) return false
        return normalized.substringBefore(' ').equals(target, ignoreCase = true)
    }
}
