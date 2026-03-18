package info.skyblond.recorder.spotify.detector.boundary

import info.skyblond.recorder.spotify.wav.WavSampleReader
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RmsValleyDetectorTest {

    companion object {
        private fun gapFile(name: String): File {
            val url = RmsValleyDetectorTest::class.java.getResource("/wav/gap/$name")
                ?: throw IllegalStateException("Test resource not found: /wav/gap/$name")
            return File(url.toURI())
        }

        /** Generate a sine wave FloatArray for testing. */
        private fun sineWave(frequency: Double, numSamples: Int, amplitude: Float = 1.0f): FloatArray {
            return FloatArray(numSamples) { i ->
                (amplitude * sin(2.0 * PI * frequency * i / 44100.0)).toFloat()
            }
        }
    }

    private val detector = RmsValleyDetector()

    // ── RMS Computation Unit Tests ──────────────────────────────────

    @Test
    fun `rms of full-scale sine wave is approximately 1 over sqrt 2`() {
        // A full-scale sine has RMS = 1/sqrt(2) ≈ 0.7071
        val numSamples = 44100 // 1 second = many complete cycles at 440Hz
        val left = sineWave(440.0, numSamples)
        val right = sineWave(440.0, numSamples)
        val frameSize = 441 // 10ms

        val rms = detector.computeRmsProfile(left, right, frameSize)
        assertTrue(rms.isNotEmpty())

        val expectedRms = 1.0 / sqrt(2.0)
        for (i in rms.indices) {
            assertEquals(expectedRms, rms[i], 0.01, "Frame $i RMS mismatch")
        }
    }

    @Test
    fun `rms of silence is zero`() {
        val left = FloatArray(4410) // 100ms of silence
        val right = FloatArray(4410)
        val frameSize = 441

        val rms = detector.computeRmsProfile(left, right, frameSize)
        assertTrue(rms.isNotEmpty())

        for (i in rms.indices) {
            assertEquals(0.0, rms[i], 1e-10, "Frame $i should be zero")
        }
    }

    @Test
    fun `rms of half-amplitude sine is half of full`() {
        val numSamples = 44100
        val left = sineWave(440.0, numSamples, amplitude = 0.5f)
        val right = sineWave(440.0, numSamples, amplitude = 0.5f)
        val frameSize = 441

        val rms = detector.computeRmsProfile(left, right, frameSize)
        assertTrue(rms.isNotEmpty())

        val expectedRms = 0.5 / sqrt(2.0)
        for (i in rms.indices) {
            assertEquals(expectedRms, rms[i], 0.01, "Frame $i RMS mismatch")
        }
    }

    // ── Smooth Unit Tests ───────────────────────────────────────────

    @Test
    fun `smooth preserves constant signal`() {
        val values = DoubleArray(20) { 0.5 }
        val smoothed = detector.smooth(values, 5)

        assertEquals(values.size, smoothed.size)
        for (i in smoothed.indices) {
            assertEquals(0.5, smoothed[i], 1e-10, "Index $i should remain 0.5")
        }
    }

    @Test
    fun `smooth reduces spike`() {
        val values = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0)
        val smoothed = detector.smooth(values, 5)

        // The spike at index 3 should be reduced
        assertTrue(smoothed[3] < 1.0, "Spike should be reduced")
        assertTrue(smoothed[3] > 0.0, "Spike should not be eliminated")
        // Neighbors should pick up some energy
        assertTrue(smoothed[2] > 0.0, "Left neighbor should increase")
        assertTrue(smoothed[4] > 0.0, "Right neighbor should increase")
    }

    @Test
    fun `smooth with window 1 is identity`() {
        val values = doubleArrayOf(0.1, 0.5, 0.3, 0.9, 0.2)
        val smoothed = detector.smooth(values, 1)

        assertEquals(values.size, smoothed.size)
        for (i in values.indices) {
            assertEquals(values[i], smoothed[i], 1e-10, "Index $i should be unchanged")
        }
    }

    // ── detect() Integration Tests ──────────────────────────────────

    @Test
    fun `detect clear silence gap`() {
        val reader = WavSampleReader(gapFile("gap_clear.wav"))
        val candidates = detector.detect(reader, 0.0, reader.duration)

        assertTrue(candidates.isNotEmpty(), "Should detect at least one boundary")
        val best = candidates.first()
        assertTrue(best.confidence > 0.5, "Clear gap should have high confidence, got ${best.confidence}")
    }

    @Test
    fun `detect short silence gap`() {
        val reader = WavSampleReader(gapFile("gap_short.wav"))
        val candidates = detector.detect(reader, 0.0, reader.duration)

        assertTrue(candidates.isNotEmpty(), "Should detect at least one boundary in short gap")
    }

    @Test
    fun `detect no gap returns empty`() {
        val reader = WavSampleReader(gapFile("no_gap.wav"))
        val candidates = detector.detect(reader, 0.0, reader.duration)

        assertTrue(candidates.isEmpty(), "Continuous tone should produce no candidates")
    }

    @Test
    fun `detect quiet non-silent gap`() {
        val reader = WavSampleReader(gapFile("gap_quiet.wav"))
        val candidates = detector.detect(reader, 0.0, reader.duration)

        assertTrue(candidates.isNotEmpty(), "Should detect quiet gap")
        val best = candidates.first()
        // Quiet gap should have lower confidence than clear silence
        assertTrue(best.confidence > 0.0, "Should have positive confidence")
    }

    @Test
    fun `detected boundary is near gap end`() {
        // gap_clear.wav: 440Hz 0.5s → silence 0.3s → 880Hz 0.5s
        // timestamp = valley.endFrame ≈ where energy rises ≈ 0.8s (Song B start)
        val reader = WavSampleReader(gapFile("gap_clear.wav"))
        val candidates = detector.detect(reader, 0.0, reader.duration)

        assertTrue(candidates.isNotEmpty(), "Should detect boundary")
        val best = candidates.first()
        assertTrue(best.timestamp > 0.7, "Valley end should be near Song B start, got ${best.timestamp}")
        assertTrue(best.timestamp < 0.9, "Valley end should not overshoot, got ${best.timestamp}")
    }

    @Test
    fun `candidate source and featureDuration are correct`() {
        val reader = WavSampleReader(gapFile("gap_clear.wav"))
        val candidates = detector.detect(reader, 0.0, reader.duration)

        assertTrue(candidates.isNotEmpty())
        for (c in candidates) {
            assertEquals(BoundarySource.RMS_VALLEY, c.source)
            assertTrue(c.featureDurationMs > 0.0, "Feature duration should be positive, got ${c.featureDurationMs}")
        }
    }

    @Test
    fun `candidate confidence in valid range`() {
        val reader = WavSampleReader(gapFile("gap_clear.wav"))
        val candidates = detector.detect(reader, 0.0, reader.duration)

        assertTrue(candidates.isNotEmpty())
        for (c in candidates) {
            assertTrue(c.confidence > 0.0, "Confidence should be positive, got ${c.confidence}")
            assertTrue(c.confidence <= 1.0, "Confidence should be <= 1.0, got ${c.confidence}")
        }
    }

    @Test
    fun `window smaller than one frame returns empty`() {
        val reader = WavSampleReader(gapFile("gap_clear.wav"))
        // 10ms frame → window of 5ms should be too small
        val candidates = detector.detect(reader, 0.5, 0.505)

        assertTrue(candidates.isEmpty(), "Tiny window should produce no candidates")
    }
}
