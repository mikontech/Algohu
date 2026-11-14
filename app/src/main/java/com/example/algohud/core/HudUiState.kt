package com.example.algohud.core

/** Small, immutable rendering projection. Prices never drive operation semantics. */
data class HudUiState(
    val status: String = "CZEKAM", val protection: String = "SL: —", val freshness: String = "XTB: brak odczytu",
    val spread: String = "SPREAD: brak kandydata", val budget: String = "BUDŻET: brak danych",
    val severity: Int = 0, val paused: Boolean = false, val closing: Boolean = false,
    val positionSymbol: String? = null, val positionAt: Long? = null, val eventKey: String = ""
)
