package com.example.algohud.bot

import java.time.LocalDate

/**
 * JEDYNY plik do strojenia bota. Wszystkie progi, wagi i limity są tutaj – reszta kodu tylko je czyta.
 * Po zmianie wartości zbuduj APK od nowa (./gradlew assembleDebug albo GitHub Actions „Build APK”).
 * Logika, która z nich korzysta, jest czysta (bez Androida) i ma testy w app/src/test.
 */
object BotConfig {

    /** Zasady stałe: wolumen, SL w złotówkach, R:R, dzienny bezpiecznik. */
    object Risk {
        /** Stały wolumen każdego zlecenia (loty XTB). */
        const val FIXED_LOT = 0.007
        /** Strata na SL: zawsze w przedziale 15–20 PLN. */
        const val SL_MIN_PLN = 15.0
        const val SL_MAX_PLN = 20.0
        /** TP = TP_R × odległość SL (min. R:R 1:2,5). */
        const val TP_R = 2.5
        /** Ostrzeżenie w HUD od takiej dziennej straty (ułamek kapitału z początku dnia). */
        const val DAILY_WARN_PCT = 0.10
        /** Kill-switch: od takiej dziennej straty bot zamyka pozycje i nie otwiera nowych do następnego dnia. */
        const val DAILY_KILL_PCT = 0.25
    }

    /** Dane do analizy. */
    object Data {
        /** Ile świec M5 trzymać w pamięci (3000 ≈ 250 świec H1 – tyle potrzeba na EMA 200 z H1). */
        const val M5_KEEP_BARS = 3000
    }

    /** SignalEngine: wagi warstw i progi decyzji LONG / SHORT / BRAK. */
    object Signal {
        // ── Warstwa 1: trend wyższego interwału (H1 i M15 składane z M5) ──
        const val W_H1_STRUCTURE = 1.5   // HH/HL (long) albo LH/LL (short) na H1
        const val W_H1_EMA = 1.0         // EMA50 nad/pod EMA200 na H1 (przy krótkiej historii: cena vs EMA50)
        const val W_H1_SLOPE = 0.5       // nachylenie EMA50 na H1
        const val W_M15_STRUCTURE = 0.5
        const val W_M15_EMA = 0.5
        const val EMA_FAST = 50
        const val EMA_SLOW = 200
        /** Nachylenie liczone na tylu świecach wstecz; liczy się dopiero powyżej SLOPE_MIN_ATR × ATR. */
        const val SLOPE_BARS = 5
        const val SLOPE_MIN_ATR = 0.15
        /** |wynik trendu| ≥ tyle punktów = trend ↑/↓, mniej = brak trendu. */
        const val HTF_TREND_MIN = 1.0
        /** Czy wolno wchodzić, gdy wyższy interwał nie ma trendu (false = tylko po wyraźnym CHoCH). */
        const val ALLOW_NEUTRAL_HTF = false

        // ── Warstwa 2: momentum na interwale wejścia (M1 / M5) ──
        const val W_M5_RSI = 0.75
        const val W_M5_MACD = 0.75
        const val W_M1_MACD = 1.0
        const val W_M1_RSI = 0.5
        const val RSI_PERIOD = 14
        const val RSI_BULL = 55.0        // RSI ≥ 55 = punkty dla longa
        const val RSI_BEAR = 45.0        // RSI ≤ 45 = punkty dla shorta
        const val RSI_OVERBOUGHT = 78.0  // powyżej nie dajemy punktów longowi (nie gonimy szczytu)
        const val RSI_OVERSOLD = 22.0
        const val MACD_FAST = 12
        const val MACD_SLOW = 26
        const val MACD_SIGNAL = 9

        // ── Warstwa 3: price action / SMC na M1 ──
        const val W_BOS = 1.5            // wybicie struktury zgodne z kierunkiem
        const val W_CHOCH = 2.0          // zmiana charakteru (pierwsze wybicie przeciw dotychczasowej strukturze)
        const val W_SWEEP = 1.5          // zebranie płynności pod dołkiem / nad szczytem i powrót
        const val W_REJECTION = 1.0      // odrzucenie od strefy popytu / podaży
        /** BOS/CHoCH liczy się przez tyle świec M1 od wybicia. */
        const val STRUCTURE_FRESH_BARS = 10
        /** Sweep: knot w ostatnich SWEEP_RECENT świecach poza ekstremum z SWEEP_LOOKBACK świec wcześniej. */
        const val SWEEP_RECENT = 5
        const val SWEEP_LOOKBACK = 30
        /** Strefa popytu/podaży = dolne/górne ZONE_FRACTION zakresu z ZONE_LOOKBACK świec M1. */
        const val ZONE_LOOKBACK = 60
        const val ZONE_FRACTION = 0.25
        /** Odrzucenie: knot ≥ tyle zasięgu świecy. */
        const val REJECTION_WICK = 0.5

        // ── Progi decyzji ──
        /** Własne wejście SignalEngine („SCORE”): łączny wynik strony ≥ tyle… */
        const val ENTRY_SCORE = 5.5
        /** …i przewaga nad stroną przeciwną ≥ tyle. */
        const val ENTRY_LEAD = 2.0
        /** Potwierdzenie wejść pozostałych strategii (RT, ORB, ZAKRES, SMC, TURNIEJ): wynik strony ≥ tyle… */
        const val CONFIRM_SCORE = 4.0
        /** …i przewaga ≥ tyle. */
        const val CONFIRM_LEAD = 1.0
        /** Po wejściu SCORE kolejne najwcześniej po tylu świecach M1. */
        const val ENTRY_COOLDOWN_BARS = 5
        /** SL wejścia SCORE w ATR(M1) – i tak dociągany do 15–20 PLN. */
        const val ENTRY_STOP_ATR = 2.0

        // ── Filtry ──
        /** Spread większy niż tyle × ATR(M5) = brak wejścia. */
        const val MAX_SPREAD_ATR_M5 = 0.5
        /** Bezwzględny limit spreadu w jednostkach ceny (0 = wyłączony). */
        const val MAX_SPREAD_ABS = 0.0
        /** ATR(M1) względem swojej średniej z ATR_BASE_BARS świec: poza [MIN, MAX] = brak wejścia. */
        const val ATR_BASE_BARS = 240
        const val ATR_RATIO_MIN = 0.4
        const val ATR_RATIO_MAX = 3.0
    }

    /** Wyjście przy POTWIERDZONYM odwróceniu tendencji (jedyny wyjątek od „zamyka tylko TP/SL”). */
    object Reversal {
        const val ENABLED = true
        /** true = warunki liczone na świecach M5 (wolniej, mniej fałszywych alarmów); false = na M1. */
        const val USE_M5 = false
        /** Ile z 3 warunków musi być spełnionych naraz. */
        const val MIN_CONDITIONS = 2
        /** Przez ile kolejnych ZAMKNIĘTYCH świec warunki muszą się utrzymać. */
        const val CONFIRM_BARS = 2
        /** Ruch przeciw pozycji (od najlepszej ceny / wejścia) mniejszy niż tyle × ATR to szum – nie zamykamy. */
        const val MIN_MOVE_ATR = 0.5
        /** ATR do progu szumu liczony z M5 (true) – jedna świeca M1 nie wystarczy, żeby go przekroczyć. */
        const val NOISE_ATR_FROM_M5 = true
        /** Warunek (c): wynik strony przeciwnej ≥ tyle i większy od wyniku strony pozycji o SCORE_LEAD. */
        const val SCORE = 5.0
        const val SCORE_LEAD = 1.5
        const val EMA_FAST = 20
        const val EMA_SLOW = 50
        /** Nie oceniamy odwrócenia wcześniej niż po tylu zamkniętych świecach od wejścia. */
        const val MIN_BARS_IN_TRADE = 2
        /** Gdy świeca potwierdzająca zamyka się lepiej dla pozycji niż pierwsza świeca serii o więcej niż tyle × ATR – to odbicie, nie zamykamy. */
        const val MAX_BOUNCE_ATR = 0.15
    }

    /** Przesuwanie SL/TP w otwartej pozycji. */
    object Trailing {
        enum class Mode { SWING, ATR, TIGHTER }
        /** SL na wejście (+spread) po zysku ≥ tyle R. */
        const val BE_AT_R = 1.0
        /** Dalsze prowadzenie SL od zysku ≥ tyle R. */
        const val TRAIL_FROM_R = 1.5
        /** SWING = za ostatni swing low/high, ATR = k×ATR za najlepszą ceną, TIGHTER = bliższy z obu. */
        val MODE = Mode.SWING
        const val ATR_K = 2.0
        /** Zapas za swingiem w ATR(M1). */
        const val SWING_BUFFER_ATR = 0.2
        /** SL nie bliżej bieżącej ceny niż max(2× spread, tyle × ATR) – inaczej XTB odrzuci albo wybije szum. */
        const val MIN_GAP_ATR = 0.5
        /** Impuls: świeca M1 w stronę pozycji o zasięgu ≥ tyle × ATR… */
        const val IMPULSE_ATR = 1.5
        /** …z korpusem ≥ tyle zasięgu… */
        const val IMPULSE_BODY = 0.6
        /** …oraz rosnąca aktywność ticków (≥ tyle × średnia), rosnący histogram MACD albo wybicie struktury. */
        const val IMPULSE_TICK_RATIO = 1.2
        const val IMPULSE_BREAK_BARS = 10
        /** TP przesuwamy dopiero przy zysku ≥ tyle R. */
        const val TP_EXTEND_FROM_R = 1.0
        /** Nowy TP: najbliższa strefa płynności za obecnym TP, jeśli nie dalej niż tyle × ATR(M5)… */
        const val TP_LEVEL_MAX_ATR = 4.0
        /** …w przeciwnym razie obecny TP + tyle × ATR(M5). */
        const val TP_EXTEND_ATR = 1.5
        /** Zmiana w XTB najwyżej raz na tyle ms. */
        const val MODIFY_MIN_INTERVAL_MS = 45_000L
        /** Do XTB wysyłamy zmianę dopiero, gdy SL/TP różni się od zapisanego o ≥ tyle PLN. */
        const val MODIFY_MIN_STEP_PLN = 2.0
        /** Po tylu nieudanych próbach z rzędu: alert w HUD (bot dalej pilnuje SL sam). */
        const val MAX_FAILURES_BEFORE_ALERT = 2
        /** Ile ostatnich przesunięć pokazać w HUD. */
        const val HISTORY_IN_HUD = 4
    }

    /** Sesja „US Open” – otwarcie giełdy w Nowym Jorku (9:30 czasu NY, niezależnie od zmian czasu w Polsce). */
    object UsOpen {
        const val ENABLED = true
        const val ZONE = "America/New_York"
        const val OPEN_HOUR = 9
        const val OPEN_MINUTE = 30
        /** Okno trybu: od tylu minut przed otwarciem… */
        const val WINDOW_BEFORE_MIN = 5L
        /** …do tylu minut po otwarciu. */
        const val WINDOW_AFTER_MIN = 60L
        /** Zakres otwarcia budowany z pierwszych tylu minut (5–15). */
        const val RANGE_MIN = 5L
        /** Bez żadnych wejść przez tyle minut od otwarcia (największy spread i poślizg). */
        const val NO_ENTRY_MIN = 2L
        /** SL za przeciwną krawędzią zakresu + zapas w ATR(M1). */
        const val STOP_BUFFER_ATR = 0.2
        /** Ile wejść ORB na jedną sesję. */
        const val MAX_TRADES = 1
        /** Dni bez sesji w USA (święta NYSE, cały dzień). Źródło: nyse.com/markets/hours-calendars. Dopisz kolejne lata. */
        val HOLIDAYS: Set<LocalDate> = listOf(
            "2026-01-01", "2026-01-19", "2026-02-16", "2026-04-03", "2026-05-25", "2026-06-19", "2026-07-03",
            "2026-09-07", "2026-11-26", "2026-12-25",
            "2027-01-01", "2027-01-18", "2027-02-15", "2027-03-26", "2027-05-31", "2027-06-18", "2027-07-05",
            "2027-09-06", "2027-11-25", "2027-12-24",
            "2028-01-17", "2028-02-21", "2028-04-14", "2028-05-29", "2028-06-19", "2028-07-04", "2028-09-04",
            "2028-11-23", "2028-12-25"
        ).map(LocalDate::parse).toSet()
    }

    /** Dziennik decyzji (plik CSV w pamięci telefonu). */
    object Log {
        const val ENABLED = true
        /** Ile dni plików trzymać. */
        const val KEEP_DAYS = 14
    }
}
