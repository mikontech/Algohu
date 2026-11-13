package com.example.algohud.council

import android.content.SharedPreferences

enum class AiProvider { CLAUDE, GEMINI, GROQ }
enum class AiRoute { GEMINI_GROQ, GEMINI, GROQ, CLAUDE }
data class AiPlan(val fast: String, val deep: String) {
    val providers get() = listOf(fast, deep).map { AiSettings.provider(it) }.distinct()
}

object AiSettings {
    const val GEMINI_MODEL = "gemini-3.8-flash"
    const val GROQ_MODEL = "qwen/qwen3.8-27b"
    fun keyPref(p: AiProvider) = when (p) {
        AiProvider.CLAUDE -> "council_api_key"
        AiProvider.GEMINI -> "gemini_api_key"
        AiProvider.GROQ -> "groq_api_key"
    }
    fun key(prefs: SharedPreferences, p: AiProvider) = prefs.getString(keyPref(p), null)?.trim()
    fun route(prefs: SharedPreferences): AiRoute {
        val fallback = if (!key(prefs, AiProvider.CLAUDE).isNullOrBlank()) AiRoute.CLAUDE else AiRoute.GEMINI_GROQ
        return runCatching { AiRoute.valueOf(prefs.getString("ai_route", fallback.name)!!) }.getOrDefault(fallback)
    }
    fun provider(model: String): AiProvider = when (model.substringBefore(':', "")) {
        "gemini" -> AiProvider.GEMINI
        "groq" -> AiProvider.GROQ
        "claude", "" -> AiProvider.CLAUDE
        else -> error("Nieznany dostawca AI")
    }
    fun plan(prefs: SharedPreferences): AiPlan {
        val gemini = "gemini:" + (prefs.getString("gemini_model", null)?.takeIf { it.isNotBlank() } ?: GEMINI_MODEL)
        val groq = "groq:" + (prefs.getString("groq_model", null)?.takeIf { it.isNotBlank() } ?: GROQ_MODEL)
        return when (route(prefs)) {
            AiRoute.GEMINI_GROQ -> AiPlan(gemini, groq)
            AiRoute.GEMINI -> AiPlan(gemini, gemini)
            AiRoute.GROQ -> AiPlan(groq, groq)
            AiRoute.CLAUDE -> AiPlan(prefs.getString("council_fast_model", null) ?: AgentCouncil.DEFAULT_FAST_MODEL,
                prefs.getString("council_deep_model", null) ?: AgentCouncil.DEFAULT_DEEP_MODEL)
        }
    }
    fun hasKeys(prefs: SharedPreferences, plan: AiPlan = plan(prefs)) = plan.providers.all { !key(prefs, it).isNullOrBlank() }
}
