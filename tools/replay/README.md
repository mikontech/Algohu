# Odtworzenie reguł wejść na danych historycznych

Przybliżona symulacja reguł RT, ORB, Zakres i SMC z `BotOrchestrator.kt` / `SmcEngine.kt`
na świecach 1m złota. Służy do sprawdzenia, czy wejścia mają przewagę po spreadzie.

```bash
cd tools/replay
python3 fetch_data.py                 # ok. 29 dni świec 1m GC=F
python3 replay.py data/gc_1m.csv      # strategie jak w aplikacji, osobno i razem
python3 variants.py data/gc_1m.csv    # losowe wejścia (kontrola) i warianty parametrów
```

Założenia: spread 0,70 USD, 0,006 lota (ok. 2,39 PLN za 1 USD ruchu), SL 15–20 PLN
(SMC 8–20 PLN), TP 2,5R, stop na wejście po +1R i dalej 1R za ceną.

Uproszczenia, o których trzeba pamiętać przy czytaniu wyników:

- tiki wewnątrz świecy są syntetyczne (O→L→H→C dla świecy wzrostowej, O→H→L→C dla spadkowej),
- dane to kontrakt futures, a nie kwotowania XTB,
- brak turnieju (`Learner`), bramki jakości danych i ciasnego trailingu po +2R,
- SL/TP wykonują się dokładnie po poziomie, bez poślizgu.

Wynik z 14.10–12.11.2026: wszystkie cztery strategie na minusie, łącznie ok. −1,8 PLN na
transakcję – tyle samo co losowe wejścia z tym samym SL/TP (koszt spreadu ok. 1,7 PLN).
