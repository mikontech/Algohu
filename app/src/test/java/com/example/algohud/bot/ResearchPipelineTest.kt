package com.example.algohud.bot

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ResearchPipelineTest {
    private fun request() = ResearchPipeline.Request(
        "TEST", CandleLabeler.Config(60_000L, 1, 1, 0.75, 0.0),
        WalkForwardValidator.Config(9, 3, 0L,
            DirectionModel.Config(epochs = 10, minSamples = 3)),
        TradeSimulator.Config(60_000L, 1000.0, 0.0, 0.0, 1.0, 0.0,
            0.01, 0.01, 100.0, 0.01, 0.01, 10.0, 0.5,
            1.0, 1.0, 1, 0.6, 0.1, true))

    private fun writeRecording(dir: File, count: Int = 60) {
        File(dir, "rec_TEST_1m.csv").writeText((0 until count).joinToString("\n", postfix = "\n") { i ->
            val high = if (i % 3 == 0) 104 else 101
            val low = if (i % 3 == 1) 96 else 99
            "${(i + 1) * 60_000L},100,$high,$low,100,10"
        })
    }

    @Test fun endToEndReportsArePublishedAndRunsDoNotOverwrite() = runBlocking {
        val dir = Files.createTempDirectory("research-test").toFile()
        try {
            writeRecording(dir)
            val first = ResearchPipeline.run(dir, request())
            val second = ResearchPipeline.run(dir, request())
            assertEquals(60, first.inputCandles)
            assertEquals(59, first.usedCandles)
            assertTrue(first.validation.forecasts.isNotEmpty())
            assertNotEquals(first.outputDirectory, second.outputDirectory)
            assertEquals(first.inputSha256, second.inputSha256)
            assertEquals(64, first.inputSha256.length)
            listOf("input.csv", "labels.csv", "forecasts.csv", "trades.csv",
                "report.txt", "settings.txt").forEach {
                assertTrue(File(first.outputDirectory, it).length() > 0L)
            }
            assertTrue(File(dir, "research").listFiles()!!.none { it.name.startsWith(".pending_") })
        } finally { dir.deleteRecursively() }
    }

    @Test fun insufficientDataLeavesNoPartialReport() = runBlocking {
        val dir = Files.createTempDirectory("research-short").toFile()
        try {
            writeRecording(dir, 3)
            try {
                ResearchPipeline.run(dir, request())
                fail("Expected insufficient data")
            } catch (expected: IllegalArgumentException) {
                assertTrue(File(dir, "research").listFiles()!!.isEmpty())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun corruptInputIsRejectedWithoutPublishingReports() = runBlocking {
        val dir = Files.createTempDirectory("research-corrupt").toFile()
        try {
            writeRecording(dir)
            File(dir, "rec_TEST_1m.csv").appendText("broken\n")
            try {
                ResearchPipeline.run(dir, request())
                fail("Expected corrupt input")
            } catch (expected: IllegalArgumentException) {
                assertTrue(File(dir, "research").listFiles()!!.isEmpty())
            }
        } finally { dir.deleteRecursively() }
    }
}
