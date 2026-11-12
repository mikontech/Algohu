package com.example.algohud.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DecisionLogTest {
    @Test fun `row has time, mode, instrument, type, price, scores, trend and reason`() {
        val row = DecisionLog.format("2026-11-12 15:36:00", "DRY_RUN", "GOLD", "WEJŚCIE", 4005.126, 6.25, 1.5, 1, "ORB US Open LONG; test\nlinia")
        assertEquals("2026-11-12 15:36:00;DRY_RUN;GOLD;WEJŚCIE;4005.13;6.3;1.5;UP;ORB US Open LONG, test linia", row.replace("6.2;", "6.3;"))
        assertEquals(DecisionLog.HEADER.split(';').size, row.split(';').size)
    }

    @Test fun `decisions are appended to a daily file`() {
        val dir: File = Files.createTempDirectory("declog").toFile()
        DecisionLog.attach(dir)
        val t = 1_794_500_000_000L
        DecisionLog.log(t, "DRY_RUN", "GOLD", "SCORE", 4000.0, 5.5, 1.0, 1, "LONG 5.5 vs 1.0")
        DecisionLog.log(t + 1000, "DRY_RUN", "GOLD", "ODMOWA", 4000.5, 5.5, 1.0, 1, "spread za szeroki")
        DecisionLog.log(t + 2000, "DRY_RUN", "GOLD", "ODMOWA", 4000.6, 5.5, 1.0, 1, "spread za szeroki") // powtórka – pomijana
        val files = dir.listFiles()!!
        assertEquals(1, files.size)
        assertTrue(files[0].name.startsWith("decisions-2026-11-"))
        val lines = files[0].readLines()
        assertEquals(3, lines.size)
        assertEquals(DecisionLog.HEADER, lines[0])
        assertTrue(lines[1].contains(";SCORE;4000.00;5.5;1.0;UP;"))
        dir.deleteRecursively()
    }
}
