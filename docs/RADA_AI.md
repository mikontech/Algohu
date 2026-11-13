# Rada AI (4 agenty) – nakładka na Algo HUD

## Gdzie siedzi w bocie

Rada **nie zastępuje** strategii ani RiskManagera. Wchodzi jako bramka między gotowym kandydatem na wejście
a `engine.submit()`. Wyjścia, schodki SL, zamknięcia na przeciwny trend i kill-switch idą jak dotąd – bez AI.

```mermaid
flowchart TD
    XTB[XTB na ekranie] -->|drzewo widoków| AES[AutoExecutionService<br/>ScreenScanner]
    PYTH[Feed Pyth/Binance] --> BO
    AES -->|ScreenQuote| BO[BotOrchestrator<br/>SMC · RT · ORB · ZAKRES · TURNIEJ · US OPEN]
    BO --> BUILD[buildOrder<br/>Sizing · SL 15–20 zł · TP 2,5R · traderCheck]
    BUILD -->|kandydat OrderRequest| RADA

    subgraph RADA [AgentCouncil – max ~40 s]
      OBS[👁️ Obserwator<br/>Haiku + zrzut ekranu] --> S1[🌍 Strateg R1]
      OBS --> K1[🛡️ Sceptyk R1]
      S1 --> S2[🌍 Strateg R2<br/>odpowiada Sceptykowi]
      K1 --> S2
      S1 --> K2[🛡️ Sceptyk R2<br/>werdykt + WETO]
      K1 --> K2
      S2 --> CMD[🎖️ Dowódca<br/>przepisuje side/SL/TP, może tylko zmniejszyć lot]
      K2 --> CMD
    end

    CMD --> GATE[RiskGate – Kotlin, bez AI<br/>side/SL/TP bez zmian · lot ≤ kandydat · R:R ≥ 2,5<br/>ekran = dane bota · poślizg ≤ 0,25 SL · debata ≤ 45 s]
    GATE -->|OK| ENG[ExecutionEngine.submit<br/>RiskManager → tryb DRY_RUN/CONFIRM/AUTO]
    GATE -->|WETO| LOG[HUD + ntfy: powód]
    ENG --> AES2[AutoExecutionService klika w XTB]
    KILL[⛔ KILL-SWITCH HUD] -.->|cancelAll| RADA
    KILL -.-> ENG
```

## Tryby rady
- `OFF` – wyłączona.
- `ADVISORY` (domyślnie) – debatuje i loguje werdykt, ale bot wchodzi jak dotąd. Do porównania: czy weta rady
  trafiają w przegrane transakcje.
- `GATE` – weto blokuje wejście, ADJUST zmniejsza lot (min. 0.006). Błąd/timeout API = weto (fail-closed).

## Dlaczego zrzut przez AccessibilityService, a nie MediaProjection
`takeScreenshot()` (Android 11+) działa w już włączonej usłudze ułatwień dostępu – bez okna zgody przy każdym
starcie (Android 14+ wymaga nowej zgody MediaProjection za każdym razem) i bez nowej usługi pierwszoplanowej.
Manifest bez zmian; jedyna zmiana: `android:canTakeScreenshot="true"` w `accessibility_config.xml`
(po aktualizacji APK może być trzeba wyłączyć i włączyć usługę Algo HUD w Ułatwieniach dostępu).
Jeśli XTB blokuje zrzuty (FLAG_SECURE), Obserwator dostaje tekst drzewa widoków (`dumpTargetWindow()`).

## Krok 2: podpięcie, świadomość, samonaprawa widoku

- **Podpięcie:** wszystkie nowe wejścia (`handleSignal`, `smcTick`, `enterAggressive` → RT/ORB/ZAKRES/AI-świece/US OPEN)
  idą przez `BotOrchestrator.submitEntry()`. W GATE debata leci w tle – pętla cen, wyjścia i schodki SL nie czekają.
  Po werdykcie: ponowna kontrola (kill-switch? już w pozycji?) i dopiero `engine.submit`. Weto = 3 min przerwy dla kierunku.
- **Świadomość (omni-awareness):** `BotOrchestrator.awareness()` – tryb, kill-switch, wynik dnia, feed, usługa ułatwień
  dostępu, aplikacja na wierzchu, ekran błędu XTB, status samonaprawy, ostatni odczyt XTB i środki, pozycje w księdze bota,
  wiersze pozycji widoczne w XTB, bramka danych, przerwa po stracie, ntfy, ostatni alarm, 6 ostatnich zdarzeń bota.
  Trafia do wszystkich agentów. Pozycja w XTB spoza księgi bota = weto RiskGate (jedna pozycja naraz).
- **Samonaprawa widoku we wszystkich trybach** (`navAutonomy`, domyślnie włączona; zlecenia dalej tylko w AUTO):
  XTB wraca na wierzch po 20 s (nie z Ustawień, instalatora, Algo HUD, okien systemowych; AnyDesk – po 2 min),
  bot wraca na bilet GOLD, zamyka wiszące okna, naciska „Odśwież” na ekranie błędu.
  Pauza: przycisk ⏸ w HUD (10 min) albo w aplikacji (30 min).
- **Nawigator AI** (zapas): gdy zwykłe szukanie GOLD zawiedzie – zrzut → Haiku wskazuje gdzie dotknąć → bot sprawdza bilet.
  Maks. 5 kroków / 45 s, maks. 6 uruchomień na godzinę. Odmawia dotyku poza XTB i na przyciskach Sell/Buy/Potwierdź/Zamknij…
- **Kill-switch:** przerywa debatę i nawigację AI (także zapytanie HTTP w locie), zwalnia blokadę UI dla zamykania pozycji.

## Klucz API
Klucz Anthropic wpisuje się w aplikacji (prefs `council_api_key`). Nie wolno go wkładać do kodu ani do sekretów
GitHub Actions – APK z artefaktów publicznego repo może pobrać każdy.

## Dokładniejsza obserwacja świec

`CandleWatch` liczy geometrię OHLC i relacje świec z istniejącego feedu bota. M1 i M5
są aktualizowane tym samym tickiem przed oceną strategii. Lokalny HUD w Szczegółach
odświeża podsumowanie co sekundę; częstotliwość źródła cen pozostaje bez zmian.
Rada otrzymuje snapshot przy kandydacie na wejście, a nie wywołanie API co sekundę.

Raport zawiera 30 zamkniętych M1 i 24 M5 z czasem UTC, korpusy, knoty, położenie
zamknięcia w zakresie, geometrię ostatnich sześciu świec, objęcia, inside/outside bar,
zamknięcia poza H/L poprzedniej świecy i powroty do jej zakresu. Świeca w budowie ma
osobną sekcję: upływ czasu, czas do końca i wstępne relacje. Nie jest potwierdzonym sygnałem.

Oznaczane są luki, błędny OHLC, nieaktualna świeca oraz wiek ceny powyżej 10 sekund
(próg informacyjny, nie dodatkowa bramka wykonania). Brakujące świece nie są uzupełniane.
Nie ma danych o wolumenie. OHLC feedu nie musi pokrywać się z OHLC XTB; przesunięcie
cen nie gwarantuje zgodności knotów. Analiza nie zmienia parametrów ryzyka ani sama
nie otwiera/zamyka pozycji. W GATE stanowi dodatkowe dane do oceny rady.
