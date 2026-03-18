package info.skyblond.recorder.spotify.detector.boundary

import info.skyblond.recorder.spotify.wav.WavSampleReader

/**
 * Identifies the type of boundary detection algorithm that produced a candidate.
 * Used by the fusion logic to weight candidates from different sources.
 */
enum class BoundarySource {
    /** Detected via RMS energy valley analysis. */
    RMS_VALLEY,
}

/**
 * A candidate boundary point detected by a [BoundaryDetector].
 *
 * @property timestamp the detected boundary time in seconds (absolute offset in the recording file)
 * @property confidence confidence score in the range (0.0, 1.0]
 * @property source the detection algorithm that produced this candidate
 * @property featureDurationMs duration of the detected feature in milliseconds.
 *           For RMS valley detection this is the valley duration; used by the fusion logic
 *           to distinguish clean Spotify gaps from extended silence contaminated by song content.
 */
data class BoundaryCandidate(
    val timestamp: Double,
    val confidence: Double,
    val source: BoundarySource,
    val featureDurationMs: Double,
)

/**
 * Detects candidate track boundaries within a given time window of an audio recording.
 *
 * Each implementation analyzes the audio using a different technique (e.g., RMS energy,
 * spectral flux) and independently produces candidate boundary points.
 */
interface BoundaryDetector {
    /**
     * Analyze audio in the given time window and return candidate boundary points.
     *
     * @param reader WAV file reader providing access to audio samples
     * @param windowStart start of the search window in seconds (inclusive)
     * @param windowEnd end of the search window in seconds (exclusive)
     * @return candidate boundary points sorted by confidence descending;
     *         empty list if no boundary was detected
     */
    fun detect(
        reader: WavSampleReader,
        windowStart: Double,
        windowEnd: Double,
    ): List<BoundaryCandidate>
}
