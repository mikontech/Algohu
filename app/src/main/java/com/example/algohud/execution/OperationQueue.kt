package com.example.algohud.execution

import com.example.algohud.core.ExecutionResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** FIFO serialization. Distinct IDs for audit, one in-flight intent per instrument/action. */
class OperationQueue(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val serial = Mutex()
    private val lock = Any()
    private val active = HashSet<String>()
    private val seen = LinkedHashSet<String>()
    suspend fun run(id: String, key: String, maxWaitMs: Long, action: suspend () -> ExecutionResult): ExecutionResult {
        synchronized(lock) {
            if (id in seen || !active.add(key)) return ExecutionResult.Rejected("Operacja już przyjęta / trwa")
            if (active.size > 16) { active.remove(key); return ExecutionResult.Rejected("Kolejka pełna") }
            seen.add(id)
        }
        val queued = clock()
        try {
            return serial.withLock {
                if (clock() - queued > maxWaitMs) ExecutionResult.Rejected("Operacja wygasła w kolejce") else action()
            }
        } finally {
            synchronized(lock) {
                active.remove(key)
                while (seen.size > 1024) seen.remove(seen.first())
            }
        }
    }
}

data class OperationStatus(val id: String = "", val symbol: String = "", val text: String = "",
    val active: Boolean = false, val error: Boolean = false, val at: Long = System.currentTimeMillis())
