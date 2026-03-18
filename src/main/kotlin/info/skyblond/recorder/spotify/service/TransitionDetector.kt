package info.skyblond.recorder.spotify.service

import info.skyblond.recorder.spotify.detector.boundary.BoundaryDetector
import info.skyblond.recorder.spotify.tracker.drift.DriftTracker
import info.skyblond.recorder.spotify.wav.WavSampleReader
import kotlin.math.abs

/**
 * Confidence level assigned to each transition detection result.
 */
enum class TransitionConfidence { HIGH, MEDIUM, LOW }

/**
 * Result of detecting a single track transition.
 *
 * @property cutPoint final start time for ffmpeg `-ss` (seconds), with margin applied
 * @property confidence confidence level of this detection
 * @property message diagnostic message describing how the decision was made
 */
data class TransitionResult(
    val cutPoint: Double,
    val confidence: TransitionConfidence,
    val message: String,
)

/**
 * Input information for a single track in the recording session.
 *
 * @property dbusOffset offset in the recording where DBus reported this track started (seconds)
 * @property spotifyDurationSec track duration as reported by Spotify Web API (seconds)
 */
data class TrackInfo(
    val dbusOffset: Double,
    val spotifyDurationSec: Double,
)

/**
 * Orchestrates adaptive track boundary detection using a DBus-primary approach:
 *
 * - **Cut point**: always based on the drift-corrected DBus timestamp, not on audio analysis.
 *   This avoids ambiguity between Spotify inter-track gaps and song-internal silence.
 * - **RMS detection**: used to calibrate drift estimates and to assign confidence.
 *   Only "clean" valleys (short, clearly just a Spotify gap) update the drift tracker.
 *
 * @param detectors list of boundary detectors to query for candidates
 * @param driftTracker tracks cumulative drift between DBus timestamps and actual audio
 * @param searchBefore base search window before the expected offset (seconds)
 * @param searchAfter base search window after the expected offset (seconds)
 * @param margin conservative margin subtracted from corrected DBus offset (seconds)
 * @param cleanValleyMaxDurationMs maximum valley duration (ms) to be considered "clean" for drift calibration
 * @param nearbyThresholdSec maximum distance (s) between detected valley and expected position to be considered "nearby"
 */
class TransitionDetector(
    private val detectors: List<BoundaryDetector>,
    private val driftTracker: DriftTracker,
    private val searchBefore: Double = 1.5,
    private val searchAfter: Double = 2.5,
    private val margin: Double = 0.03,
    private val cleanValleyMaxDurationMs: Double = 1000.0,
    private val nearbyThresholdSec: Double = 2.0,
) {
    /**
     * Refine the start times for all tracks in a recording session.
     *
     * Processes tracks sequentially: clean valley detections calibrate the drift tracker,
     * improving accuracy for subsequent tracks.
     *
     * @param reader WAV file reader for the recording
     * @param tracks list of tracks in playback order with DBus offsets and Spotify durations
     * @return list of [TransitionResult]s, one per track, in the same order
     */
    fun refineStartTimes(
        reader: WavSampleReader,
        tracks: List<TrackInfo>,
    ): List<TransitionResult> {
        if (tracks.isEmpty()) return emptyList()

        return tracks.mapIndexed { index, track ->
            val correctedOffset = track.dbusOffset + driftTracker.estimate

            // Cut point is always based on corrected DBus offset
            val cutPoint = (correctedOffset - margin).coerceAtLeast(0.0)

            // Determine search window, scaled by drift uncertainty
            val uncertaintyScale = 1.0 + driftTracker.uncertainty / 5.0
            val windowStart = (correctedOffset - searchBefore * uncertaintyScale).coerceAtLeast(0.0)
            val windowEnd = (correctedOffset + searchAfter * uncertaintyScale).coerceAtMost(reader.duration)

            // Query all boundary detectors
            val candidates = if (windowEnd > windowStart) {
                detectors.flatMap { it.detect(reader, windowStart, windowEnd) }
                    .sortedByDescending { it.confidence }
            } else emptyList()

            val bestCandidate = candidates.firstOrNull()

            // Determine confidence and update drift from clean detections
            val (confidence, message) = if (bestCandidate != null) {
                val isClean = bestCandidate.featureDurationMs < cleanValleyMaxDurationMs
                val isNearby = abs(bestCandidate.timestamp - correctedOffset) < nearbyThresholdSec

                if (isClean && isNearby) {
                    // Clean valley near expected position → calibrate drift
                    val driftObservation = bestCandidate.timestamp - track.dbusOffset
                    driftTracker.update(driftObservation, bestCandidate.confidence)

                    TransitionConfidence.HIGH to
                            "Track $index: clean valley (${bestCandidate.featureDurationMs.toInt()}ms), drift calibrated"
                } else if (isNearby) {
                    // Long valley near expected position → may include song silence, don't calibrate
                    TransitionConfidence.MEDIUM to
                            "Track $index: long valley (${bestCandidate.featureDurationMs.toInt()}ms), no drift update"
                } else {
                    // Valley far from expected position → unreliable
                    TransitionConfidence.LOW to
                            "Track $index: valley too far from expected position (${String.format("%.2f", abs(bestCandidate.timestamp - correctedOffset))}s)"
                }
            } else {
                TransitionConfidence.LOW to "Track $index: no valley detected"
            }

            TransitionResult(
                cutPoint = cutPoint,
                confidence = confidence,
                message = message,
            )
        }
    }
}
