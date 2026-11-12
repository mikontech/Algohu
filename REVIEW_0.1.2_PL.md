# Algo HUD 0.1.2 — AGRESYWNY+ i ochrona zysku

Wersja oparta na 0.1.1. Agresywniejsze wejścia oznaczają więcej okazji, bez zwiększania lota 0.006 i limitu strat. Nie potwierdzono, że zmiana poprawia rentowność.

## Wejścia

- RT: minimalny zasięg 1.2 → 1.0 ATR, korpus 70% → 65%, maksymalny knot 15% → 20%, wybicie 10 → 8 świec.
- Zakres: odbicie 0.30 → 0.25 ATR.
- SMC: maksymalnie 3 zamiast 2 setupów na sesję. Pozostałe filtry, SL/TP, blackout i limity ryzyka pozostają.

## Wyjście przy odwróceniu

Dla BUY spadek, dla SELL wzrost. Bot wymaga co najmniej 3 kolejnych odczytów przeciwko pozycji przez minimum 4 sekundy. Najlepszy obserwowany zysk cenowy musi osiągnąć max(0.40 ATR, 3 spready), cofnięcie max(0.25 ATR, 2 spready), a bieżący szacowany zysk po spreadzie musi nadal wynosić co najmniej max(0.15 ATR, 2 spready).

Reguła działa przed bazowym prowadzeniem SL/TP. Dla rzeczywistych pozycji wymaga świeżego, poprawnego odczytu BUY/SELL właściwego instrumentu w XTB. Nie zamyka straty; dotychczasowe SL/TP nadal obowiązują. Po udanym zamknięciu blokuje ponowne wejście przez 30 sekund. Nieudana próba jest ponownie oceniana najwcześniej po 20 sekundach. Zlecenia reduce-only są ponownie sprawdzane po uzyskaniu blokady wykonania, aby drugie zamknięcie nie stało się nową pozycją.

To szacowanie wyniku na podstawie księgi bota i feedu po spreadzie, a nie odczyt ostatecznego PnL brokera. Nie obejmuje nieznanych prowizji, swapu i poślizgu. Nie gwarantuje zysku. Brak odczytu XTB blokuje nowe wyjście ochrony zysku, ale nie wyłącza istniejącego SL/TP.

## Testy i instalacja

Dodano 13 testów reguły ochrony zysku: BUY/SELL, ruch sprzyjający, szum, spread, strata, częste/powtórzone ticki, luki, niepoprawne ceny i zmiana pozycji. GitHub Actions uruchamia pełny zestaw testów i buduje APK debug. Nie wykonano transakcji ani testu na telefonie. Najpierw zweryfikuj zachowanie na koncie demo.

APK debug może mieć inny podpis niż poprzednia kompilacja. Nie odinstalowuj starej wersji bez zabezpieczenia jej danych. Zapisany AUTO nadal jest przywracany po uruchomieniu HUD.
