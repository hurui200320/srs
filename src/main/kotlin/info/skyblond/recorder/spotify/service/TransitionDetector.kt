package info.skyblond.recorder.spotify.service

import info.skyblond.recorder.spotify.detector.boundary.BoundaryCandidate
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
 * Orchestrates adaptive track boundary detection by combining multiple signal sources:
 * 1. DBus timestamps corrected by drift tracking
 * 2. Duration chain estimation (previous boundary + track duration + gap)
 * 3. Audio-based boundary detection (e.g., RMS energy valley)
 *
 * Processes tracks sequentially, updating drift and gap estimates as it goes.
 *
 * @param detectors list of boundary detectors to query for candidates
 * @param driftTracker tracks cumulative drift between DBus timestamps and actual audio
 * @param searchBefore base search window before the expected offset (seconds)
 * @param searchAfter base search window after the expected offset (seconds)
 * @param margin conservative margin subtracted from detected onset (seconds)
 * @param initialGapEstimate initial estimate of the inter-track gap (seconds)
 * @param gapSmoothingAlpha EMA alpha for updating gap estimate (0.0 ~ 1.0)
 */
class TransitionDetector(
    private val detectors: List<BoundaryDetector>,
    private val driftTracker: DriftTracker,
    private val searchBefore: Double = 1.5,
    private val searchAfter: Double = 2.5,
    private val margin: Double = 0.03,
    private val initialGapEstimate: Double = 0.3,
    private val gapSmoothingAlpha: Double = 0.2,
) {
    companion object {
        /** Threshold: RMS result vs chain estimate must be within this to be "consistent" */
        private const val CHAIN_CONSISTENCY_THRESHOLD = 0.5 // seconds

        /** Threshold: DBus corrected vs chain must be within this when RMS fails */
        private const val DBUS_CHAIN_CONSISTENCY_THRESHOLD = 0.3 // seconds

        /** Confidence boundary between high and medium */
        private const val HIGH_CONFIDENCE_THRESHOLD = 0.7

        /** Confidence boundary between medium and low */
        private const val MEDIUM_CONFIDENCE_THRESHOLD = 0.4
    }

    /**
     * Refine the start times for all tracks in a recording session.
     *
     * Processes tracks sequentially: each track's result may influence the
     * drift estimate and gap estimate used for subsequent tracks.
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

        val results = mutableListOf<TransitionResult>()
        var previousConfirmedStart: Double? = null
        var previousDuration: Double? = null
        var gapEstimate = initialGapEstimate

        for ((index, track) in tracks.withIndex()) {
            val result = detectSingleTransition(
                reader = reader,
                track = track,
                trackIndex = index,
                previousConfirmedStart = previousConfirmedStart,
                previousDuration = previousDuration,
                gapEstimate = gapEstimate,
            )
            results.add(result.first)

            // Update state for next iteration
            previousConfirmedStart = result.first.cutPoint + margin // recover the onset before margin
            previousDuration = track.spotifyDurationSec

            // Update gap estimate if we got a good detection and have a previous reference
            val updatedGap = result.second
            if (updatedGap != null) {
                gapEstimate = gapSmoothingAlpha * updatedGap + (1.0 - gapSmoothingAlpha) * gapEstimate
            }
        }

        // Post-processing: consistency check
        return addConsistencyWarnings(results, tracks, gapEstimate)
    }

    /**
     * Detect transition for a single track.
     * Returns (TransitionResult, observedGap?) where observedGap is non-null
     * if the gap estimate should be updated.
     */
    private fun detectSingleTransition(
        reader: WavSampleReader,
        track: TrackInfo,
        trackIndex: Int,
        previousConfirmedStart: Double?,
        previousDuration: Double?,
        gapEstimate: Double,
    ): Pair<TransitionResult, Double?> {
        val correctedOffset = track.dbusOffset + driftTracker.estimate

        // Duration chain estimate (not available for first track)
        val chainEstimate: Double? = if (previousConfirmedStart != null && previousDuration != null) {
            previousConfirmedStart + previousDuration + gapEstimate
        } else null

        // Determine search window, scaled by drift uncertainty
        val uncertaintyScale = 1.0 + driftTracker.uncertainty / 5.0
        val windowStart = (correctedOffset - searchBefore * uncertaintyScale).coerceAtLeast(0.0)
        val windowEnd = (correctedOffset + searchAfter * uncertaintyScale).coerceAtMost(reader.duration)

        if (windowEnd <= windowStart) {
            return makeResult(correctedOffset, TransitionConfidence.LOW, "Window collapsed") to null
        }

        // Query all boundary detectors
        val candidates = detectors.flatMap { it.detect(reader, windowStart, windowEnd) }
            .sortedByDescending { it.confidence }

        // Fusion decision
        return fuse(
            candidates = candidates,
            correctedOffset = correctedOffset,
            chainEstimate = chainEstimate,
            previousConfirmedStart = previousConfirmedStart,
            previousDuration = previousDuration,
            trackIndex = trackIndex,
        )
    }

    /**
     * Core fusion logic implementing the priority-based decision from design doc section 3.5.
     */
    private fun fuse(
        candidates: List<BoundaryCandidate>,
        correctedOffset: Double,
        chainEstimate: Double?,
        previousConfirmedStart: Double?,
        previousDuration: Double?,
        trackIndex: Int,
    ): Pair<TransitionResult, Double?> {
        val bestCandidate = candidates.firstOrNull()

        // Case: first track (no chain available)
        if (chainEstimate == null) {
            return if (bestCandidate != null) {
                val onset = bestCandidate.timestamp
                val confidence = classifyConfidence(bestCandidate.confidence)
                if (confidence != TransitionConfidence.LOW) {
                    updateDrift(onset, correctedOffset, bestCandidate.confidence)
                }
                makeResult(onset, confidence, "Track $trackIndex: first track, used ${bestCandidate.source} (${bestCandidate.confidence})") to null
            } else {
                makeResult(correctedOffset, TransitionConfidence.LOW, "Track $trackIndex: first track, RMS failed, used corrected DBus") to null
            }
        }

        // Case 1: High confidence detection
        if (bestCandidate != null && bestCandidate.confidence >= HIGH_CONFIDENCE_THRESHOLD) {
            val onset = bestCandidate.timestamp
            updateDrift(onset, correctedOffset, bestCandidate.confidence)
            val observedGap = computeObservedGap(onset, previousConfirmedStart, previousDuration)
            return makeResult(onset, TransitionConfidence.HIGH, "Track $trackIndex: high confidence ${bestCandidate.source} (${bestCandidate.confidence})") to observedGap
        }

        // Case 2: Medium confidence detection
        if (bestCandidate != null && bestCandidate.confidence >= MEDIUM_CONFIDENCE_THRESHOLD) {
            val onset = bestCandidate.timestamp
            val chainDiff = abs(onset - chainEstimate)

            return if (chainDiff < CHAIN_CONSISTENCY_THRESHOLD) {
                // Consistent with chain → trust RMS
                updateDrift(onset, correctedOffset, bestCandidate.confidence)
                val observedGap = computeObservedGap(onset, previousConfirmedStart, previousDuration)
                makeResult(onset, TransitionConfidence.MEDIUM, "Track $trackIndex: medium confidence ${bestCandidate.source}, consistent with chain (diff=${chainDiff}s)") to observedGap
            } else {
                // Inconsistent → fallback to average of corrected DBus and chain
                val fallback = (correctedOffset + chainEstimate) / 2.0
                makeResult(fallback, TransitionConfidence.LOW, "Track $trackIndex: medium confidence ${bestCandidate.source} but inconsistent with chain (diff=${chainDiff}s), used dbus+chain average") to null
            }
        }

        // Case 3: Detection failed (no candidate or low confidence)
        val dbusChainDiff = abs(correctedOffset - chainEstimate)
        return if (dbusChainDiff < DBUS_CHAIN_CONSISTENCY_THRESHOLD) {
            val avg = (correctedOffset + chainEstimate) / 2.0
            makeResult(avg, TransitionConfidence.LOW, "Track $trackIndex: RMS failed, dbus and chain agree (diff=${dbusChainDiff}s), used average") to null
        } else {
            makeResult(chainEstimate, TransitionConfidence.LOW, "Track $trackIndex: RMS failed, dbus and chain disagree (diff=${dbusChainDiff}s), used chain estimate") to null
        }
    }

    private fun makeResult(onset: Double, confidence: TransitionConfidence, message: String): TransitionResult {
        return TransitionResult(
            cutPoint = onset - margin,
            confidence = confidence,
            message = message,
        )
    }

    private fun classifyConfidence(confidence: Double): TransitionConfidence {
        return when {
            confidence >= HIGH_CONFIDENCE_THRESHOLD -> TransitionConfidence.HIGH
            confidence >= MEDIUM_CONFIDENCE_THRESHOLD -> TransitionConfidence.MEDIUM
            else -> TransitionConfidence.LOW
        }
    }

    private fun updateDrift(detectedOnset: Double, correctedOffset: Double, confidence: Double) {
        val observedDrift = detectedOnset - (correctedOffset - driftTracker.estimate)
        driftTracker.update(observedDrift, confidence)
    }

    private fun computeObservedGap(
        detectedOnset: Double,
        previousConfirmedStart: Double?,
        previousDuration: Double?,
    ): Double? {
        if (previousConfirmedStart == null || previousDuration == null) return null
        return detectedOnset - (previousConfirmedStart + previousDuration)
    }

    private fun addConsistencyWarnings(
        results: MutableList<TransitionResult>,
        tracks: List<TrackInfo>,
        gapEstimate: Double,
    ): List<TransitionResult> {
        val tolerance = 2.0 // seconds
        return results.mapIndexed { index, result ->
            if (index < results.size - 1) {
                val effectiveDuration = results[index + 1].cutPoint - result.cutPoint
                val expectedDuration = tracks[index].spotifyDurationSec + gapEstimate + margin // account for margin on both ends
                val deviation = abs(effectiveDuration - expectedDuration)
                if (deviation > tolerance) {
                    result.copy(message = result.message + " [WARNING: duration deviation ${String.format("%.1f", deviation)}s]")
                } else result
            } else result
        }
    }
}
