package com.example.algohud.execution

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Recovery failures never replace an already obtained execution result. */
object UiActionRecovery {
    data class Block(val instrument: String?, val reason: String)
    @Volatile var blocked: Block? = null
        private set

    @Synchronized
    fun observeReadyScreen(instrument: String?, bid: Double?, ask: Double?) {
        val block = blocked ?: return
        if (instrument == null || bid == null || ask == null) return
        if (!bid.isFinite() || !ask.isFinite() || bid <= 0.0 || ask < bid) return
        if (block.instrument == null || block.instrument == instrument) blocked = null
    }

    suspend fun <T> run(
        instrument: String?,
        timeoutMs: Long = 6_000L,
        action: suspend () -> T,
        restore: suspend () -> Unit,
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
                } catch (failure: Exception) {
                    failure
                }
            }
            if (error != null) {
                blocked = Block(instrument, "Nie przywrócono biletu: ${error.message ?: error.javaClass.simpleName}")
                primary?.let { if (it !== error) it.addSuppressed(error) }
                try { onRecoveryError(error) } catch (_: Exception) {
                    // Blocking state remains available even when logging fails.
                }
            }
        }
    }
}
