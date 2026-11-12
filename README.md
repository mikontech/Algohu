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
2. **Strategie.** Wszystkie strategie działają jednocześnie; pierwsza z sygnałem otwiera pozycję,
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
| SMC XAU | Sweep płynności (Asia High/Low, PDH/PDL, swingi z 60 min) → CHoCH na M1, SL za ekstremum sweepu, TP na przeciwnej płynności, kontekst H1. Tylko dla GOLD/XAU. Maks. 2 wejścia na sesję. | `bot/SmcEngine.kt` |
| RT / ORB | Impuls świecy 1m w czasie rzeczywistym i wybicie z zakresu otwarcia Londynu 09:00 (pierwsze 5 min, aktywne 15 min). | `bot/BotOrchestrator.kt` |
| US OPEN | Otwarcie sesji w USA (9:30 czasu Nowego Jorku – zwykle 15:30 PL, w tygodniach przesunięcia czasu 14:30). Zakres z pierwszych 15 min, bez wejść 5 min przed i 2 min po otwarciu, wejście po zamknięciu świecy 1m poza zakresem zgodnie z kompasem, SL w środku zakresu (setup pomijany, gdy to > 20 PLN). Jedno wejście na otwarcie, święta NYSE w kodzie. | `bot/UsOpenOrb.kt`, `bot/UsSession.kt` |
| Zakres | Kupno po odbiciu od dolnych 20% zakresu z 30 świec 1m, sprzedaż przy górze; wyłączona w silnym trendzie. | `bot/BotOrchestrator.kt` |
| Turniej | Warianty strategii (5m i 1m) handlują „na sucho”; handluje tylko zwycięzca z dodatnim, statystycznie istotnym wynikiem. | `bot/Learning.kt` |

**🧭 Kompas kierunku** (`bot/TrendScore.kt`) – wspólna ocena −100…+100 z trendu 5m (EMA20/50), nachylenia
EMA20 1m, MACD, RSI i struktury (BOS) 1m. Blokuje wejścia pod prąd silnego wyniku (wszystkie strategie poza SMC,
które z założenia gra odwrócenia po sweepie), jest warunkiem wyjścia przy odwróceniu i linią w HUD.

Model predykcji następnej świecy (`bot/NextCandlePredictor.kt`, regresja logistyczna ucząca się
online) jest obecnie **tylko informacją w HUD** – nie otwiera pozycji.

### Parametry wejść i wyjść

Wartości są stałymi w `BotOrchestrator.kt` (blok `companion object`):

- **Wolumen:** stały `0.006` lota na każdą transakcję, z wstępnym sprawdzeniem depozytu.
- **Stop loss:** liczony w złotówkach – 15–20 PLN straty, a dla SMC 8–20 PLN (SL strukturalny).
- **Take profit:** 2,5 × odległość SL (minimalne R:R 1:2,5).
- **Zamykanie:** TP albo SL w XTB, a do tego bot sam zamyka pozycję przy **potwierdzonym odwróceniu
  trendu** (`bot/ReversalGuard.kt`): ≥ 2 z 3 sygnałów (struktura przeciw, momentum przeciw, kompas przeciw)
  na 2 kolejnych zamkniętych świecach 1m, min. 2 świece od wejścia i cofnięcie ≥ 0,5×ATR od najlepszej ceny.
  Pojedyncza świeca przeciw nie zamyka pozycji. Wyłącznik: `TREND_EXIT` w `BotOrchestrator`.
- **Prowadzenie SL:** po +1R (min. 8 PLN zysku) SL trafia na cenę wejścia, od +1,5R idzie 1R za
  najlepszą ceną. Zmiana jest wpisywana do otwartej pozycji w XTB (krok ≥ 2 PLN, najwyżej co 20 s).
- **Duży ruch:** silny impuls w stronę pozycji przy zysku ≥ 1R → TP odsuwany na najlepszą cenę
  + max(1,5×ATR, 1R) (maks. raz na świecę), SL 0,6×ATR za najlepszą ceną. Nowy TP trafia do otwartej
  pozycji w XTB od razu, SL – w rytmie co 20 s.
- **Sesje:** handel przez całą dobę (Azja, Londyn, Nowy Jork), bez nowych wejść 30 min przed
  zamknięciem sesji instrumentu.
- **Spread:** filtr względem odległości SL oraz względem typowego spreadu instrumentu (`bot/SpreadGuard.kt`).

### Okna publikacji danych

Aplikacja nie ma podłączonego kalendarza makro. Zamiast niego działają:

- stałe okna danych z USA liczone w czasie Nowego Jorku: 8:25–8:40, 9:55–10:10, 13:55–14:35 ET
  (zwykle 14:25, 15:55, 19:55 PL) – w `bot/UsSession.kt`,
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
│   ├── SmcEngine.kt                   – silnik Smart Money Concepts dla XAU/USD
│   ├── Learning.kt                    – strategie turniejowe, ShadowTrader, Learner
│   ├── NextCandlePredictor.kt         – predykcja następnej świecy (informacyjnie)
│   ├── DataQuality.kt                 – raport pochodzenia danych i bramka wejść
│   ├── Bars.kt, CandleEvaluator.kt    – agregacja świec, wskaźniki, ocena impulsu
│   ├── SpreadGuard.kt, MarketHours.kt, MacroWindows.kt – filtry spreadu, sesji i publikacji
│   ├── TrendScore.kt, ReversalGuard.kt – kompas kierunku i wyjście przy odwróceniu trendu
│   ├── UsSession.kt, UsOpenOrb.kt     – zegar sesji USA (czas NY, święta) i wybicie otwarcia USA
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

Testy jednostkowe (`app/src/test/`) obejmują kompas kierunku, wyjście przy odwróceniu (także przypadki,
w których bot NIE może zamknąć), zegar sesji USA (tygodnie przesunięcia czasu, święta), wybicie otwarcia USA, silnik SMC, predyktor świec, bramkę jakości danych,
formularz SL/TP i parser wierszy pozycji. `BotOrchestrator` i obsługa ekranu XTB nie mają testów.

## Znane ograniczenia

- **Dzienny limit straty jest wyłączony.** `RiskManager.Config.maxDailyLossPct` ma wartość
  nieskończoną, więc kill-switch nie zadziała sam po serii strat.
- **Brak bramki przed AUTO.** `autoGate` w `OverlayService` zawsze przepuszcza – tryb AUTO można
  włączyć bez dodatniego wyniku na papierze.
- **Automatyzacja zależy od wyglądu XTB.** Identyfikatory widoków pochodzą ze zrzutów ekranu;
  aktualizacja aplikacji brokera może zepsuć odczyt lub klikanie.
- **Godziny sesji** w `bot/MarketHours.kt` są wpisane ręcznie i wymagają weryfikacji w XTB.
- **Święta NYSE** w `bot/UsSession.kt` są wpisane na 2026–2027 – uzupełniaj co rok.
- **Przesunięcie SL/TP w XTB** nie jest weryfikowane odczytem pozycji po zapisie – bot zakłada sukces,
  gdy dotknął „Modyfikuj pozycję”.
- **Kalendarz makro** nie jest podłączony (`EconomicCalendarSource.None`).
- **Kanały RSS** w `MarketNewsEngine` są przykładowe – sprawdź regulaminy źródeł.
- **Identyfikator aplikacji** to nadal `com.example.algohud`, a build nie jest podpisany kluczem wydania.
