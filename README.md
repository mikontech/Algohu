# Algo HUD

Aplikacja na Androida z pływającą nakładką (HUD), która analizuje złoto (GOLD / XAU/USD)
i potrafi sama składać zlecenia w aplikacji XTB przez usługę ułatwień dostępu.

> **Domyślnie tryb DRY_RUN (paper trading).** Żadne prawdziwe zlecenie nie zostanie wysłane,
> dopóki ręcznie nie przełączysz trybu na AUTO w aplikacji Algo HUD.

> **To nie jest porada inwestycyjna.** Handel CFD z dźwignią wiąże się z wysokim ryzykiem utraty
> środków. Automatyzacja opiera się na klikaniu w interfejs XTB – przed użyciem na prawdziwym
> koncie sprawdź regulamin brokera i przeczytaj sekcję [Znane ograniczenia](#znane-ograniczenia).

Wersja: `0.1.0` • Android 8.0+ (minSdk 26, targetSdk 35) • Kotlin, bez bibliotek UI poza AndroidX.

## Jak to działa

```
ceny (Pyth / Binance / ekran XTB) → świece 1m i 5m → strategie → bramka jakości danych
    → RiskManager → ExecutionEngine → klikanie w bilet XTB (AccessibilityService)
```

1. **Dane.** Cena złota pochodzi z Pyth Network (spot XAU/USD, bez klucza API), a gdy Pyth nie
   odpowiada lub ma za krótką historię – z Binance (PAXGUSDT, historia przesuwana do poziomu
   ceny spot). Kwotowania Buy/Sell, instrument, dostępne środki i wartość kontraktu bot czyta
   z ekranu XTB.
2. **Strategie.** Wszystkie cztery działają jednocześnie; pierwsza z sygnałem otwiera pozycję,
   a jednocześnie może być otwarta tylko jedna.
3. **Bramka jakości danych.** Przed każdym nowym wejściem bot sprawdza, czy dane są świeże
   i spójne (szczegóły niżej).
4. **Ryzyko i wykonanie.** Każde zlecenie przechodzi przez `RiskManager`, a potem – w trybie
   AUTO – jest wpisywane do biletu XTB: najpierw SL/TP, potem wolumen i przycisk Buy/Sell.

## Tryby pracy

| Tryb | Co robi | Jak włączyć |
|---|---|---|
| `DRY_RUN` | Symulacja. Bez odczytu środków z XTB używa konta papierowego 10 000. | Domyślny; przycisk w aplikacji |
| `AUTO` | Bot sam składa zlecenia w XTB. Wymaga włączonej usługi ułatwień dostępu. | Przycisk w aplikacji |
| `CONFIRM` | Bot proponuje, człowiek zatwierdza przyciskiem „Potwierdź” w HUD. | Jest w kodzie, ale ekran aplikacji nie ma dla niego przycisku |

Wybrany tryb jest zapamiętywany i przywracany po restarcie usługi.

## Strategie

| Strategia | Opis | Plik |
|---|---|---|
| SMC XAU | Sweep płynności (Asia High/Low, PDH/PDL, swingi z 60 min) → CHoCH na M1, SL za ekstremum sweepu, TP na przeciwnej płynności, kontekst H1. Tylko dla GOLD/XAU. Maks. 3 wejścia na sesję. | `bot/SmcEngine.kt` |
| RT / ORB | Impuls świecy 1m w czasie rzeczywistym i wybicie z zakresu otwarcia (pierwsze 5 min, aktywne 15 min). | `bot/BotOrchestrator.kt` |
| Zakres | Kupno po odbiciu od dolnych 20% zakresu z 30 świec 1m, sprzedaż przy górze; wyłączona w silnym trendzie. | `bot/BotOrchestrator.kt` |
| Turniej | Warianty strategii (5m i 1m) handlują „na sucho”; handluje tylko zwycięzca z dodatnim, statystycznie istotnym wynikiem. | `bot/Learning.kt` |

Model predykcji następnej świecy (`bot/NextCandlePredictor.kt`, regresja logistyczna ucząca się
online) jest obecnie **tylko informacją w HUD** – nie otwiera pozycji.

### Ocena strategii na bieżąco (pomiar „na sucho”)

Każdy sygnał strategii SMC, RT, ORB i Zakres, który przeszedł filtry, jest dodatkowo prowadzony
„na sucho” – z tym samym SL, TP i prowadzeniem stopu co prawdziwa pozycja, z wejściem i wyjściem
po gorszej stronie spreadu (`bot/StrategyProof.kt`). O prawdziwych zleceniach (AUTO/CONFIRM)
decyduje stała `PROOF_POLICY` w `BotOrchestrator.kt`:

| Wartość | Zachowanie |
|---|---|
| `LIVE` (domyślna) | Strategia handluje od razu. Gdy średnia z ostatnich 20 prób (liczona od 10) jest ujemna, strategia jest wstrzymana i tylko mierzy; wraca sama, gdy średnia przestanie być ujemna. |
| `STRICT` | Zlecenia dopiero po ≥ 30 próbach, średniej ≥ +0,10R i wyniku istotnym statystycznie (t ≥ 2) z ostatnich 100 prób. |
| `OFF` | Bez bramki, sam pomiar. |

Stan każdej strategii widać w planie na HUD (`🧪 Wyniki strategii`). Wyniki są trwałe i przeżywają
restart. Tryb `DRY_RUN` działa bez zmian. Turniej ma własną bramkę.

Odtworzenie reguł wejść na miesiącu świec 1m złota (`tools/replay/`) dało wynik zbliżony do
losowych wejść z tym samym SL/TP, czyli średnio stratę rzędu spreadu na transakcji. Tryb `LIVE`
zmniejszył w tej symulacji liczbę transakcji o ok. połowę, ale nie poprawił wyniku na transakcję.

### SignalEngine – jedna decyzja LONG / SHORT / BRAK

`bot/SignalEngine.kt` łączy trzy warstwy w wynik punktowy (score) osobno dla LONG i SHORT. Liczy się
przy każdym zamknięciu świecy M1:

| Warstwa | Co liczy |
|---|---|
| Trend wyższego interwału (H1, M15 – składane ze świec M5) | struktura HH/HL vs LH/LL, EMA 50/200, nachylenie EMA |
| Momentum (M5, M1) | RSI, histogram MACD |
| Price action / SMC (M1) | BOS / CHoCH, liquidity sweep, odrzucenie strefy popytu/podaży |

- **Własne wejście „SCORE”**: score ≥ `ENTRY_SCORE` z przewagą `ENTRY_LEAD` nad stroną przeciwną,
  kierunek zgodny z trendem H1/M15 (wyjątek: świeży CHoCH na M1).
- **Bramka dla pozostałych strategii** (SMC, RT, ORB, Zakres, Turniej): wejście tylko wtedy, gdy
  score po tej stronie ≥ `CONFIRM_SCORE` i trend wyższego interwału się zgadza.
- **Filtry**: brak wejść przy spreadzie > `MAX_SPREAD_ATR_M5` × ATR(M5) oraz gdy ATR M1 jest poza
  zakresem `ATR_RATIO_MIN`…`ATR_RATIO_MAX` względem swojej normy.

Wszystkie progi i wagi są w jednym pliku: **`bot/BotConfig.kt`**.

### Tryb US Open

`bot/UsOpenSession.kt` liczy otwarcie giełdy w USA przez `ZoneId.of("America/New_York")` (9:30 NY),
więc w Polsce jest to 15:30, a w tygodniach różnej zmiany czasu – 14:30.

- Okno: od 5 min przed do 60 min po otwarciu (`WINDOW_BEFORE_MIN`, `WINDOW_AFTER_MIN`).
- Pierwsze 2 min po otwarciu: żadna strategia nie otwiera pozycji (`NO_ENTRY_MIN`).
- Opening Range Breakout: zakres z pierwszych 5 min (`RANGE_MIN`, można ustawić do 15), wejście po
  zamknięciu świecy M1 poza zakresem, w kierunku zgodnym ze score. SL za przeciwną krawędzią
  zakresu; gdy strata na SL przekroczyłaby 20 PLN, setup jest pomijany. Jedno wejście na sesję.
- Dni bez sesji: weekendy i święta NYSE z listy `HOLIDAYS` (lata 2026–2028; listę trzeba co jakiś
  czas uzupełnić).

### Parametry wejść i wyjść

- **Wolumen:** stały `0.007` lota na każdą transakcję, z wstępnym sprawdzeniem depozytu.
- **Stop loss:** liczony w złotówkach – 15–20 PLN straty, a dla SMC 8–20 PLN (SL strukturalny).
- **Take profit:** 2,5 × odległość SL (minimalne R:R 1:2,5).
- **Zamykanie:** TP albo SL w XTB, a wcześniej tylko przy **potwierdzonym odwróceniu tendencji**
  (`bot/ReversalDetector.kt`): co najmniej 2 z 3 warunków naraz – (a) BOS/CHoCH przeciw pozycji na
  M1, (b) zamknięcie po drugiej stronie EMA 20/50 z rosnącym przeciwnym momentum, (c) score strony
  przeciwnej ponad progiem – utrzymane przez 2 kolejne zamknięte świece, przy ruchu ≥ 0,5×ATR(M5)
  od najlepszej ceny. Pojedyncza świeca cofnięcia, szum i odbicie w trakcie potwierdzania nie
  zamykają pozycji. Powód zamknięcia jest w HUD i w dzienniku.
- **Prowadzenie SL/TP** (`bot/TrailingManager.kt`): od +1R SL na wejście + spread; od +1,5R SL za
  ostatni swing (albo k×ATR za najlepszą ceną); przy silnym impulsie (świeca ≥ 1,5×ATR, pełny
  korpus, wybicie) TP przesuwa się do kolejnej strefy płynności albo o 1,5×ATR(M5). SL nigdy nie
  wraca w stronę straty. Zmiany są wpisywane do otwartej pozycji w XTB najwyżej co 45 s i o krok
  ≥ 2 PLN; po zmianie bot odczytuje wiersz pozycji i sprawdza wartości. Przy błędzie ponawia
  (15 s, 30 s, 60 s…), a po 2 błędach z rzędu pokazuje alert w HUD.
- **Dzienny kill-switch:** ostrzeżenie w HUD przy stracie 10% kapitału z początku dnia, twarde
  zatrzymanie nowych wejść przy 25% (`DAILY_WARN_PCT`, `DAILY_KILL_PCT`).
- **Sesje:** handel przez całą dobę (Azja, Londyn, Nowy Jork), bez nowych wejść 30 min przed
  zamknięciem sesji instrumentu.
- **Spread:** filtr względem odległości SL oraz względem typowego spreadu instrumentu (`bot/SpreadGuard.kt`).

### Tryb papierowy i dziennik decyzji

- **Tryb papierowy (demo)** to `DRY_RUN`: bot liczy wszystko tak samo, ale niczego nie klika w XTB –
  wejścia, przesunięcia SL/TP i wyjścia są tylko księgowane i logowane.
- **Dziennik decyzji** (`bot/DecisionLog.kt`): plik CSV na każdy dzień,
  `Android/data/com.example.algohud/files/decisions/decisions-RRRR-MM-DD.csv`, kolumny
  `czas;tryb;instrument;typ;cena;score_long;score_short;trend_htf;powod`. Typy wpisów: `SCORE`
  (co minutę), `WEJŚCIE`, `ODMOWA`, `WYJŚCIE`, `ODWRÓCENIE`, `TRAILING`, `US_OPEN`, `RYZYKO`.
  Pliki starsze niż 14 dni są usuwane.

### Szybkość i częstość wejść

- **Szybkie wejście:** SL, TP i wolumen są wpisywane do biletu XTB bez przewijania ekranu, każda
  wartość jest sprawdzana odczytem zwrotnym, a potem bot klika Buy/Sell. Gdy cokolwiek się nie
  zgadza, bot wraca do pełnej ścieżki z przewijaniem (`FAST_ENTRY` w `AutoExecutionService.kt`).
  HUD pokazuje czas „od decyzji do kliknięcia” i użytą ścieżkę.
- **Odczyt ceny z ekranu** co 0,5 s (`SCREEN_POLL_MS`).
- **Luźniejsze progi:** RT – świeca ≥ 1,0×ATR, korpus ≥ 65%, knot ≤ 20%, wybicie 8 świec;
  Zakres – odbicie ≥ 0,25×ATR; SMC – do 3 wejść na sesję.
- **Mniej filtrów:** `TRADER_STRICT = false` wyłącza filtry „pod prąd trendu 5m”, „nie gonię”
  i „opór/wsparcie za blisko”. Zostają: przerwa wokół danych USA, koszt spreadu i martwy rynek.

Więcej wejść to więcej zapłaconych spreadów; w symulacji liczba transakcji rośnie o ok. 50%,
a wynik na transakcję pozostaje ujemny (`tools/replay/freq.py`).

### Okna publikacji danych

Aplikacja nie ma podłączonego kalendarza makro. Zamiast niego działają:

- stałe okna danych z USA liczone w czasie Nowego Jorku: 8:25–8:40, 9:55–10:10, 13:55–14:35
  (w Polsce zwykle 14:25–14:40, 15:55–16:10, 19:55–20:35) – w `SmcEngine.kt`,
- **ręczna** lista publikacji w `bot/MacroWindows.kt` – trzeba ją samodzielnie uzupełniać.

## Bramka jakości danych

`bot/DataQuality.kt` blokuje nowe wejścia, gdy:

- brakuje ceny z feedu albo jest starsza niż 20 s,
- nie jest znana wartość kontraktu (bilet instrumentu nie był otwarty w XTB),
- po przerwie w danych pozycje nie zostały jeszcze uzgodnione z XTB,
- w trybie realnym: bilet XTB jest nieodczytany lub starszy niż 8 s, widać inny instrument,
  brakuje ceny Buy/Sell albo różnica XTB–feed nagle odskoczyła od typowej.

Pełny raport (źródło, czas odczytu, jednostka i status każdego pola) można skopiować z aplikacji
przyciskiem „Skopiuj raport danych”.

## Bezpieczeństwo

- **KILL:** przytrzymanie przycisku w HUD albo akcja w powiadomieniu blokuje nowe wejścia
  i próbuje zamknąć otwartą pozycję przez ekran pozycji w XTB. Kill-switch włącza się też sam,
  gdy po kliknięciu nie da się zweryfikować stanu zlecenia w XTB.
- **Weryfikacja instrumentu:** przed kliknięciem bot sprawdza, czy na ekranie XTB jest instrument ze zlecenia.
- **Tarcza dotyku:** w trybie AUTO ekran może ignorować dotknięcia; 3× „głośność w dół”
  odblokowuje ją na 5 minut.
- **Zakres usługi ułatwień dostępu:** aplikacja brokera jest rozpoznawana po nazwie (XTB),
  a zdarzenia z innych aplikacji są odrzucane bez czytania treści. Wyjątkiem są okna AnyDesk
  i systemowe okno nagrywania ekranu, opisane niżej.

## Zdalny dostęp (AnyDesk)

Funkcje opcjonalne, domyślnie wyłączone:

- automatyczne zatwierdzanie systemowego okna „Rozpocząć nagrywanie lub przesyłanie?” – tylko dla AnyDesk,
- automatyczna akceptacja prośby o połączenie – **wyłącznie** od zaufanych ID lub aliasów AnyDesk
  wpisanych w aplikacji; nieznany ID nie jest akceptowany.

## Budowanie

Wymagania: JDK 17, Android SDK 35.

```bash
./gradlew testDebugUnitTest   # testy jednostkowe
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

- **Android Studio:** File → Open → wskaż ten folder → Run.
- **GitHub Actions:** workflow `Build APK` (`.github/workflows/android.yml`) uruchamia testy
  i buduje APK przy każdym pushu do `main`, w każdym pull requeście i ręcznie. Wyniki są
  w zakładce *Actions → ostatni build → Artifacts*: `algohud-debug-apk` oraz `unit-test-report`.

## Pierwsze uruchomienie

1. Zezwól na wyświetlanie nad innymi aplikacjami.
2. Zezwól na powiadomienia (Android 13+).
3. Włącz usługę „Algo HUD – wykonawca zleceń” w Ustawieniach → Ułatwienia dostępu.
   Przy instalacji spoza Google Play najpierw: *Informacje o aplikacji → ⋮ → Zezwól na ustawienia ograniczone*.
4. Uruchom HUD i dotknij bąbla, aby rozwinąć panel.
5. Otwórz w XTB bilet GOLD – bez tego bot nie zna wartości kontraktu i nie otworzy pozycji.
6. Obserwuj działanie w DRY_RUN; dopiero potem przełącz na AUTO.

Po restarcie telefonu lub aktualizacji aplikacji bot uruchamia się sam, jeśli nie został
zatrzymany przyciskiem „Zatrzymaj”.

## Struktura

```
app/src/main/java/com/example/algohud/
├── MainActivity.kt                    – uprawnienia, start/stop, tryb, ustawienia AnyDesk, diagnostyka
├── BootReceiver.kt                    – autostart po restarcie telefonu
├── core/Models.kt                     – modele domenowe (Tick, Bar, OrderRequest, Position, HudState)
├── data/
│   ├── PythFeed.kt                    – ceny i historia świec z Pyth Network
│   └── MarketNewsEngine.kt            – strumienie Binance, RSS, sentyment słów kluczowych
├── bot/
│   ├── BotOrchestrator.kt             – spina dane, strategie, ryzyko i wykonanie; parametry handlu
│   ├── BotConfig.kt                   – WSZYSTKIE progi i wagi do strojenia (ryzyko, score, odwrócenie, trailing, US Open)
│   ├── SignalEngine.kt                – score LONG/SHORT z trzech warstw (trend H1/M15, momentum, price action)
│   ├── ReversalDetector.kt            – potwierdzone odwrócenie tendencji (2 z 3 warunków, 2 świece)
│   ├── TrailingManager.kt             – plan przesunięć SL/TP + ProtectionSync (limit częstości, ponowienia, historia)
│   ├── UsOpenSession.kt               – okno US Open w czasie Nowego Jorku, święta NYSE, Opening Range Breakout
│   ├── DecisionLog.kt                 – dziennik decyzji do pliku CSV
│   ├── SmcEngine.kt                   – silnik Smart Money Concepts dla XAU/USD
│   ├── Learning.kt                    – strategie turniejowe, ShadowTrader, Learner
│   ├── NextCandlePredictor.kt         – predykcja następnej świecy (informacyjnie)
│   ├── DataQuality.kt                 – raport pochodzenia danych i bramka wejść
│   ├── StrategyProof.kt               – pomiar strategii „na sucho” i bramka prawdziwych zleceń
│   ├── Bars.kt, CandleEvaluator.kt    – agregacja świec, wskaźniki, ocena impulsu
│   ├── SpreadGuard.kt, MarketHours.kt, MacroWindows.kt – filtry spreadu, sesji i publikacji
│   └── CandleRecorder.kt, BarStore.kt – zapis świec do CSV w pamięci aplikacji
├── risk/
│   ├── RiskManager.kt                 – kontrola każdego zlecenia, kill-switch
│   ├── PositionSizer.kt               – wyliczanie wolumenu z ryzyka i dźwigni
│   └── TradeJournal.kt                – dziennik zamkniętych transakcji (ostatnie 300)
├── execution/
│   ├── AutoExecutionService.kt        – ExecutionEngine i obsługa ekranu XTB (AccessibilityService)
│   ├── ScreenScanner.kt               – odczyt instrumentu i cen z ekranu
│   └── SlTpForm.kt                    – logika pól SL/TP w bilecie i wierszy pozycji
└── overlay/OverlayService.kt          – usługa pierwszoplanowa i pływający HUD
```

Testy jednostkowe (`app/src/test/`) obejmują silnik SMC, predyktor świec, bramkę jakości danych, ocenę strategii na sucho,
formularz SL/TP, parser wierszy pozycji oraz nowe moduły: `SignalEngine`, `ReversalDetector` (także przypadki, w których bot
nie zamyka), `TrailingManager`, `UsOpenSession` (w tym tygodnie zmiany czasu) i `DecisionLog`. Moduły te nie zależą od Androida.
`BotOrchestrator` i obsługa ekranu XTB nie mają testów.

## Znane ograniczenia

- **Dzienny kill-switch** zatrzymuje nowe wejścia po stracie 25% kapitału z początku dnia
  (ostrzeżenie przy 10%). Wartości w `BotConfig.Risk`.
- **Edycja SL/TP w otwartej pozycji, odczyt zwrotny i zamykanie przy odwróceniu nie były testowane
  na telefonie.** Najpierw sprawdź je w `DRY_RUN`, potem na koncie demo.
- **Wolumen 0.007** – nie zweryfikowano, czy XTB przyjmuje taki krok wolumenu dla złota.
- **Bramka score zmniejsza liczbę wejść.** Przy neutralnym trendzie H1/M15 bot nie wchodzi
  (`ALLOW_NEUTRAL_HTF`), chyba że na M1 jest świeży CHoCH.
- **Brak bramki przed AUTO.** `autoGate` w `OverlayService` zawsze przepuszcza – tryb AUTO można
  włączyć bez dodatniego wyniku na papierze.
- **Ocena na bieżąco nie daje przewagi.** Tryb `LIVE` tylko wstrzymuje strategię po serii słabych
  wyników; w symulacji strategie nie miały przewagi po spreadzie, więc oczekiwany wynik
  pozostaje ujemny. Tryb `STRICT` blokuje zlecenia do czasu udowodnienia przewagi.
- **Szybkie wejście nie było testowane na telefonie.** Zostało zbudowane i przechodzi testy
  jednostkowe, ale klikanie w XTB wymaga sprawdzenia na koncie demo.
- **Automatyzacja zależy od wyglądu XTB.** Identyfikatory widoków pochodzą ze zrzutów ekranu;
  aktualizacja aplikacji brokera może zepsuć odczyt lub klikanie.
- **Godziny sesji** w `bot/MarketHours.kt` są wpisane ręcznie i wymagają weryfikacji w XTB.
- **Kalendarz makro** nie jest podłączony (`EconomicCalendarSource.None`).
- **Kanały RSS** w `MarketNewsEngine` są przykładowe – sprawdź regulaminy źródeł.
- **Identyfikator aplikacji** to nadal `com.example.algohud`, a build nie jest podpisany kluczem wydania.
