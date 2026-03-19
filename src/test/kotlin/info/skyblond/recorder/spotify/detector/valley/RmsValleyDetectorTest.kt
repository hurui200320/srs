package info.skyblond.recorder.spotify.detector.valley

import info.skyblond.recorder.spotify.wav.WavSampleReader
import java.io.File
import kotlin.math.PI
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
        val numSamples = 44100
        val left = sineWave(440.0, numSamples)
        val right = sineWave(440.0, numSamples)
        val frameSize = 441

        val rms = detector.computeRmsProfile(left, right, frameSize)
        assertTrue(rms.isNotEmpty())

        val expectedRms = 1.0 / sqrt(2.0)
        for (i in rms.indices) {
            assertEquals(expectedRms, rms[i], 0.01, "Frame $i RMS mismatch")
        }
    }

    @Test
    fun `rms of silence is zero`() {
        val left = FloatArray(4410)
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

        assertTrue(smoothed[3] < 1.0, "Spike should be reduced")
        assertTrue(smoothed[3] > 0.0, "Spike should not be eliminated")
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

    // ── detectValleys() Integration Tests ───────────────────────────

    @Test
    fun `detect clear silence gap returns valley`() {
        val reader = WavSampleReader(gapFile("gap_clear.wav"))
        val valleys = detector.detectValleys(reader, 0.0, reader.duration)

        assertTrue(valleys.isNotEmpty(), "Should detect at least one valley")
        val v = valleys.first()
        assertTrue(v.startTime >= 0.0, "Valley startTime should be non-negative")
        assertTrue(v.endTime > v.startTime, "Valley endTime should be after startTime")
        assertTrue(v.bottomTime >= v.startTime && v.bottomTime <= v.endTime,
            "bottomTime should be within valley bounds")
    }

    @Test
    fun `detect short silence gap returns valley`() {
        val reader = WavSampleReader(gapFile("gap_short.wav"))
        val valleys = detector.detectValleys(reader, 0.0, reader.duration)

        assertTrue(valleys.isNotEmpty(), "Should detect at least one valley in short gap")
    }

    @Test
    fun `detect no gap returns empty`() {
        val reader = WavSampleReader(gapFile("no_gap.wav"))
        val valleys = detector.detectValleys(reader, 0.0, reader.duration)

        assertTrue(valleys.isEmpty(), "Continuous tone should produce no valleys")
    }

    @Test
    fun `detect quiet non-silent gap may not be detected with strict threshold`() {
        // gap_quiet.wav has 0.01 amplitude in the gap. With valleyThresholdFactor=0.01,
        // the threshold ≈ median(~0.7) * 0.01 = 0.007, which is close to the gap's RMS (~0.007).
        // The quiet gap may or may not be detected depending on exact values.
        // With a more lenient threshold, it should be detected.
        val lenientDetector = RmsValleyDetector(valleyThresholdFactor = 0.05)
        val reader = WavSampleReader(gapFile("gap_quiet.wav"))
        val valleys = lenientDetector.detectValleys(reader, 0.0, reader.duration)

        assertTrue(valleys.isNotEmpty(), "Should detect quiet gap with lenient threshold")
    }

    @Test
    fun `valley bottomTime is within the silence gap`() {
        // gap_clear.wav: 440Hz 0.5s → silence 0.3s → 880Hz 0.5s
        val reader = WavSampleReader(gapFile("gap_clear.wav"))
        val valleys = detector.detectValleys(reader, 0.0, reader.duration)

        assertTrue(valleys.isNotEmpty(), "Should detect valley")
        val v = valleys.first()
        assertTrue(v.bottomTime > 0.45, "bottomTime should be after Song A ends, got ${v.bottomTime}")
        assertTrue(v.bottomTime < 0.85, "bottomTime should be before Song B starts, got ${v.bottomTime}")
    }

    @Test
    fun `valley bottomEnergy is very low for clear gap`() {
        val reader = WavSampleReader(gapFile("gap_clear.wav"))
        val valleys = detector.detectValleys(reader, 0.0, reader.duration)

        assertTrue(valleys.isNotEmpty())
        val v = valleys.first()
        assertTrue(v.bottomEnergy < 0.01, "Clear gap should have very low bottomEnergy, got ${v.bottomEnergy}")
    }

    @Test
    fun `window smaller than one frame returns empty`() {
        val reader = WavSampleReader(gapFile("gap_clear.wav"))
        val valleys = detector.detectValleys(reader, 0.5, 0.505)

        assertTrue(valleys.isEmpty(), "Tiny window should produce no valleys")
    }
}
