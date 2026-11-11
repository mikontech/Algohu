from pathlib import Path
import subprocess

path = Path('app/src/main/java/com/example/algohud/execution/AutoExecutionService.kt')
text = path.read_text(encoding='utf-8')
marker = '// UI_RECOVERY_V1'
if marker in text:
    raise SystemExit('Recovery patch already present; refusing to apply twice')
sha = subprocess.check_output(['git', 'hash-object', str(path)], text=True).strip()
if sha != 'c7a5cb29d635612f98892cb7a7ea1c64391e024c':
    raise SystemExit(f'Unexpected executor blob {sha}; manual review required')

old = '''            val result = block()
            runCatching { restoreTicketTop() } // po każdej akcji bilet wraca na górę – bot dalej widzi ceny
            result'''
new = '''            // UI_RECOVERY_V1
            val expectedInstrument = _screenQuote.value?.instrument
            UiActionRecovery.run(
                instrument = expectedInstrument,
                action = block,
                restore = {
                    restoreTicketTop()
                    val root = appRoot() ?: error("Brak okna po akcji")
                    if (root.packageName?.toString() == ScreenScanner.XTB_PACKAGE) {
                        val quote = ScreenScanner.scanXtb(root, ScreenScanner.XTB_PACKAGE,
                            "XTB", System.currentTimeMillis())
                        check(expectedInstrument != null && quote?.instrument == expectedInstrument) {
                            "Nie potwierdzono właściwego instrumentu po akcji"
                        }
                        val bid = quote?.bid
                        val ask = quote?.ask
                        check(bid != null && ask != null && bid.isFinite() && ask.isFinite() && bid > 0 && ask >= bid) {
                            "Brak poprawnych cen Sell/Buy po akcji"
                        }
                        check(ScreenScanner.findById(root, "TradingBuyButton").singleOrNull()?.isEnabled == true &&
                            ScreenScanner.findById(root, "TradingSellButton").singleOrNull()?.isEnabled == true) {
                            "Przyciski transakcji nie są aktywne"
                        }
                        UiActionRecovery.observeReadyScreen(quote?.instrument, bid, ask)
                    } else {
                        error("Po akcji nie widzę okna XTB")
                    }
                },
                onRecoveryError = { failure ->
                    diag = "Błąd przywracania ekranu; nowe wejścia zablokowane: ${failure.message}"
                    Log.e(TAG, diag, failure)
                }
            )'''

old_submit = '        return svc.executeOnScreen(order, profile)'
new_submit = '''        if (!order.reduceOnly) {
            UiActionRecovery.blocked?.let {
                return ExecutionResult.Rejected("Nowe wejście zablokowane: ${it.reason}")
            }
        }
        return svc.executeOnScreen(order, profile)'''

old_scan = '            _screenQuote.value = quote'
new_scan = '''            _screenQuote.value = quote
            if (!_actionActive.value && pkg == ScreenScanner.XTB_PACKAGE &&
                ScreenScanner.findById(root, "TradingBuyButton").singleOrNull()?.isEnabled == true &&
                ScreenScanner.findById(root, "TradingSellButton").singleOrNull()?.isEnabled == true) {
                UiActionRecovery.observeReadyScreen(quote.instrument, quote.bid, quote.ask)
            }'''

for before, after in [(old, new), (old_submit, new_submit), (old_scan, new_scan)]:
    if text.count(before) != 1:
        raise SystemExit('Expected exactly one matching code block; no file written')
    text = text.replace(before, after, 1)
path.write_text(text, encoding='utf-8')
