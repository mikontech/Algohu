package com.example.algohud.execution

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Pending reset is independent from quote recovery. State remains in memory only. */
class TicketRecoveryState(private val nowNanos: () -> Long = System::nanoTime) {
    private data class PendingReset(val token: Long, val instrument: String)
    private var serial = 0L
    private var pending: PendingReset? = null
    private var readyAfterNanos = Long.MIN_VALUE
    @Volatile var blocked: UiActionRecovery.Block? = null
        private set

    @Synchronized fun beginTicketReset(instrument: String): Long? {
        require(instrument.isNotBlank())
        if (blocked != null || pending != null) return null
        val token = ++serial
        pending = PendingReset(token, instrument)
        readyAfterNanos = nowNanos()
        blocked = UiActionRecovery.Block(instrument,
            "Trwa obsługa wejścia; oczekuję potwierdzenia resetu TP/SL nowego biletu")
        return token
    }

    /** Only after successful TicketProtectionReset.reset(). */
    @Synchronized fun markTicketResetVerified(token: Long): Boolean {
        val current = pending ?: return false
        if (current.token != token) return false
        pending = null
        readyAfterNanos = nowNanos()
        blocked = UiActionRecovery.Block(current.instrument,
            "Reset TP/SL potwierdzony; oczekuję świeżego odczytu gotowego formularza")
        return true
    }

    @Synchronized fun observeReadyScreen(snapshot: TicketReadiness.Snapshot) {
        val current = blocked ?: return
        if (pending != null) return
        val instrument = current.instrument ?: return
        val age = nowNanos() - snapshot.capturedAtNanos
        if (age < 0L || age > 1_000_000_000L || snapshot.capturedAtNanos <= readyAfterNanos) return
        if (snapshot.failure(instrument) == null) blocked = null
    }

    @Synchronized private fun failed(instrument: String?, error: Exception) {
        readyAfterNanos = nowNanos()
        blocked = UiActionRecovery.Block(pending?.instrument ?: instrument,
            "Nie przywrócono biletu: ${error.message ?: error.javaClass.simpleName}")
    }

    suspend fun <T> run(
        instrument: String?, timeoutMs: Long = 6_000L,
        action: suspend () -> T, restore: suspend () -> Unit,
        onRecoveryError: (Exception) -> Unit
    ): T {
        require(timeoutMs > 0L)
        var primary: Throwable? = null
        try {
            return action()
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            val error = withContext(NonCancellable) {
                try {
                    withTimeout(timeoutMs) { restore() }
                    null
                } catch (failure: Exception) { failure }
            }
            if (error != null) {
                failed(instrument, error)
                primary?.let { if (it !== error) it.addSuppressed(error) }
                try { onRecoveryError(error) } catch (_: Exception) { }
            }
        }
    }
}

object UiActionRecovery {
    data class Block(val instrument: String?, val reason: String)
    private val state = TicketRecoveryState()
    val blocked: Block? get() = state.blocked
    fun beginTicketReset(instrument: String): Long? = state.beginTicketReset(instrument)
    fun markTicketResetVerified(token: Long): Boolean = state.markTicketResetVerified(token)
    fun observeReadyScreen(snapshot: TicketReadiness.Snapshot) = state.observeReadyScreen(snapshot)
    suspend fun <T> run(
        instrument: String?, timeoutMs: Long = 6_000L,
        action: suspend () -> T, restore: suspend () -> Unit,
        onRecoveryError: (Exception) -> Unit
    ): T = state.run(instrument, timeoutMs, action, restore, onRecoveryError)
}
