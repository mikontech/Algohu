package com.example.algohud.research

import com.example.algohud.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Ograniczona kolejka; JSONL na IO, rotacja 4 × 2 MB; żadnych kluczy ani zrzutów ekranu. */
class ResearchRecorder(dir: File) {
    companion object { val fileLock = Any() }
    private val sessionId = java.util.UUID.randomUUID().toString()
    private val folder = File(dir, "research").apply { mkdirs() }
    private val channel = Channel<Pair<String, Map<String, Any?>>>(2048)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dropped = AtomicLong()
    private val book = ShadowBook(::record)
    init { scope.launch {
        for ((event, data) in channel) {
            try {
                synchronized(fileLock) {
                val file = File(folder, "events.jsonl")
                if (file.length() > 2_000_000) {
                    File(folder,"events.3.jsonl").delete()
                    for (i in 2 downTo 1) File(folder,"events.$i.jsonl").renameTo(File(folder,"events.${i+1}.jsonl"))
                    file.renameTo(File(folder,"events.1.jsonl"))
                }
                file.appendText(JSONObject(data).put("event", event).toString()+"\n")
                }
            } catch (_: Exception) { dropped.incrementAndGet() }
        }
        scope.cancel()
    } }
    fun record(event: String, data: Map<String, Any?>) {
        if (!channel.trySend(event to (data + mapOf("recordedAt" to System.currentTimeMillis(), "session" to sessionId))).isSuccess) dropped.incrementAndGet()
    }
    fun observe(o: OrderRequest, mode: ExecutionMode, result: ExecutionResult?, q: ScreenQuote?) {
        val data = mapOf("id" to o.clientOrderId, "symbol" to o.symbol, "side" to o.side.name,
            "strategy" to o.reason, "signalAt" to o.signalAt, "mode" to mode.name, "reduceOnly" to o.reduceOnly,
            "reference" to o.referencePrice, "brokerAnchor" to o.brokerReferencePrice, "stop" to o.stopPrice,
            "tp" to o.takeProfit, "quantity" to o.quantity, "bid" to q?.bid, "ask" to q?.ask,
            "quoteAt" to q?.timestamp, "currency" to q?.accountCurrency,
            "elapsedMs" to System.currentTimeMillis()-o.signalAt,
            "fill" to (result as? ExecutionResult.Filled)?.avgPrice,
            "source" to if (mode == ExecutionMode.DRY_RUN) "SIMULATION" else if (result is ExecutionResult.Filled) "EXECUTOR_FILL" else "UNCONFIRMED",
            "result" to result?.toString())
        record(if (result == null) "candidate" else "result", data)
        if (result == null) book.candidate(o,q,System.currentTimeMillis())
    }
    fun quote(q: ScreenQuote) {
        book.quote(q,System.currentTimeMillis())
        record("quote", mapOf("symbol" to q.instrument,"bid" to q.bid,"ask" to q.ask,"at" to q.timestamp))
    }
    fun summary() = book.summary()+"\nUtracone rekordy: ${dropped.get()}. Zapis JSONL: research/.\nKandydaci = propozycje przekazane do silnika; wcześniejsze filtry strategii nie są symulowane."
    fun close() { record("session_end", mapOf("open_simulations" to "not_closed_or_carried_forward")); channel.close() }
}
