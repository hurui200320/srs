package info.skyblond.recorder.spotify.service

import info.skyblond.recorder.spotify.detector.boundary.BoundaryCandidate
import info.skyblond.recorder.spotify.detector.boundary.BoundaryDetector
import info.skyblond.recorder.spotify.detector.boundary.BoundarySource
import info.skyblond.recorder.spotify.tracker.drift.EmaDriftTracker
import info.skyblond.recorder.spotify.wav.WavSampleReader
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransitionDetectorTest {

    companion object {
        private const val MARGIN = 0.03

        private fun mockReader(duration: Double = 100.0): WavSampleReader {
            return mockk<WavSampleReader> {
                every { this@mockk.duration } returns duration
                every { sampleRate } returns 44100
            }
        }

        private fun mockDetector(vararg candidates: BoundaryCandidate): BoundaryDetector {
            return mockk<BoundaryDetector> {
                every { detect(any(), any(), any()) } returns candidates.toList()
            }
        }

        /** Create a clean valley candidate (short duration, suitable for drift calibration). */
        private fun cleanCandidate(timestamp: Double, confidence: Double, durationMs: Double = 300.0): BoundaryCandidate {
            return BoundaryCandidate(timestamp, confidence, BoundarySource.RMS_VALLEY, durationMs)
        }

        /** Create a long valley candidate (not suitable for drift calibration). */
        private fun longCandidate(timestamp: Double, confidence: Double, durationMs: Double = 2000.0): BoundaryCandidate {
            return BoundaryCandidate(timestamp, confidence, BoundarySource.RMS_VALLEY, durationMs)
        }
    }

    // ── Core: cutPoint always based on corrected DBus ────────────────

    @Test
    fun `cutPoint always based on corrected dbus regardless of RMS`() {
        val detector = TransitionDetector(
            detectors = listOf(mockDetector(cleanCandidate(12.0, 0.9))),
            driftTracker = EmaDriftTracker(initialEstimate = 0.0),
            margin = MARGIN,
        )
        val results = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 10.0, spotifyDurationSec = 5.0)),
        )
        assertEquals(1, results.size)
        assertEquals(10.0 - MARGIN, results[0].cutPoint, 0.001)
    }

    @Test
    fun `cutPoint uses drift-corrected dbus offset`() {
        val detector = TransitionDetector(
            detectors = listOf(mockDetector(/* empty */)),
            driftTracker = EmaDriftTracker(initialEstimate = 0.5),
            margin = MARGIN,
        )
        val results = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 10.0, spotifyDurationSec = 5.0)),
        )
        assertEquals(10.5 - MARGIN, results[0].cutPoint, 0.001)
    }

    @Test
    fun `no valley detected gives LOW confidence`() {
        val detector = TransitionDetector(
            detectors = listOf(mockDetector(/* empty */)),
            driftTracker = EmaDriftTracker(),
            margin = MARGIN,
        )
        val results = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 5.0, spotifyDurationSec = 10.0)),
        )
        assertEquals(TransitionConfidence.LOW, results[0].confidence)
        assertTrue(results[0].message.contains("no valley"), "Message: ${results[0].message}")
    }

    @Test
    fun `margin is applied correctly`() {
        val customMargin = 0.05
        val detector = TransitionDetector(
            detectors = listOf(mockDetector(/* empty */)),
            driftTracker = EmaDriftTracker(),
            margin = customMargin,
        )
        val results = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 5.0, spotifyDurationSec = 10.0)),
        )
        assertEquals(5.0 - customMargin, results[0].cutPoint, 0.001)
    }

    // ── Drift calibration from clean valleys ─────────────────────────

    @Test
    fun `clean valley near expected position updates drift`() {
        val driftTracker = EmaDriftTracker(initialEstimate = 0.0, alpha = 0.4)
        val detector = TransitionDetector(
            detectors = listOf(mockDetector(cleanCandidate(5.3, 0.9, durationMs = 300.0))),
            driftTracker = driftTracker,
            margin = MARGIN,
        )
        assertEquals(0.0, driftTracker.estimate, 1e-10)

        detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 5.0, spotifyDurationSec = 10.0)),
        )

        assertTrue(driftTracker.estimate > 0.0, "Drift should have been updated, got ${driftTracker.estimate}")
    }

    @Test
    fun `long valley does not update drift`() {
        val driftTracker = EmaDriftTracker(initialEstimate = 0.0, alpha = 0.4)
        val detector = TransitionDetector(
            detectors = listOf(mockDetector(longCandidate(5.3, 0.9, durationMs = 2000.0))),
            driftTracker = driftTracker,
            margin = MARGIN,
        )

        detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 5.0, spotifyDurationSec = 10.0)),
        )

        assertEquals(0.0, driftTracker.estimate, 1e-10, "Drift should NOT be updated for long valley")
    }

    @Test
    fun `distant valley does not update drift`() {
        val driftTracker = EmaDriftTracker(initialEstimate = 0.0, alpha = 0.4)
        val detector = TransitionDetector(
            detectors = listOf(mockDetector(cleanCandidate(15.0, 0.9))),
            driftTracker = driftTracker,
            margin = MARGIN,
        )

        detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 5.0, spotifyDurationSec = 10.0)),
        )

        assertEquals(0.0, driftTracker.estimate, 1e-10, "Drift should NOT be updated for distant valley")
    }

    @Test
    fun `clean nearby valley gives HIGH confidence`() {
        val detector = TransitionDetector(
            detectors = listOf(mockDetector(cleanCandidate(5.3, 0.9))),
            driftTracker = EmaDriftTracker(),
            margin = MARGIN,
        )
        val results = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 5.0, spotifyDurationSec = 10.0)),
        )
        assertEquals(TransitionConfidence.HIGH, results[0].confidence)
    }

    @Test
    fun `long nearby valley gives MEDIUM confidence`() {
        val detector = TransitionDetector(
            detectors = listOf(mockDetector(longCandidate(5.3, 0.9))),
            driftTracker = EmaDriftTracker(),
            margin = MARGIN,
        )
        val results = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 5.0, spotifyDurationSec = 10.0)),
        )
        assertEquals(TransitionConfidence.MEDIUM, results[0].confidence)
    }

    // ── Multi-track processing ──────────────────────────────────────

    @Test
    fun `multi-track sequential processing returns correct count`() {
        val mockDet = mockk<BoundaryDetector> {
            every { detect(any(), any(), any()) } answers {
                val windowCenter = (secondArg<Double>() + thirdArg<Double>()) / 2.0
                listOf(cleanCandidate(windowCenter, 0.9))
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
        assertEquals(3, results.size)
        for (i in 0 until results.size - 1) {
            assertTrue(results[i].cutPoint < results[i + 1].cutPoint,
                "cutPoints should be increasing: ${results.map { it.cutPoint }}")
        }
    }
}
