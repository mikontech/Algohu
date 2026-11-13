# Algo HUD – paczka kontekstowa (handoff) • stan po Kroku 2 Rady AI • 2026-11-13

Dokument dla innego modelu AI / programisty. Zawiera wszystko, co trzeba wiedzieć, żeby kontynuować pracę bez
czytania historii czatów. Kod jest źródłem prawdy – tu jest mapa.

---

## 1. Czym jest projekt

**Algo HUD** – aplikacja Android (Kotlin, bez Compose – zwykłe View + WindowManager) z pływającą nakładką nad
aplikacją brokera **XTB** (pakiet `com.xtb.xmobile2`, stała `ScreenScanner.XTB_PACKAGE`). Bot handluje
**GOLD (XAU/USD, CFD)**: liczy ceny z Pyth/Binance, czyta bilet XTB przez **AccessibilityService** i sam składa
zlecenia klikając w interfejs XTB (brak API XTB).

- Repo: `github.com/mikontech/Algohu` (publiczne). Pusta kopia `mikontech/Algohud` – nieużywana.
- Gałąź robocza: **`trend-usopen`**, PR **#6** (niezmergowany do `main`).
- APK: GitHub Actions → PR #6 → Checks → Artifacts → **`algohud-debug-apk`**. Podpis stałym kluczem
  (`app/debug.keystore` w repo) → instaluje się jako aktualizacja (bez utraty danych/uprawnień).
- Telefon bota: osobny telefon z uszkodzonym ekranem dotykowym, obsługa przez **AnyDesk** z telefonu głównego.
  Stąd: tarcza dotyku (overlay pochłaniający „duchy” dotyku), auto-akceptacja AnyDesk, alarmy przez **ntfy**.
- Użytkownik pracuje z telefonu; pliki na GitHub wgrywa przez przeglądarkę (Claude in Chrome z komputera).
- Komunikacja z użytkownikiem i teksty UI: **po polsku**.

## 2. Reguły handlu ustalone przez użytkownika (nie zmieniać bez pytania)

| Reguła | Wartość / zachowanie | Gdzie w kodzie |
|---|---|---|
| Instrument | GOLD w XTB | `BotOrchestrator.smcEligible`, `AutoExecutionService.SUBTITLES` |
| Strategie | wszystkie naraz, bez menu: SMC (główna), RT impuls, ORB, ZAKRES, TURNIEJ (Learner), US OPEN; pierwsza z sygnałem wchodzi | `BotOrchestrator.processPrice` |
| Jedna pozycja naraz | tak | `RiskManager.Config.maxOpenPositions = 1` |
| R:R | min. 1:2,5 (TP = 2,5 × SL) | `TP_R`, `SmcEngine.MIN_RR`, `RiskGate.MIN_RR` |
| SL | odległość jak dla straty 15–20 zł przy 0.006 lota (SMC: 8–20 zł strukturalnie) | `SL_MIN_PLN`, `SL_MAX_PLN`, `buildOrder` |
| Wolumen | od 0.006 w górę krokiem 0.001, wg wolnych środków, bez górnego limitu (świadoma decyzja – ryzyko ~3–4%/transakcję) | `bot/Sizing.kt` |
| Schodki SL | co 8 zł zysku: +8 → BE+spread, +16 → +8 zablokowane… | `SL_STEP_PLN`, `Sizing` |
| Kill-switch dzienny | 15% konta | `RiskManager.Config.maxDailyLossPct = 0.15` |
| Dane makro USA | 2 min bez wejść wokół publikacji | `MacroWindows`, `smc.newsBlackout` |
| Sesje | handel całą dobę (`TRADE_ALL_SESSIONS = true`), także otwarcie USA | `UsSession`, `UsOpenOrb` |
| Wyjścia | SL/TP w XTB + zamknięcie na potwierdzonym odwróceniu trendu, przesuwanie TP/SL przy dużym ruchu | `ReversalGuard`, `BROKER_EXITS_ONLY`, `TREND_EXIT` |
| Powiadomienia | ntfy: wejścia/wyjścia, „za chwilę mogę wejść”, „dlaczego nie wchodzę”, cichy raport co 30 min, alarmy awarii | `bot/Ntfy.kt` (limit ntfy.sh ~250/dzień/IP – był raz HTTP 429) |
| HUD | minimalny: kiedy wszedł, na jakiej świecy, cena, LONG/SHORT; szczegóły za przyciskiem | `OverlayService`, `HudState.compact` |
| Tryb startowy | zawsze `DRY_RUN`; AUTO włącza użytkownik w aplikacji | `ExecutionEngine(initialMode = DRY_RUN)` |

## 3. Struktura plików (app/src/main/java/com/example/algohud)

```
MainActivity.kt              onboarding uprawnień, tryby, tarcza, AnyDesk, ⚙ Rada AI (klucz, tryb, nawigator, samonaprawa)
BootReceiver.kt              autostart po restarcie
core/Models.kt               Side, ExecutionMode(DRY_RUN/CONFIRM/AUTO), Bar, Tick, OrderRequest, ExecutionResult, Position, ScreenQuote, HudState
overlay/OverlayService.kt    Foreground service: składa cały stos (DI ręczne), HUD, tarcza dotyku, KILL, przyciski 🧠 ⏸
bot/BotOrchestrator.kt       (≈2260 l.) serce: ceny→świece→strategie→buildOrder→submitEntry (rada)→engine; pozycje, raporty, HUD, awareness()
bot/SmcEngine.kt             SMC XAU: sweep płynności, struktura, R:R, sesje, blackout newsów
bot/Learning.kt              TURNIEJ strategii (Learner), backtest kroczący, champion na interwał
bot/NextCandlePredictor.kt   model online świecy 1m (obecnie tylko informacja: AI_PREDICTOR_ENTRIES=false)
bot/UsOpenOrb.kt, UsSession  ORB otwarcia USA
bot/TrendScore.kt            „kompas” trendu (blokada wejść pod prąd)
bot/EntryFilters.kt, ReversalGuard.kt, SpreadGuard.kt, DataQuality.kt, MarketHours.kt, MacroWindows.kt
bot/Sizing.kt                wolumen wg środków, schodki SL
bot/Ntfy.kt                  wysyłka ntfy z kluczami/odstępami (anty-spam)
bot/Bars.kt, BarStore.kt, CandleRecorder.kt, CandleEvaluator.kt
data/PythFeed.kt             Pyth XAU/USD (+ historia benchmarks)
data/MarketNewsEngine.kt     RSS + sentyment słownikowy, Binance WS (zapas), blackout makro
risk/RiskManager.kt          księga pozycji, ryzyko, kill-switch, PnL dnia, trailing
risk/PositionSizer.kt, TradeJournal.kt
execution/AutoExecutionService.kt (≈2120 l.) AccessibilityService: odczyt XTB, wykonanie zleceń, SL/TP, zamykanie,
                             samonaprawa widoku, szukanie biletu GOLD, ekran błędu XTB, AnyDesk, alarmy, prymitywy nawigatora AI
execution/ScreenScanner.kt   parsowanie drzewa widoków XTB (id: ElementTitleHorizontalText, CloseButton…)
execution/SlTpForm.kt        koordynator formularza SL/TP, PositionRowInfo
execution/XtbErrorRecovery.kt „Coś poszło nie tak” → Odśwież → restart XTB → alarm
council/                     ◀ NOWE (Rada AI)
  CouncilTypes.kt            CouncilMode, CouncilContext (+awareness, untrackedXtbRows), raporty agentów, CouncilDecision
  CouncilPrompts.kt          4 prompty systemowe (PL) z regułami użytkownika i schematami JSON
  ClaudeClient.kt            OkHttp → api.anthropic.com/v1/messages (anulowalne), extractJson
  CouncilParsing.kt          tolerancyjne parsowanie JSON (brak/zły werdykt → VETO)
  RiskGate.kt                deterministyczna bramka po debacie (czysty Kotlin, testy)
  AgentCouncil.kt            orkiestracja debaty, tryby, transcript/status (StateFlow), fail-closed, ntfy
  ScreenGrabber.kt           AccessibilityService.takeScreenshot → JPEG base64 (Android 11+)
  AiNavigator.kt             wizyjny nawigator-zapas do biletu GOLD
res/xml/accessibility_config.xml  canRetrieveWindowContent, canPerformGestures, canTakeScreenshot, filtr klawiszy (3× głośność)
docs/RADA_AI.md              diagram Mermaid rady
docs/HANDOFF.md              ten plik
```
Testy jednostkowe: `app/src/test/...` (SmcEngine, Sizing, UsOpen, ReversalGuard, DataQuality, SlTp*, XtbErrorRecovery, **RiskGateTest** …).
CI: `.github/workflows/android.yml` – `testDebugUnitTest` + `assembleDebug`, artefakty `algohud-debug-apk`, `unit-test-report`
(wyzwalane push na `main`, każdy `pull_request` i ręcznie).
Zależności: core-ktx, activity-ktx, lifecycle-service/runtime, coroutines-android, okhttp 4.12. Brak kotlinx.serialization (JSON = org.json).

## 4. Architektura i przepływ danych (po Kroku 2)

```
 Pyth/Binance ──ticki──┐                      ┌──────── AutoExecutionService (AccessibilityService) ───────┐
                       ▼                      │ co 1 s: scanNow → ScreenQuote (instrument, Sell/Buy, środki)│
 BotOrchestrator.processPrice (pod Mutex)     │         recoverFromXtbError · findWantedInstrument ·        │
   ├─ risk.onMark → wyjścia/trailing ────────►│         selfHeal · keepXtbFront · AnyDesk                   │
   ├─ świece M1/M5 → strategie → Signal       │ wykonanie: performXtbEntry / setProtection / closePosition  │
   └─ buildOrder (SL 15–20 zł, TP 2,5R,       └──────────────────────────▲──────────────────────────────────┘
      wolumen, traderCheck, bramka danych)                               │ klikanie (uiMutex)
          │ kandydat OrderRequest                                         │
          ▼                                                               │
   submitEntry(inst, order, after) ── zamknięcie/odwrócenie/reduceOnly ──►│ engine.submit (od razu, bez AI)
          │ NOWE wejście                                                  │
          ├─ rada OFF / ADVISORY bez klucza ─────────────────────────────────────►│ engine.submit
          ├─ ADVISORY: engine.submit od razu + debata równolegle (log)   │
          └─ GATE: debata w tle (scope.launch), pętla cen działa dalej    │
                 AgentCouncil.review(ctx)                                 │
                   R0 👁️ Obserwator (Haiku, zrzut ekranu bez HUD / tekst drzewa) → screen_ok?
                   R1 🌍 Strateg ‖ 🛡️ Sceptyk (Sonnet, równolegle)
                   R2 Strateg odpowiada na zarzuty ‖ Sceptyk: APPROVE/ADJUST(×0.5–1 lot)/VETO
                   R3 🎖️ Dowódca (Haiku): przepisuje side/SL/TP, wolumen ≤ kandydat
                   RiskGate (Kotlin) ── OK ──► lock → (kill? już w pozycji?) → engine.submit → after()
                                     └─ WETO ─► skip + countBlock + 3 min przerwy dla kierunku
 ExecutionEngine.submit: RiskManager.evaluate → tryb (DRY_RUN=papier / CONFIRM / AUTO=AccessibilityExecutor) → księgowanie
```

### 4.1 Kontekst agentów („omni-awareness”)
`BotOrchestrator.councilContext()` + `awareness()` dają agentom: kandydata (E/SL/TP/R:R/strata w zł), powód strategii,
cenę teraz, spread, przesunięcie cen XTB względem feedu (`basis`), środki, wynik dnia, sentyment newsów, notatki
(kompas, US OPEN, filtr tradera, notatka SMC), 24 świece M5 i 30 M1, oraz stan aplikacji: tryb, kill-switch, feed,
usługa ułatwień dostępu, `diag` odczytu, aplikacja na wierzchu, ekran błędu XTB, `navStatus` samonaprawy, ostatni
odczyt XTB, pozycje w księdze bota, wiersze pozycji widoczne w XTB, bramka danych, przerwa po stracie, stan ntfy,
ostatni alarm, 6 ostatnich zdarzeń bota. Liczby dla agentów zawsze z kropką (`Locale.US`).

### 4.2 RiskGate – twarde reguły po debacie (`council/RiskGate.kt`)
Weto, gdy: pozycja na instrumencie w XTB spoza księgi bota • debata > 45 s • Obserwator: `screen_ok=false`, inny
instrument (GOLD≈XAUUSD≈XAU), cena ekranu ≠ cena bota+shift o > max(1,0; 0,3×SL) • Sceptyk VETO • Dowódca
`execute=false`, inny kierunek, SL/TP różne o > 0,05, wolumen większy od kandydata • R:R < 2,5 • poślizg ceny w trakcie
debaty > 0,25×SL • wolumen po redukcji < 0.006. Wynik: kandydat z wolumenem ≤ oryginał i dopiskiem „• RADA AI ✓”.
LLM nigdy nie generuje współrzędnych kliknięć zlecenia ani nowych cen.

### 4.3 Tryby rady (prefs `council`, klucz `council_mode`)
- `OFF` – brak.  `ADVISORY` (domyślny) – tylko log/ntfy, bot wchodzi jak dotąd.  `GATE` – weto blokuje,
  błąd/timeout API = brak wejścia (fail-closed) + ntfy HIGH maks. raz na 30 min.
- Modele (prefs): `council_fast_model` (domyślnie `claude-haiku-5-5`, Obserwator/Dowódca/Nawigator),
  `council_deep_model` (domyślnie `claude-sonnet-5-5`, Strateg/Sceptyk). Klucz: `council_api_key` – tylko w telefonie,
  NIGDY w kodzie ani sekretach CI (artefakty publicznego repo są pobieralne).

## 5. Autonomiczna nawigacja i samonaprawa widoku (`AutoExecutionService`)

Pętla co 1 s (`startPolling`): `scanNow` → `recoverFromXtbError` → `findWantedInstrument` → `selfHeal` →
AnyDesk → `keepXtbFront`. Bramka: `autonomyOn() = (selfHealEnabled[=AUTO] || navAutonomy) && teraz > navPausedUntil`.

| Sytuacja | Reakcja |
|---|---|
| XTB w tle / pulpit / inna aplikacja > 20 s | `keepXtbFront`: launch intent XTB (`NEW_TASK|REORDER_TO_FRONT`), co 30 s; alarm po 3 nieudanych próbach. Nie wyciąga z: Algo HUD, Ustawień, instalatora, uprawnień, systemui/android; AnyDesk – dopiero po 2 min; ekran wygaszony – nic |
| XTB widoczne, ale nie bilet GOLD (Portfolio, Wiadomości, historia, lista) | `findWantedInstrument` po 20 s (5 s gdy otwarta pozycja): `navigateTo` = Rynki→CFD→wiersz z przewijaniem → lupa → kafelek „Ostatnio wyszukiwane” → wpisanie nazwy → wynik → Surowce; potem **AiNavigator** (zapas). Odstępy prób: 20 s ×3, potem 2/5/10 min; alarmy i „🔍 Diagnoza” do ntfy |
| Bilet GOLD przewinięty w dół | przewinięcie do góry (co ≥ 10 s) |
| Wiszące okno potwierdzenia/zamknięcia/modyfikacji > 8 s | `selfHeal`: Wstecz |
| „Coś poszło nie tak” | Odśwież → restart XTB → alarm co minutę |
| Użytkownik chce użyć telefonu | ⏸ w HUD (10 min) / w aplikacji (30 min) |

**AiNavigator** (`council/AiNavigator.kt`): pod `uiMutex`, maks. 5 kroków / 45 s, maks. 6 uruchomień/h.
Krok: zrzut (HUD ukryty) → Haiku zwraca `{"action":"tap|back|scroll_down|scroll_up|done|give_up","x","y","why"}` →
`navTapNormalized` (odmowa poza XTB, na ekranie potwierdzenia zlecenia, poza marginesem i na węzłach
Sell/Buy/Kup/Sprzedaj/Potwierdź/Zamknij/Modyfikuj/Usuń/Wpłać/Wypłać/Wyloguj) → `onTarget` (tytuł + Sell/Buy).

**Kill-switch** (HUD ⛔ przytrzymanie, powiadomienie, `risk.killSwitch=true`): `stopAi()` przerywa debatę rady
i nawigację AI (także HTTP w locie) → zwolniony `uiMutex` → `engine.flattenAll`. Deterministyczna nawigacja
działa dalej (potrzebna do zamykania pozycji).

**HUD ukrywany przy zrzucie** (`withHudHidden`) tylko gdy nie trwa żadna akcja w XTB – inaczej zrzut z HUD-em
(przełączanie widoczności w trakcie gestu mogłoby przechwycić dotyk).

## 6. UI

- **HUD** (`OverlayService`): bąbel (przeciąganie, dotyk = rozwiń) → panel: widok uproszczony / szczegóły,
  linia 🧠 status rady + 🧭 `navStatus`, przyciski: ⛔ KILL (przytrzymaj), 🧠 (pokaż/ukryj 14 ostatnich linii debaty),
  ⏸ (pauza samonaprawy 10 min), ℹ️ Szczegóły, ✔ Potwierdź (CONFIRM).
- **Aplikacja** (`MainActivity`) → sekcja „🧠 Rada AI”: klucz API (pole hasła, walidacja `sk-ant-`), tryb
  ADVISORY/GATE/OFF, nawigator AI wł./wył. (`ai_nav`), samonaprawa we wszystkich trybach / tylko AUTO (`ui.nav_autonomy`),
  pauza 30 min / wznów; status na górze ekranu.

## 7. Uprawnienia / manifest
`INTERNET`, `SYSTEM_ALERT_WINDOW`, `FOREGROUND_SERVICE(_SPECIAL_USE)`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`,
`<queries>` launcher. Usługa ułatwień dostępu z `canTakeScreenshot="true"` (po aktualizacji APK może trzeba
wyłączyć/włączyć usługę). Bez MediaProjection (świadomie: Android 14+ wymaga zgody przy każdym starcie).

## 8. Stan gałęzi i otwarte sprawy

- Zdalnie na `trend-usopen`: ostatni commit „Po lupie pierwszy kafelek Ostatnio wyszukiwane”.
- Lokalnie przygotowane (do wgrania): commit „Rada AI: 4 agenty + RiskGate (krok 1…)” + commit Kroku 2
  (podpięcie rady, awareness, samonaprawa we wszystkich trybach, AiNavigator, HUD/aplikacja).
- **Niewypchnięta wcześniejsza poprawka** użytkownika: `algohud-gold-klik-dotyk.patch` (GOLD: klik + dotyk palcem
  w wiersz, czekanie ~5 s na bilet, ślad kroków w ntfy „🔍 Diagnoza”). Dotyka `navigateTo`/`clickRowWithScroll`
  – przy łączeniu z Krokiem 2 możliwy konflikt w okolicy `findWantedInstrument` (wpięcie AiNavigatora jest po
  `navigateTo(target) || …`, więc logicznie się uzupełniają).
- Otwarty problem: bot nie zawsze sam otwiera GOLD po wyjściu z biletu (w nocy 13.11 – 324 nieudane próby, pozycja
  SHORT bez nadzoru ~2 h). GOLD: 1. w Rynki→CFD, 2. w Popularnych, 1. wynik wyszukiwania („Troy Ounce of Gold (CFD)”,
  żółta ikona „Au”). AiNavigator to zapas; warto zebrać „🔍 Diagnoza” po nowej wersji.
- Otwarte pytanie użytkownika: czy bot ma przejmować ręcznie otwarte pozycje GOLD (np. SHORT 0.008 @ 3896.73
  z 13.11 nie był w księdze). Na razie: nie przejmuje; alarm „pozycja poza botem”; Rada AI wetuje nowe wejście,
  gdy taka pozycja jest widoczna na bilecie.
- Historia testów: 12.11 – bot pomijał silne wzrosty w dniu spadkowym (long blokowany trendem 5m), ZAKRES
  zashortował szczyt rajdu (−10 zł), XTB zawiesiło się „Coś poszło nie tak” w trakcie pozycji.

## 9. Weryfikacja Kroku 2 (co zrobiono, czego nie)
- Cały `app/src/main` skompilowany `kotlinc 2.0.21` przeciw `android-35/android.jar` + coroutines + stuby
  OkHttp/AndroidX (typecheck bez Gradle) – **0 błędów**.
- Testy jednostkowe bez `kotlinx-coroutines-test` (14 klas, 89 testów, w tym 13 RiskGate) – **89/89 OK** (własny runner).
- **Nie wykonano**: pełnego Gradle/AAPT/lint ani buildu w GitHub Actions (brak SDK i dostępu do repo z tego środowiska).
  Po wgraniu sprawdzić Checks w PR #6.
- Testy na urządzeniu do zrobienia: zgoda `canTakeScreenshot`, czy XTB blokuje zrzuty (FLAG_SECURE → Obserwator
  przechodzi na tekst drzewa), start XTB z tła na Androidzie 15 (wyjątek SYSTEM_ALERT_WINDOW wymaga widocznej nakładki),
  koszt/opóźnienie debaty na żywym rynku w ADVISORY przed przejściem na GATE.

## 10. Jak kontynuować (wskazówki dla następnego modelu)
1. Zmiany w logice wejść – tylko przez `buildOrder`/`submitEntry`; nie omijać RiskManagera ani RiskGate.
2. Nowe akcje w XTB – zawsze pod `uiMutex` (`withAction`), z weryfikacją ekranu (`onTarget`, `findOrderConfirm`).
3. Każdy nowy alarm ntfy – z `key` i `minGapMs` (limit ntfy.sh).
4. Teksty UI i komunikaty po polsku; liczby dla LLM z `Locale.US`.
5. Nie dodawać kluczy API do repo; publiczne artefakty.
6. Pomysły na Krok 3: statystyka „weto rady vs wynik transakcji” w TradeJournal (ocena ADVISORY), zapis
   transkryptów debat do pliku, cache kontekstu (prompt caching) dla kosztów, przejmowanie ręcznych pozycji (decyzja
   użytkownika), rozpoznawanie ikony „Au” bez LLM.

## Aktualizacja integracji Codex

Patch kroków 1–2 nałożony na 5182ef4 bez konfliktów. GATE bez klucza API blokuje nowe wejścia zarówno w orkiestratorze, jak i przy bezpośrednim wywołaniu rady. Dodano testy regresji dla GATE, ADVISORY i OFF. Lokalny Gradle nie wystartował: pobieranie dystrybucji zakończyło się Network is unreachable. Wymagany pełny build CI i test na urządzeniu; niezawodność nawigacji GOLD nie jest jeszcze potwierdzona.
