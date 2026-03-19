package info.skyblond.recorder.spotify.detector.valley

import info.skyblond.recorder.spotify.wav.WavSampleReader
import kotlin.math.sqrt

/**
 * Detects RMS energy valleys in an audio signal.
 *
 * Computes a per-frame RMS energy profile, applies smoothing, and scans for
 * contiguous low-energy regions ("valleys"). Returns all detected valleys
 * as [ValleyInfo] objects for the caller to use in pairing and scoring.
 *
 * @param frameDurationMs duration of each analysis frame in milliseconds (default 10ms)
 * @param smoothingWindowMs duration of the smoothing moving-average window in milliseconds (default 50ms)
 * @param valleyThresholdFactor valley threshold as a fraction of the median RMS energy (default 0.05)
 * @param minValleyDurationMs minimum valley duration in milliseconds to be considered (default 50ms)
 */
class RmsValleyDetector(
    private val frameDurationMs: Double = 10.0,
    private val smoothingWindowMs: Double = 50.0,
    private val valleyThresholdFactor: Double = 0.05,
    private val minValleyDurationMs: Double = 50.0,
) : ValleyDetector {

    override fun detectValleys(
        reader: WavSampleReader,
        windowStart: Double,
        windowEnd: Double,
    ): List<ValleyInfo> {
        if (windowEnd <= windowStart) return emptyList()

        val (left, right) = reader.readSamples(windowStart, windowEnd)
        if (left.isEmpty()) return emptyList()

        val frameSize = (reader.sampleRate * frameDurationMs / 1000.0).toInt()
        if (frameSize <= 0 || left.size < frameSize) return emptyList()

        // Step 1: Compute RMS energy profile
        val rmsProfile = computeRmsProfile(left, right, frameSize)
        if (rmsProfile.isEmpty()) return emptyList()

        // Step 2: Smooth
        val smoothingFrames = (smoothingWindowMs / frameDurationMs).toInt().coerceAtLeast(1)
        val smoothed = smooth(rmsProfile, smoothingFrames)

        // Step 3: Find valleys
        val median = median(smoothed)
        if (median == 0.0) return emptyList() // entire window is silent

        val threshold = median * valleyThresholdFactor
        val minValleyFrames = (minValleyDurationMs / frameDurationMs).toInt().coerceAtLeast(1)
        val valleys = findValleys(smoothed, threshold, minValleyFrames)

        // Step 4: Convert to ValleyInfo with absolute timestamps
        val frameDurationSec = frameDurationMs / 1000.0
        return valleys.map { valley ->
            ValleyInfo(
                bottomTime = windowStart + valley.bottomFrame * frameDurationSec,
                bottomEnergy = valley.bottomEnergy,
                startTime = windowStart + valley.startFrame * frameDurationSec,
                endTime = windowStart + valley.endFrame * frameDurationSec,
            )
        }
    }

    // ── Internal utility functions (visible to tests) ───────────────

    /**
     * Compute per-frame RMS energy from stereo audio samples.
     *
     * Both channels are combined: for each frame, the RMS is computed over
     * all samples from both channels within that frame.
     *
     * @param left left channel samples
     * @param right right channel samples
     * @param frameSize number of samples per frame (per channel)
     * @return array of RMS values, one per frame
     */
    internal fun computeRmsProfile(left: FloatArray, right: FloatArray, frameSize: Int): DoubleArray {
        require(left.size == right.size) { "Channel sizes must match" }
        if (left.isEmpty() || frameSize <= 0) return doubleArrayOf()

        val numFrames = left.size / frameSize
        if (numFrames == 0) return doubleArrayOf()

        val rms = DoubleArray(numFrames)
        for (f in 0 until numFrames) {
            val start = f * frameSize
            val end = start + frameSize
            var sumSquares = 0.0
            for (i in start until end) {
                val l = left[i].toDouble()
                val r = right[i].toDouble()
                sumSquares += l * l + r * r
            }
            rms[f] = sqrt(sumSquares / (2.0 * frameSize))
        }
        return rms
    }

    /**
     * Apply a centered moving-average smoothing to the input array.
     *
     * @param values input values
     * @param windowSize number of elements in the averaging window (must be >= 1)
     * @return smoothed array of the same length
     */
    internal fun smooth(values: DoubleArray, windowSize: Int): DoubleArray {
        if (values.isEmpty() || windowSize <= 1) return values.copyOf()

        val result = DoubleArray(values.size)
        val halfWindow = windowSize / 2

        for (i in values.indices) {
            val from = (i - halfWindow).coerceAtLeast(0)
            val to = (i + halfWindow).coerceAtMost(values.size - 1)
            var sum = 0.0
            for (j in from..to) {
                sum += values[j]
            }
            result[i] = sum / (to - from + 1)
        }
        return result
    }

    // ── Private helpers ─────────────────────────────────────────────

    private data class Valley(
        val startFrame: Int,
        val endFrame: Int,       // exclusive
        val bottomFrame: Int,
        val bottomEnergy: Double,
    )

    private fun findValleys(smoothed: DoubleArray, threshold: Double, minFrames: Int): List<Valley> {
        val valleys = mutableListOf<Valley>()
        var i = 0
        while (i < smoothed.size) {
            if (smoothed[i] < threshold) {
                val start = i
                var bottomFrame = i
                var bottomEnergy = smoothed[i]
                while (i < smoothed.size && smoothed[i] < threshold) {
                    if (smoothed[i] < bottomEnergy) {
                        bottomEnergy = smoothed[i]
                        bottomFrame = i
                    }
                    i++
                }
                val end = i
                if (end - start >= minFrames) {
                    valleys.add(Valley(start, end, bottomFrame, bottomEnergy))
                }
            } else {
                i++
            }
        }
        return valleys
    }

    private fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }
}
