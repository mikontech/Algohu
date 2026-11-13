package com.example.algohud.council

import android.content.SharedPreferences
import java.time.Instant
import java.time.ZoneOffset

/** Liczniki wspólne dla rady i nawigatora, zapisane przed próbą HTTP; brak automatycznych ponowień. */
class AiLimits(private val prefs: SharedPreferences, private val clock: () -> Long = System::currentTimeMillis) {
    fun reserveDebate(): String? = synchronized(LOCK) {
        reserve("debates", 12, 10 * 60_000L)
    }
    fun reserveObservation(): String? = synchronized(LOCK) {
        reserve("observations", 24, 10 * 60_000L)
    }
    fun reserveRequest(provider: AiProvider): String? = synchronized(LOCK) {
        val now = clock()
        val until = prefs.getLong("ai_backoff_${provider.name}", 0)
        if (until > now) "${provider.name}: przerwa po limicie API (${(until - now + 999) / 1000}s)"
        else reserve("requests_${provider.name}", 60, 0)
    }
    fun backoff(provider: AiProvider, retrySeconds: Long?) = synchronized(LOCK) {
        val delay = maxOf(15 * 60L, (retrySeconds ?: 0).coerceIn(0, 7 * 86400L)) * 1000
        val key = "ai_backoff_${provider.name}"
        prefs.edit().putLong(key, maxOf(prefs.getLong(key, 0), clock() + delay)).commit()
        Unit
    }
    private fun reserve(bucket: String, cap: Int, gapMs: Long): String? {
        val now = clock()
        val prefix = "ai_limit_$bucket"
        val today = Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
        val savedDay = prefs.getLong("${prefix}_day", Long.MIN_VALUE)
        // Cofnięcie zegara nie resetuje budżetu.
        val sameDay = savedDay >= today
        val count = if (sameDay) prefs.getLong("${prefix}_count", 0) else 0
        val last = prefs.getLong("${prefix}_last", Long.MIN_VALUE)
        if (count >= cap) return "$bucket: limit $cap/dobę UTC"
        if (gapMs > 0 && last != Long.MIN_VALUE && now - last < gapMs)
            return "debata nie częściej niż co 10 minut"
        if (!prefs.edit().putLong("${prefix}_day", maxOf(today, savedDay)).putLong("${prefix}_count", count + 1)
                .putLong("${prefix}_last", now).commit()) return "nie można zapisać licznika API"
        return null
    }
    companion object { private val LOCK = Any() }
}
