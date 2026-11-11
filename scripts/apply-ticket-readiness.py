from pathlib import Path
import hashlib
import subprocess

root = Path(__file__).resolve().parents[1]
base = 'app/src/main/java/com/example/algohud/execution/'
tests = 'app/src/test/java/com/example/algohud/execution/'
def git(*args):
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()
def blob(data):
    return hashlib.sha1(b'blob ' + str(len(data)).encode() + b'\0' + data).hexdigest()
if git('branch', '--show-current') != 'feature/research-lab':
    raise SystemExit('STOP: wrong branch; main must remain unchanged')
expected = {
    base + 'AutoExecutionService.kt': '2537ff343bf2cf5765c5927c35f4dac67c1a46a1',
    base + 'UiActionRecovery.kt': 'a8e329e46e8c5433f7e33c0730b6aec3745833d3',
    base + 'ScreenScanner.kt': '338cb1694ac348a88a91853ceabad26f07e119f1',
    tests + 'UiActionRecoveryTest.kt': '004d7e41bcaab09b8fbf9a4610149ce8cebc3df9',
}
for name, sha in expected.items():
    if blob((root / name).read_bytes()) != sha:
        raise SystemExit('STOP: unexpected version of ' + name)
source = (root / (base + 'AutoExecutionService.kt')).read_text(encoding='utf-8')
def replace(old, new):
    global source
    if source.count(old) != 1:
        raise SystemExit('STOP: integration anchor must occur exactly once: ' + old[:90])
    source = source.replace(old, new, 1)
replace('        val result = svc.executeOnScreen(order, profile)', '''        val resetToken = if (profile.packageName == ScreenScanner.XTB_PACKAGE && !order.reduceOnly) {
            UiActionRecovery.beginTicketReset(order.symbol)
                ?: return ExecutionResult.Rejected("Bilet jest już zablokowany lub trwa jego reset")
        } else null
        val result = svc.executeOnScreen(order, profile)''')
replace('''        if (profile.packageName == ScreenScanner.XTB_PACKAGE && !order.reduceOnly &&
            (result is ExecutionResult.Filled || result is ExecutionResult.Submitted)) {''', '''        if (resetToken != null &&
            (result is ExecutionResult.Filled || result is ExecutionResult.Submitted)) {''')
replace('                    svc.resetNextOrderTicket(order.symbol)', '                    svc.resetNextOrderTicket(order.symbol, resetToken)')
replace('UiActionRecovery.observeReadyScreen(quote.instrument, quote.bid, quote.ask)', 'UiActionRecovery.observeReadyScreen(XtbTicketReader.read(root))')
old_reset = '''    suspend fun resetNextOrderTicket(instrument: String): Unit =
        withContext(Dispatchers.Main.immediate) {
            withAction {
                restoreTicketTop()
                TicketProtectionReset.reset(
                    instrument = instrument,
                    readRoot = { appRoot() },
                    reveal = { id -> revealById(id) != null },
                    hideKeyboard = {
                        if (windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }) {
                            check(performGlobalAction(GLOBAL_ACTION_BACK)) { "Nie zamknięto klawiatury" }
                            delay(350)
                        }
                    }
                )
            }
        }'''
new_reset = old_reset.replace('resetNextOrderTicket(instrument: String)', 'resetNextOrderTicket(instrument: String, resetToken: Long)').replace('withAction {', 'withAction(expectedInstrument = instrument) {').replace('''                )
            }''', '''                )
                check(UiActionRecovery.markTicketResetVerified(resetToken)) {
                    "Nieaktualna sesja resetu biletu; nowe wejścia pozostają zablokowane"
                }
            }''')
replace(old_reset, new_reset)
replace('    private suspend fun <T> withAction(block: suspend () -> T): T = uiMutex.withLock {', '''    private suspend fun <T> withAction(
        expectedInstrument: String? = _screenQuote.value?.instrument,
        block: suspend () -> T
    ): T = uiMutex.withLock {''')
replace('''            val expectedInstrument = _screenQuote.value?.instrument
            UiActionRecovery.run(
                instrument = expectedInstrument,
                action = block,''', '''            UiActionRecovery.run(
                instrument = expectedInstrument,
                timeoutMs = 12_000L,
                action = block,''')
replace('''                    restoreTicketTop()
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
                    }''', '''                    val instrument = checkNotNull(expectedInstrument) {
                        "Nie określono instrumentu do przywrócenia"
                    }
                    check(appRoot()?.packageName?.toString() == ScreenScanner.XTB_PACKAGE) {
                        "Przed przywracaniem nie widzę okna XTB"
                    }
                    restoreTicketTop()
                    val ready = TicketReadiness.awaitReady(
                        instrument = instrument,
                        read = { XtbTicketReader.read(appRoot()) }
                    )
                    UiActionRecovery.observeReadyScreen(ready)''')
if 'observeReadyScreen(quote' in source:
    raise SystemExit('STOP: obsolete quote-only recovery remains')
prepared = {base + 'AutoExecutionService.kt': source}
for name in ['TicketReadiness.kt', 'XtbTicketReader.kt', 'UiActionRecovery.kt', 'UiActionRecoveryTest.kt', 'TicketReadinessTest.kt']:
    target = (tests if name.endswith('Test.kt') else base) + name
    if target not in expected and (root / target).exists():
        raise SystemExit('STOP: new target already exists: ' + target)
    prepared[target] = (root / 'scripts/ticket-readiness' / name).read_text(encoding='utf-8')
for target, content in prepared.items():
    path = root / target
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding='utf-8')
print('Applied guarded readiness integration to', len(prepared), 'files. No trading actions performed.')
