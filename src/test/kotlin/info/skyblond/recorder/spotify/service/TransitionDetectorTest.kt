package info.skyblond.recorder.spotify.service

import info.skyblond.recorder.spotify.detector.boundary.BoundaryCandidate
import info.skyblond.recorder.spotify.detector.boundary.BoundaryDetector
import info.skyblond.recorder.spotify.detector.boundary.BoundarySource
import info.skyblond.recorder.spotify.tracker.drift.EmaDriftTracker
import info.skyblond.recorder.spotify.wav.WavSampleReader
import io.mockk.every
import io.mockk.mockk
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransitionDetectorTest {

    companion object {
        private const val MARGIN = 0.03

        /**
         * Create a mock WavSampleReader with the given duration.
         * The mock only needs to provide [WavSampleReader.duration] and [WavSampleReader.sampleRate];
         * actual audio reading is handled by the (also mocked) BoundaryDetector.
         */
        private fun mockReader(duration: Double = 100.0): WavSampleReader {
            return mockk<WavSampleReader> {
                every { this@mockk.duration } returns duration
                every { sampleRate } returns 44100
            }
        }

        /**
         * Create a mock BoundaryDetector that returns the given candidates
         * for any detect() call.
         */
        private fun mockDetector(vararg candidates: BoundaryCandidate): BoundaryDetector {
            return mockk<BoundaryDetector> {
                every { detect(any(), any(), any()) } returns candidates.toList()
            }
        }

        /** Shorthand to create a BoundaryCandidate. */
        private fun candidate(timestamp: Double, confidence: Double): BoundaryCandidate {
            return BoundaryCandidate(timestamp, confidence, BoundarySource.RMS_VALLEY)
        }
    }

    // ── Fusion Decision Branches ────────────────────────────────────

    @Test
    fun `high confidence RMS detection is used directly`() {
        // Track 0: high confidence near offset 0 to establish baseline
        // Track 1: high confidence at 10.5
        val mockDet = mockk<BoundaryDetector> {
            every { detect(any(), any(), any()) } answers {
                val windowCenter = (secondArg<Double>() + thirdArg<Double>()) / 2.0
                if (windowCenter < 5.0) listOf(candidate(0.05, 0.9))
                else listOf(candidate(10.5, 0.9))
            }
        }
        val detector = TransitionDetector(
            detectors = listOf(mockDet),
            driftTracker = EmaDriftTracker(),
            margin = MARGIN,
        )
        val results = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(
                TrackInfo(dbusOffset = 0.0, spotifyDurationSec = 10.0),
                TrackInfo(dbusOffset = 10.0, spotifyDurationSec = 5.0),
            ),
        )
        assertEquals(2, results.size)
        assertEquals(TransitionConfidence.HIGH, results[1].confidence)
        assertEquals(10.5 - MARGIN, results[1].cutPoint, 0.001)
    }

    @Test
    fun `medium confidence RMS consistent with chain uses RMS`() {
        // Track 0: high confidence near offset 0 to establish a stable baseline
        // Track 1: medium confidence near chain estimate to test consistency check
        // Chain for track 1 ≈ track0_start(~0.05) + 10.0 + 0.3 = 10.35
        // RMS candidate at 10.4 → diff from chain ~0.05 < 0.5 → consistent
        val mockDet = mockk<BoundaryDetector> {
            every { detect(any(), any(), any()) } answers {
                val windowCenter = (secondArg<Double>() + thirdArg<Double>()) / 2.0
                if (windowCenter < 5.0) listOf(candidate(0.05, 0.9))
                else listOf(candidate(10.4, 0.5))
            }
        }
        val detector = TransitionDetector(
            detectors = listOf(mockDet),
            driftTracker = EmaDriftTracker(),
            margin = MARGIN,
        )
        val results = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(
                TrackInfo(dbusOffset = 0.0, spotifyDurationSec = 10.0),
                TrackInfo(dbusOffset = 10.0, spotifyDurationSec = 5.0),
            ),
        )
        assertEquals(TransitionConfidence.MEDIUM, results[1].confidence)
        assertEquals(10.4 - MARGIN, results[1].cutPoint, 0.001)
    }

    @Test
    fun `medium confidence RMS inconsistent with chain falls back to average`() {
        // Track 0: high confidence near offset 0
        // Chain for track 1 ≈ 10.35
        // RMS candidate at 12.0 → diff from chain ~1.65 > 0.5 → inconsistent
        val mockDet = mockk<BoundaryDetector> {
            every { detect(any(), any(), any()) } answers {
                val windowCenter = (secondArg<Double>() + thirdArg<Double>()) / 2.0
                if (windowCenter < 5.0) listOf(candidate(0.05, 0.9))
                else listOf(candidate(12.0, 0.5))
            }
        }
        val detector = TransitionDetector(
            detectors = listOf(mockDet),
            driftTracker = EmaDriftTracker(),
            margin = MARGIN,
        )
        val results = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(
                TrackInfo(dbusOffset = 0.0, spotifyDurationSec = 10.0),
                TrackInfo(dbusOffset = 10.0, spotifyDurationSec = 5.0),
            ),
        )
        assertEquals(TransitionConfidence.LOW, results[1].confidence)
        // Fallback = average of correctedDbus and chain, should be near ~10.2, not 12.0
        assertTrue(results[1].cutPoint < 11.0, "Should not use the distant RMS candidate, got ${results[1].cutPoint}")
    }

    @Test
    fun `RMS failed and dbus chain agree uses average`() {
        // No candidates returned. DBus offset = 10.0, chain ≈ 10.3, diff = 0.3 = threshold
        // With drift=0, correctedOffset=10.0, chain depends on track0's result
        val detector = TransitionDetector(
            detectors = listOf(mockDetector(/* empty */)),
            driftTracker = EmaDriftTracker(),
            margin = MARGIN,
        )
        val results = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(
                TrackInfo(dbusOffset = 0.0, spotifyDurationSec = 10.0),
                TrackInfo(dbusOffset = 10.2, spotifyDurationSec = 5.0),
            ),
        )
        assertEquals(TransitionConfidence.LOW, results[1].confidence)
        assertTrue(results[1].message.contains("agree") || results[1].message.contains("average"),
            "Should indicate dbus+chain agreement: ${results[1].message}")
    }

    @Test
    fun `RMS failed and dbus chain disagree uses chain estimate`() {
        // DBus offset = 12.0 (far from chain ≈ 10.3), diff > 0.3 → disagree
        val detector = TransitionDetector(
            detectors = listOf(mockDetector(/* empty */)),
            driftTracker = EmaDriftTracker(),
            margin = MARGIN,
        )
        val results = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(
                TrackInfo(dbusOffset = 0.0, spotifyDurationSec = 10.0),
                TrackInfo(dbusOffset = 12.0, spotifyDurationSec = 5.0),
            ),
        )
        assertEquals(TransitionConfidence.LOW, results[1].confidence)
        assertTrue(results[1].message.contains("chain"),
            "Should indicate chain estimate was used: ${results[1].message}")
        // cutPoint should be near chain estimate (~10.3), not dbus (12.0)
        assertTrue(results[1].cutPoint < 11.0, "Should prefer chain estimate, got ${results[1].cutPoint}")
    }

    @Test
    fun `first track with RMS success uses RMS`() {
        val detector = TransitionDetector(
            detectors = listOf(mockDetector(candidate(0.5, 0.9))),
            driftTracker = EmaDriftTracker(),
            margin = MARGIN,
        )
        val results = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 0.0, spotifyDurationSec = 10.0)),
        )
        assertEquals(1, results.size)
        assertEquals(0.5 - MARGIN, results[0].cutPoint, 0.001)
        assertTrue(results[0].message.contains("first track"))
    }

    @Test
    fun `first track with RMS failure uses corrected dbus`() {
        val detector = TransitionDetector(
            detectors = listOf(mockDetector(/* empty */)),
            driftTracker = EmaDriftTracker(initialEstimate = 0.1),
            margin = MARGIN,
        )
        val results = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 1.0, spotifyDurationSec = 10.0)),
        )
        assertEquals(1, results.size)
        // correctedOffset = 1.0 + 0.1 = 1.1
        assertEquals(1.1 - MARGIN, results[0].cutPoint, 0.001)
        assertTrue(results[0].message.contains("first track"))
        assertTrue(results[0].message.contains("corrected DBus") || results[0].message.contains("RMS failed"))
    }

    // ── Behavior Verification ───────────────────────────────────────

    @Test
    fun `margin is applied correctly`() {
        val customMargin = 0.05
        val detector = TransitionDetector(
            detectors = listOf(mockDetector(candidate(5.0, 0.9))),
            driftTracker = EmaDriftTracker(),
            margin = customMargin,
        )
        val results = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 5.0, spotifyDurationSec = 10.0)),
        )
        assertEquals(5.0 - customMargin, results[0].cutPoint, 0.001)
    }

    @Test
    fun `drift tracker is updated after high confidence detection`() {
        val driftTracker = EmaDriftTracker(initialEstimate = 0.0, alpha = 0.4)
        val detector = TransitionDetector(
            detectors = listOf(mockDetector(candidate(5.5, 0.9))),
            driftTracker = driftTracker,
            margin = MARGIN,
        )
        assertEquals(0.0, driftTracker.estimate, 1e-10, "Initial drift should be 0")

        detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 5.0, spotifyDurationSec = 10.0)),
        )

        // The detector saw onset at 5.5 but raw dbus was 5.0
        // observedDrift = 5.5 - (5.0 - 0.0) = 0.5
        // After update: drift should have moved toward 0.5
        assertTrue(driftTracker.estimate > 0.0, "Drift should have been updated, got ${driftTracker.estimate}")
    }

    @Test
    fun `multi-track sequential processing returns correct count and chain takes effect`() {
        // 3 tracks, detector always returns candidate near the dbus offset
        val mockDet = mockk<BoundaryDetector> {
            every { detect(any(), any(), any()) } answers {
                val windowStart = secondArg<Double>()
                val windowEnd = thirdArg<Double>()
                val mid = (windowStart + windowEnd) / 2.0
                listOf(candidate(mid, 0.9))
            }
        }

        val detector = TransitionDetector(
            detectors = listOf(mockDet),
            driftTracker = EmaDriftTracker(),
            margin = MARGIN,
        )
        val results = detector.refineStartTimes(
            reader = mockReader(duration = 300.0),
            tracks = listOf(
                TrackInfo(dbusOffset = 0.0, spotifyDurationSec = 90.0),
                TrackInfo(dbusOffset = 90.0, spotifyDurationSec = 90.0),
                TrackInfo(dbusOffset = 180.0, spotifyDurationSec = 90.0),
            ),
        )

        assertEquals(3, results.size, "Should return one result per track")
        // All should have some confidence (not crashing)
        results.forEach { result ->
            assertTrue(result.cutPoint >= -MARGIN, "cutPoint should be reasonable: ${result.cutPoint}")
        }
        // Results should be in increasing order
        for (i in 0 until results.size - 1) {
            assertTrue(results[i].cutPoint < results[i + 1].cutPoint,
                "cutPoints should be increasing: ${results.map { it.cutPoint }}")
        }
    }
}
