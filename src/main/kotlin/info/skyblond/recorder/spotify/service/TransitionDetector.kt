package info.skyblond.recorder.spotify.service

import info.skyblond.recorder.spotify.detector.valley.ValleyDetector
import info.skyblond.recorder.spotify.detector.valley.ValleyInfo
import info.skyblond.recorder.spotify.wav.WavSampleReader
import kotlin.math.abs

/**
 * Confidence level assigned to each transition detection result.
 */
enum class TransitionConfidence { HIGH, MEDIUM }

/**
 * Result of detecting a single track transition.
 *
 * @property cutPoint final start time for ffmpeg `-ss` (seconds)
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
 * Determines track cut points using a two-point valley search with duration constraint.
 *
 * For each track, searches for a pair of RMS energy valleys (one near the expected start,
 * one near the expected end) whose spacing matches the Spotify-reported track duration.
 * The cut point is placed symmetrically between the valley pair, ensuring equal silence
 * padding before and after the track.
 *
 * If no valid valley pair is found, the track is skipped (returns null) rather than
 * falling back to a potentially inaccurate DBus-based cut.
 *
 * @param valleyDetector detects RMS energy valleys in audio windows
 * @param searchWindow search radius in seconds around the expected start/end positions
 * @param margin user-adjustable offset subtracted from the computed cut point (seconds).
 *               Default 0.0 (no adjustment). Increase to shift all cuts earlier when
 *               the automatic detection produces unsatisfactory results for specific tracks.
 * @param maxDurationError maximum allowed difference between valley pair spacing
 *                         and Spotify duration (seconds)
 * @param w1 scoring weight for duration match accuracy (default 10.0)
 * @param w2 scoring weight for proximity to DBus-estimated positions (default 1.0)
 * @param w3 scoring weight for valley depth / energy (default 0.1)
 */
class TransitionDetector(
    private val valleyDetector: ValleyDetector,
    private val searchWindow: Double = 10.0,
    private val margin: Double = 0.0,
    private val maxDurationError: Double = 3.0,
    private val w1: Double = 10.0,
    private val w2: Double = 1.0,
    private val w3: Double = 0.1,
) {
    /**
     * Refine the start times for all tracks in a recording session.
     *
     * Each track is processed independently. Returns null for tracks where
     * no valid valley pair was found (the caller should skip these tracks).
     *
     * @param reader WAV file reader for the recording
     * @param tracks list of tracks in playback order with DBus offsets and Spotify durations
     * @return list of [TransitionResult]? (nullable), one per track, in the same order
     */
    fun refineStartTimes(
        reader: WavSampleReader,
        tracks: List<TrackInfo>,
    ): List<TransitionResult?> {
        return tracks.mapIndexed { index, track ->
            refineOne(reader, track, index)
        }
    }

    private fun refineOne(
        reader: WavSampleReader,
        track: TrackInfo,
        trackIndex: Int,
    ): TransitionResult? {
        val estimatedStart = track.dbusOffset
        val estimatedEnd = track.dbusOffset + track.spotifyDurationSec
        val duration = track.spotifyDurationSec

        // Search windows around estimated start and end
        val startWindowBegin = (estimatedStart - searchWindow).coerceAtLeast(0.0)
        val startWindowEnd = (estimatedStart + searchWindow).coerceAtMost(reader.duration)
        val endWindowBegin = (estimatedEnd - searchWindow).coerceAtLeast(0.0)
        val endWindowEnd = (estimatedEnd + searchWindow).coerceAtMost(reader.duration)

        // Detect valleys in both windows
        val startValleys = if (startWindowEnd > startWindowBegin) {
            valleyDetector.detectValleys(reader, startWindowBegin, startWindowEnd)
        } else emptyList()

        val endValleys = if (endWindowEnd > endWindowBegin) {
            valleyDetector.detectValleys(reader, endWindowBegin, endWindowEnd)
        } else emptyList()

        // Find best valley pair
        val bestPair = findBestPair(
            startValleys, endValleys,
            duration, estimatedStart, estimatedEnd,
        )

        if (bestPair == null) {
            return null // Reject: cannot determine cut point
        }

        val (v1, v2, durationError) = bestPair
        // Minimize leading silence: start as late as possible (at v1.bottom),
        // but ensure the end (cutPoint + D) does not exceed bottom + some duration (postBottomTime)
        // clamp this duration to at most 1s in case of a long silence,
        // otherwise just use the middle point between bottom and end
        val postBottomTime = minOf((v2.endTime - v2.bottomTime) / 2, 1.0)
        val rawCutPoint = maxOf(v1.bottomTime, v2.bottomTime + postBottomTime - duration)
        val cutPoint = rawCutPoint - margin

        val confidence =
            if (durationError < 1.0) TransitionConfidence.HIGH
            else TransitionConfidence.MEDIUM

        return TransitionResult(
            cutPoint = cutPoint,
            confidence = confidence,
            message = "Track $trackIndex: paired valleys (error=${"%.3f".format(durationError)}s)",
        )
    }

    private data class ScoredPair(
        val v1: ValleyInfo,
        val v2: ValleyInfo,
        val durationError: Double,
    )

    private fun findBestPair(
        startValleys: List<ValleyInfo>,
        endValleys: List<ValleyInfo>,
        duration: Double,
        estimatedStart: Double,
        estimatedEnd: Double,
    ): ScoredPair? {
        var bestPair: ScoredPair? = null
        var bestScore = Double.NEGATIVE_INFINITY

        for (v1 in startValleys) {
            for (v2 in endValleys) {
                val gap = v2.bottomTime - v1.bottomTime
                val durationError = abs(gap - duration)

                if (durationError > maxDurationError) continue

                val scoreDuration = -durationError
                val scoreProximity =
                    -(abs(v1.bottomTime - estimatedStart) + abs(v2.bottomTime - estimatedEnd))
                val scoreDepth = -(v1.bottomEnergy + v2.bottomEnergy)

                val score = w1 * scoreDuration + w2 * scoreProximity + w3 * scoreDepth

                if (score > bestScore) {
                    bestScore = score
                    bestPair = ScoredPair(v1, v2, durationError)
                }
            }
        }

        return bestPair
    }
}
