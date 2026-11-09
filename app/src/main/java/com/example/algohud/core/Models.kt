package com.example.algohud.core

import java.util.UUID

enum class Side {
    BUY, SELL;
    val sign: Int get() = if (this == BUY) 1 else -1
    fun opposite(): Side = if (this == BUY) SELL else BUY
}

/** DRY_RUN = paper trading, CONFIRM = bot proponuje, człowiek zatwierdza, AUTO = pełna automatyka. */
enum class ExecutionMode { DRY_RUN, CONFIRM, AUTO }

data class Tick(val symbol: String, val price: Double, val timestamp: Long)

data class NewsItem(
    val id: String,
    val title: String,
    val source: String,
    val publishedAt: Long,
    val sentiment: Double
)

data class MacroEvent(
    val title: String,
    val currency: String,
    val timeMillis: Long,
    val impact: Impact
) {
    enum class Impact { LOW, MEDIUM, HIGH }
}

data class OrderRequest(
    val symbol: String,
    val side: Side,
    val quantity: Double,
    val referencePrice: Double,
    val stopPrice: Double,
    val reason: String,
    val clientOrderId: String = "hud" + UUID.randomUUID().toString().replace("-", "").take(16)
)

sealed interface ExecutionResult {
    data class Filled(val orderId: String, val avgPrice: Double, val quantity: Double) : ExecutionResult
    /** Automatyzacja UI: kliknięto, ale wykonania nie da się potwierdzić programowo. */
    data class Submitted(val note: String) : ExecutionResult
    data class Rejected(val reason: String) : ExecutionResult
    /** Stan nieznany (timeout, błąd po kliknięciu) – silnik włącza kill-switch. */
    data class Failed(val error: Throwable) : ExecutionResult
}

data class Position(
    val symbol: String,
    val side: Side,
    val quantity: Double,
    val entryPrice: Double,
    val stopPrice: Double
)

/** Co jest teraz widoczne w aplikacji brokera (odczytane przez AccessibilityService). */
data class ScreenQuote(
    val packageName: String,
    val appLabel: String,
    val instrument: String?,
    val bid: Double?,
    val ask: Double?,
    val timestamp: Long
) {
    val mid: Double?
        get() = if (bid != null && ask != null) (bid + ask) / 2 else bid ?: ask

    fun isFresh(now: Long = System.currentTimeMillis(), maxAgeMs: Long = 5_000) = now - timestamp <= maxAgeMs
}

data class HudState(
    val sentiment: Double = 0.0,
    val lastPrice: Double? = null,
    val symbol: String = "—",
    val mode: ExecutionMode = ExecutionMode.DRY_RUN,
    val killSwitch: Boolean = false,
    val dailyPnl: Double = 0.0,
    val feedConnected: Boolean = false,
    val screen: ScreenQuote? = null,
    val screenSentiment: Double = 0.0,
    val screenNews: Int = 0,
    val lastMessage: String = "Uruchamianie…"
)
