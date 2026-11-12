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

## Szukanie lepszych reguł wejścia (300 dni świec 1m)

```bash
python3 fetch_okx.py 300              # świece 1m XAU-USDT-SWAP z OKX (publiczne API) -> data/okx_xau_1m.csv
python3 hyp.py data/okx_xau_1m.csv    # wybicia, trend, powrót do średniej, pora dnia, zasięg otwarcia
python3 hyp2.py                       # sweep PDH/PDL/Azja/4h na zamkniętej świecy 5m, ściśnięcie, impuls
python3 feat.py                       # czy cechy ceny przewidują ruch w kolejnych 30–240 min
```

`lab.py` to wspólny symulator: sygnał na zamkniętej świecy, wejście na otwarciu następnej,
spread 0,70 USD, 0,006 lota, SL 7 USD; gdy SL i TP wypadają w tej samej świecy, liczony jest SL.
Wyniki są dzielone na pierwsze 60% okresu (IS) i ostatnie 40% (OOS).

Wynik z 16.01–12.11.2026 (ok. 294 tys. świec w godzinach handlu GOLD): żadna z ok. 100 sprawdzonych
reguł nie ma dodatniego wyniku jednocześnie w IS i OOS. Typowy wynik to −0,6 do −2,0 PLN na
transakcję, czyli tyle co losowe wejścia (−0,8 do −1,6 PLN) – koszt spreadu. Żadna z 13 cech ceny
nie przewiduje kierunku w obu połowach okresu. Dane pochodzą z kontraktu OKX, nie z kwotowań XTB
(korelacja 5-minutowych zmian z GC=F: 0,99).
