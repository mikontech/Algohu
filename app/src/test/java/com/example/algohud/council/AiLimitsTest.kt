package com.example.algohud.council

import org.junit.Assert.*
import org.junit.Test

class AiLimitsTest {
    @Test fun debateCooldownSurvivesNewInstanceAndCapsDaily() {
        var now = 1_000_000L
        val p = memoryPrefs()
        assertNull(AiLimits(p) { now }.reserveDebate())
        assertNotNull(AiLimits(p) { now }.reserveDebate())
        repeat(11) { now += 600_000; assertNull(AiLimits(p) { now }.reserveDebate()) }
        now += 600_000
        assertNotNull(AiLimits(p) { now }.reserveDebate())
        now += 86_400_000
        assertNull(AiLimits(p) { now }.reserveDebate())
    }
    @Test fun requestBudgetSharedAcrossClientsButSeparatePerProvider() {
        val p = memoryPrefs()
        repeat(60) { assertNull(AiLimits(p) { 1_000_000 }.reserveRequest(AiProvider.GROQ)) }
        assertNotNull(AiLimits(p) { 1_000_000 }.reserveRequest(AiProvider.GROQ))
        assertNull(AiLimits(p) { 1_000_000 }.reserveRequest(AiProvider.GEMINI))
    }
    @Test fun clockRollbackDoesNotResetBudget() {
        var now = 100_000_000L
        val p = memoryPrefs()
        repeat(60) { assertNull(AiLimits(p) { now }.reserveRequest(AiProvider.GROQ)) }
        now -= 86_400_000
        assertNotNull(AiLimits(p) { now }.reserveRequest(AiProvider.GROQ))
    }
}
