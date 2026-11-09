# Algo HUD

Pływająca nakładka (HUD) dla Androida: notowania na żywo, sentyment newsów,
risk manager z kill-switchem i moduł wykonawczy (REST API lub AccessibilityService).

> **Domyślnie tryb DRY_RUN (paper trading).** Żadne prawdziwe zlecenie nie zostanie wysłane,
> dopóki ręcznie nie przełączysz trybu w HUD na CONFIRM lub AUTO.

## Struktura

```
app/src/main/java/com/example/algohud/
├── MainActivity.kt                 – onboarding uprawnień, start/stop
├── core/Models.kt                  – modele domenowe
├── data/MarketNewsEngine.kt        – WebSocket, RSS, kalendarz, sentyment
├── risk/RiskManager.kt             – sizing, limity, kill-switch
├── execution/AutoExecutionService.kt – ExecutionEngine, Wariant A (API), Wariant B (Accessibility)
├── bot/BotOrchestrator.kt          – strategia (demo) + spięcie modułów
└── overlay/OverlayService.kt       – Foreground Service + pływający HUD
```

## Budowanie

- **Android Studio:** File → Open → wskaż ten folder → Run.
- **Terminal:** `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- **GitHub Actions:** każdy push do `main` buduje APK. Pobierzesz go w zakładce
  *Actions → ostatni build → Artifacts → algohud-debug-apk*.

## Pierwsze uruchomienie

1. Zezwól na wyświetlanie nad innymi aplikacjami.
2. Zezwól na powiadomienia (Android 13+).
3. (Tylko Wariant B) Włącz usługę w Ustawieniach → Ułatwienia dostępu.
   Przy instalacji spoza Google Play najpierw: *Informacje o aplikacji → ⋮ → Zezwól na ustawienia ograniczone*.
4. Uruchom HUD. Dotknij bąbla, aby rozwinąć panel. KUP/SPRZEDAJ wymagają przytrzymania.

## Do zrobienia przed handlem prawdziwymi środkami

- [ ] Prawdziwy profil UI brokera (`UiProfiles`) z `adb shell uiautomator dump` **albo** Wariant A z kluczami w Keystore.
- [ ] Saldo z API zamiast stałej `10_000.0` (`OverlayService.buildTradingStack`).
- [ ] Synchronizacja otwartych pozycji z brokerem przy starcie (`RiskManager`).
- [ ] Źródło kalendarza makro (`EconomicCalendarSource`).
- [ ] Backtest i forward test strategii w DRY_RUN. `SentimentMomentumStrategy` to tylko demo.
- [ ] Sprawdzenie regulaminu brokera pod kątem automatyzacji.
