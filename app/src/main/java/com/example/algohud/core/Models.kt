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

/** Świeca OHLC; [start] = początek okresu (ms). */
data class Bar(val start: Long, val open: Double, val high: Double, val low: Double, val close: Double)

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
    /** Zmiana wartości (w walucie konta) przy ruchu ceny o 1,0 dla ilości 1 (dla CFD: 1 lot). */
    val pointValue: Double = 1.0,
    /** Zlecenie zamykające – nie może otworzyć nowej pozycji. */
    val reduceOnly: Boolean = false,
    val takeProfit: Double? = null,
    /** Poziom agresji: 0 zredukowany, 1 umiarkowany, 2 agresywny (wpływa na ryzyko i dźwignię). */
    val tier: Int = 1,
    /** Stały wolumen (np. 0.006) – bez skalowania wg % ryzyka i limitu dźwigni poziomu agresji. */
    val fixedSize: Boolean = false,
    val directionPrepared: Boolean = false,
    val expectedPositionAt: Long? = null,
    val signalAt: Long = System.currentTimeMillis(),
    val brokerReferencePrice: Double? = null,
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
    val stopPrice: Double,
    val pointValue: Double = 1.0,
    /** Pozycja papierowa (DRY_RUN) – nie istnieje u brokera. */
    val paper: Boolean = false,
    /** Pierwotny stop – definiuje 1R (stopPrice może być przesuwany przez trailing). */
    val initialStop: Double = stopPrice,
    val takeProfit: Double? = null,
    /** Najlepsza cena od otwarcia (do trailing stopu). */
    val best: Double = entryPrice,
    /** Powód wejścia (np. "RT impuls…", "ORB…") – do statystyk w dzienniku. */
    val entryReason: String = "",
    val openedAt: Long = 0L
)

/** Co jest teraz widoczne w aplikacji brokera (odczytane przez AccessibilityService). */
data class ScreenQuote(
    val packageName: String,
    val appLabel: String,
    val instrument: String?,
    val bid: Double?,
    val ask: Double?,
    val timestamp: Long,
    val availableFunds: Double? = null,
    val accountCurrency: String? = null,
    val volume: Double? = null,
    /** "Wartość kontraktu" w walucie konta dla wolumenu z pola [volume]. */
    val contractValueAcct: Double? = null
) {
    val mid: Double?
        get() = if (bid != null && ask != null) (bid + ask) / 2 else bid ?: ask

    /** Ile (w walucie konta) zmienia się wartość 1 lota przy ruchu ceny o 1,0. */
    val pointValuePerLot: Double?
        get() {
            val v = volume ?: return null
            val c = contractValueAcct ?: return null
            val m = mid ?: return null
            return if (v > 0 && m > 0) c / v / m else null
        }

    fun isFresh(now: Long = System.currentTimeMillis(), maxAgeMs: Long = 8_000) = now - timestamp in 0..maxAgeMs
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
    val paperStats: String = "",
    val profile: String = "",
    /** Czytelny, wielolinijkowy stan bota (prawdziwe wartości z XTB i księgi bota). */
    val status: String = "",
    val learning: String = "",
    /** Bieżący monitoring M1/M5, aktualizowany lokalnie co sekundę. */
    val candles: String = "",
    /** Jedna linia: czy dane są wiarygodne i jak świeże (XTB / feed). */
    val dataStatus: String = "",
    /** Plan bota: LONG/SHORT/czekam, na co czeka, kiedy zamknie. */
    val plan: String = "",
    /** Predykcja następnej świecy + status uczenia modelu (sekcja w HUD). */
    val prediction: String = "",
    /** true = wzrostowa (zielona), false = spadkowa (czerwona), null = brak prognozy. */
    val predictionUp: Boolean? = null,
    /** Uproszczony widok HUD: pozycja (wejście, świeca, cena, kierunek), „co dalej”, ostatnie transakcje, alarmy. */
    val compact: String = "",
    val lastMessage: String = "Uruchamianie…"
)
