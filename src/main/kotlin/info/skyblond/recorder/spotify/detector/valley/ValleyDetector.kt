package info.skyblond.recorder.spotify.detector.valley

import info.skyblond.recorder.spotify.wav.WavSampleReader

/**
 * Information about a single RMS energy valley detected in an audio signal.
 *
 * @property bottomTime time of the valley's lowest energy point (seconds, absolute offset in the recording)
 * @property bottomEnergy RMS energy at the valley bottom
 * @property startTime time when energy first drops below the valley threshold (seconds)
 * @property endTime time when energy rises back above the valley threshold (seconds)
 */
data class ValleyInfo(
    val bottomTime: Double,
    val bottomEnergy: Double,
    val startTime: Double,
    val endTime: Double,
)

/**
 * Detects all RMS energy valleys within a given time window of an audio recording.
 *
 * Returns raw valley data rather than scored cut points.
 * The caller (e.g., [info.skyblond.recorder.spotify.service.TransitionDetector]) is responsible
 * for pairing valleys and determining cut points using duration constraints.
 */
interface ValleyDetector {
    /**
     * Detect all energy valleys in the given time window.
     *
     * @param reader WAV file reader providing access to audio samples
     * @param windowStart start of the search window in seconds (inclusive)
     * @param windowEnd end of the search window in seconds (exclusive)
     * @return list of all detected valleys; empty if no valleys found
     */
    fun detectValleys(
        reader: WavSampleReader,
        windowStart: Double,
        windowEnd: Double,
    ): List<ValleyInfo>
}
