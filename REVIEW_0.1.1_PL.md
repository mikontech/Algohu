# Algo HUD 0.1.1 — przegląd i poprawki

Źródło: mikontech/Algohu, commit eccb950d43bdd9619b0dc1ea71edcb777b8d8725.

## Wprowadzone zmiany

- Kontrola danych odrzuca NaN, nieskończoność, niedodatnie ceny oraz czas z przyszłości (tolerancja 5 sekund). Kontroluje również niepoprawny ATR i bazę ceny.
- Agregacja świec nie cofa się po spóźnionym ticku; odrzuca błędne ceny. Historia jest deduplikowana, walidowana i nie nadpisuje świec zebranych na żywo.
- Pyth odrzuca stare, cofnięte, przyszłe i błędne notowania. Równoległe aktualizacje statusu instrumentów nie nadpisują się nawzajem.
- Zatrzymanie HUD wyłącza automatyczne przywracanie aplikacji brokera i usuwa oczekujące potwierdzenie.
- AUTO wymaga potwierdzenia w oknie aplikacji. Zablokowana próba zmiany trybu nie zapisuje go do automatycznego przywrócenia.
- Ekran ustawień pokazuje zapisany tryb i wersję, zamiast zawsze deklarować DRY_RUN.

## Istotne ograniczenia i następne zmiany

To wydanie instalacyjne debug, podpisywane kluczem środowiska kompilacji. Podpis może różnić się od wcześniejszego APK; nie odinstalowuj poprzedniej wersji bez zabezpieczenia danych. Do stałych aktualizacji potrzebny jest zachowany klucz podpisu wydania.

Nie zmieniono strategii ani parametrów zleceń. Zachowano przywracanie zapisanego AUTO po restarcie. Zatrzymanie HUD nie oznacza zamknięcia pozycji u brokera. Nie wykonano transakcji ani testów na koncie brokera.

Przed uznaniem za wydanie produkcyjne: testy na urządzeniu Android i koncie demonstracyjnym XTB, walidacja mapowania UI brokera, uzgadnianie rzeczywistego wykonania zamiast traktowania samego wysłania jako wypełnienia, przegląd współbieżności zmiany trybu i realizacji zleceń, trwały podpis release. Moduły BotOrchestrator i AutoExecutionService warto podzielić na mniejsze klasy. Testy jednostkowe nie potwierdzają skuteczności strategii.

## Weryfikacja

GitHub Actions uruchamia testDebugUnitTest i assembleDebug. Dodano testy odrzucania błędnych cen/czasów i regresji agregacji świec. Wynik kompilacji i testów należy odczytać z przebiegu dla konkretnego commita.
