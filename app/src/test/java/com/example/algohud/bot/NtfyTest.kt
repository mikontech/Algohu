package com.example.algohud.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NtfyTest {
    @Test fun topicIsRandomAndUrlSafe() {
        val a = Ntfy.newTopic()
        val b = Ntfy.newTopic()
        assertTrue(a.startsWith("algohud-"))
        assertEquals(18, a.length)
        assertTrue(a != b)
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' })
    }

    @Test fun deviceTopicIsStable() {
        assertEquals(Ntfy.topicFor("abc123"), Ntfy.topicFor("abc123"))
        assertTrue(Ntfy.topicFor("abc123") != Ntfy.topicFor("abc124"))
        assertEquals(18, Ntfy.topicFor("abc123").length)
    }

    @Test fun polishTitleIsEncodedInUrl() {
        val u = Ntfy.url("algohud-test", "🚨 Pozycja bez nadzoru – sprawdź", 5, "rotating_light")
        assertTrue(u, u.startsWith("https://ntfy.sh/algohud-test?title="))
        assertTrue(u, u.contains("&priority=5"))
        assertTrue(u, u.contains("&tags=rotating_light"))
        assertTrue(u, !u.contains(" ") && !u.contains("ź"))
    }
}
