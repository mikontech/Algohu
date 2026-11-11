from pathlib import Path
import subprocess

path = Path('app/src/main/java/com/example/algohud/execution/AutoExecutionService.kt')
sha = subprocess.check_output(['git', 'hash-object', str(path)], text=True).strip()
if sha != '8523a2c4eb09c52d122e1fd8f239ab6a1798b617':
    raise SystemExit(f'Unexpected executor blob {sha}; no changes applied')
text = path.read_text(encoding='utf-8')
old_submit = '        return svc.executeOnScreen(order, profile)'
new_submit = '''        val result = svc.executeOnScreen(order, profile)
        if (profile.packageName == ScreenScanner.XTB_PACKAGE && !order.reduceOnly &&
            (result is ExecutionResult.Filled || result is ExecutionResult.Submitted)) {
            return UiActionRecovery.run(
                instrument = order.symbol,
                timeoutMs = 15_000L,
                action = { result },
                restore = {
                    check(svc.xtbPositionExists(order.symbol) == true) {
                        "Nie potwierdzono pozycji; nie resetuję pól SL/TP"
                    }
                    svc.resetNextOrderTicket(order.symbol)
                },
                onRecoveryError = { failure ->
                    AutoExecutionService.diag = "Reset nowego biletu zablokowany: ${failure.message}"
                    Log.e("TicketProtectionReset", AutoExecutionService.diag, failure)
                }
            )
        }
        return result'''
anchor = '    private suspend fun <T> withAction(block: suspend () -> T): T = uiMutex.withLock {'
method = '''    // NEXT_ORDER_TICKET_RESET_V1
    suspend fun resetNextOrderTicket(instrument: String): Unit =
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
        }

'''
for before, after in [(old_submit, new_submit), (anchor, method + anchor)]:
    if text.count(before) != 1:
        raise SystemExit('Expected exactly one integration anchor; no file written')
    text = text.replace(before, after, 1)
path.write_text(text, encoding='utf-8')
