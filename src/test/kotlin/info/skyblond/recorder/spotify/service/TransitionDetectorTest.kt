package info.skyblond.recorder.spotify.service

import info.skyblond.recorder.spotify.detector.valley.ValleyDetector
import info.skyblond.recorder.spotify.detector.valley.ValleyInfo
import info.skyblond.recorder.spotify.wav.WavSampleReader
import io.mockk.every
import io.mockk.mockk
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransitionDetectorTest {

    companion object {
        private fun mockReader(duration: Double = 1000.0): WavSampleReader {
            return mockk<WavSampleReader> {
                every { this@mockk.duration } returns duration
                every { sampleRate } returns 44100
            }
        }

        /** Create a valley at a given position with minimal duration. */
        private fun valley(bottomTime: Double, bottomEnergy: Double = 0.001): ValleyInfo {
            return ValleyInfo(
                bottomTime = bottomTime,
                bottomEnergy = bottomEnergy,
                startTime = bottomTime - 0.15,
                endTime = bottomTime + 0.15,
            )
        }
    }

    // ── Correct pairing with duration constraint ────────────────────

    @Test
    fun `correct valley pair is selected by duration constraint`() {
        // Song: dbus=100, duration=200s
        // Start window has valleys at 99.5 and 101.0
        // End window has valleys at 299.8 and 302.0
        // Correct pair: (99.5, 299.8) → gap=200.3, error=0.3
        // Wrong pair:   (101.0, 302.0) → gap=201.0, error=1.0
        val mockDetector = mockk<ValleyDetector> {
            every { detectValleys(any(), any(), any()) } answers {
                val winStart = secondArg<Double>()
                if (winStart < 100.0) {
                    listOf(valley(99.5), valley(101.0))
                } else {
                    listOf(valley(299.8), valley(302.0))
                }
            }
        }
        val detector = TransitionDetector(valleyDetector = mockDetector)
        val results = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 100.0, spotifyDurationSec = 200.0)),
        )

        val result = results[0]
        assertNotNull(result)
        // Best pair: (99.5, 299.8), v2.end=299.95
        // postBottomTime = min((299.95-299.8)/2, 1.0) = 0.075
        // rawCutPoint = max(99.5, 299.8+0.075-200) = max(99.5, 99.875) = 99.875
        assertEquals(99.875, result.cutPoint, 0.05)
        assertEquals(TransitionConfidence.HIGH, result.confidence) // error 0.3 < 1.0
    }

    @Test
    fun `minimize leading silence with postBottomTime`() {
        // Song: duration=100s. Valleys at 10.0 and 110.5
        // v2.end = 110.65 (valley helper adds ±0.15)
        // postBottomTime = min((110.65-110.5)/2, 1.0) = 0.075
        // rawCutPoint = max(10.0, 110.5+0.075-100) = max(10.0, 10.575) = 10.575
        val mockDetector = mockk<ValleyDetector> {
            every { detectValleys(any(), any(), any()) } answers {
                val winStart = secondArg<Double>()
                if (winStart < 50.0) listOf(valley(10.0))
                else listOf(valley(110.5))
            }
        }
        val detector = TransitionDetector(valleyDetector = mockDetector)
        val results = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 10.0, spotifyDurationSec = 100.0)),
        )

        val result = results[0]
        assertNotNull(result)
        assertEquals(10.575, result.cutPoint, 0.01)
    }

    @Test
    fun `exact duration match gives near-zero leading silence`() {
        // Valleys exactly duration apart: v1=10.0, v2=110.0
        // postBottomTime = 0.075
        // rawCutPoint = max(10.0, 110.0+0.075-100) = max(10.0, 10.075) = 10.075
        val mockDetector = mockk<ValleyDetector> {
            every { detectValleys(any(), any(), any()) } answers {
                val winStart = secondArg<Double>()
                if (winStart < 50.0) listOf(valley(10.0))
                else listOf(valley(110.0))
            }
        }
        val detector = TransitionDetector(valleyDetector = mockDetector)
        val results = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 10.0, spotifyDurationSec = 100.0)),
        )

        val result = results[0]
        assertNotNull(result)
        assertEquals(10.075, result.cutPoint, 0.01)
    }

    // ── Duration constraint filtering ───────────────────────────────

    @Test
    fun `pairs exceeding maxDurationError are rejected`() {
        // Song: duration=100s. Valleys at 10.0 and 115.0 → gap=105, error=5 > default max 3
        val mockDetector = mockk<ValleyDetector> {
            every { detectValleys(any(), any(), any()) } answers {
                val winStart = secondArg<Double>()
                if (winStart < 50.0) listOf(valley(10.0))
                else listOf(valley(115.0))
            }
        }
        val detector = TransitionDetector(valleyDetector = mockDetector)
        val results = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 10.0, spotifyDurationSec = 100.0)),
        )

        assertNull(results[0], "Should reject pair with duration error > maxDurationError")
    }

    @Test
    fun `song-internal pause is filtered by duration constraint`() {
        // Song: duration=200s, dbus=100
        // Start window: valley at 100.0 (correct gap)
        // End window: valleys at 297.0 (song internal pause, gap=197, error=3 → borderline)
        //             and 300.0 (correct gap, gap=200, error=0)
        // Should pick (100.0, 300.0) not (100.0, 297.0)
        val mockDetector = mockk<ValleyDetector> {
            every { detectValleys(any(), any(), any()) } answers {
                val winStart = secondArg<Double>()
                if (winStart < 150.0) listOf(valley(100.0))
                else listOf(valley(297.0), valley(300.0))
            }
        }
        val detector = TransitionDetector(valleyDetector = mockDetector)
        val results = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 100.0, spotifyDurationSec = 200.0)),
        )

        val result = results[0]
        assertNotNull(result)
        // Should pick (100.0, 300.0) with error=0
        assertEquals(100.0, result.cutPoint, 0.1) // padding ≈ 0
        assertEquals(TransitionConfidence.HIGH, result.confidence)
    }

    // ── Rejection (no fallback) ─────────────────────────────────────

    @Test
    fun `no valleys in either window returns null`() {
        val mockDetector = mockk<ValleyDetector> {
            every { detectValleys(any(), any(), any()) } returns emptyList()
        }
        val detector = TransitionDetector(valleyDetector = mockDetector)
        val results = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 10.0, spotifyDurationSec = 100.0)),
        )

        assertNull(results[0], "Should return null when no valleys found (possibly gapless)")
    }

    @Test
    fun `valleys exist but no valid pair returns null`() {
        // Start valley at 10.0, end valley at 150.0 → gap=140, error=40 >> maxDurationError
        val mockDetector = mockk<ValleyDetector> {
            every { detectValleys(any(), any(), any()) } answers {
                val winStart = secondArg<Double>()
                if (winStart < 50.0) listOf(valley(10.0))
                else listOf(valley(150.0))
            }
        }
        val detector = TransitionDetector(valleyDetector = mockDetector)
        val results = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 10.0, spotifyDurationSec = 100.0)),
        )

        assertNull(results[0], "Should return null when no pair matches duration")
    }

    // ── Confidence levels ───────────────────────────────────────────

    @Test
    fun `duration error below 1s gives HIGH confidence`() {
        val mockDetector = mockk<ValleyDetector> {
            every { detectValleys(any(), any(), any()) } answers {
                val winStart = secondArg<Double>()
                if (winStart < 50.0) listOf(valley(10.0))
                else listOf(valley(110.5)) // error = 0.5 < 1.0
            }
        }
        val detector = TransitionDetector(valleyDetector = mockDetector)
        val results = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 10.0, spotifyDurationSec = 100.0)),
        )

        assertEquals(TransitionConfidence.HIGH, results[0]?.confidence)
    }

    @Test
    fun `duration error between 1s and maxDurationError gives MEDIUM confidence`() {
        val mockDetector = mockk<ValleyDetector> {
            every { detectValleys(any(), any(), any()) } answers {
                val winStart = secondArg<Double>()
                if (winStart < 50.0) listOf(valley(10.0))
                else listOf(valley(112.0)) // error = 2.0, between 1.0 and 3.0
            }
        }
        val detector = TransitionDetector(valleyDetector = mockDetector)
        val results = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 10.0, spotifyDurationSec = 100.0)),
        )

        assertEquals(TransitionConfidence.MEDIUM, results[0]?.confidence)
    }

    // ── Multi-track independence ─────────────────────────────────────

    @Test
    fun `each track is processed independently`() {
        // Return valleys at the dbus-estimated positions (near start and end of each track)
        val mockDetector = mockk<ValleyDetector> {
            every { detectValleys(any(), any(), any()) } answers {
                val winStart = secondArg<Double>()
                val winEnd = thirdArg<Double>()
                val mid = (winStart + winEnd) / 2.0
                // Return valley near the expected position (window center ≈ estimated start or end)
                listOf(valley(mid, 0.001))
            }
        }
        val detector = TransitionDetector(valleyDetector = mockDetector, searchWindow = 5.0)
        val results = detector.refineStartTimes(
            reader = mockReader(duration = 500.0),
            tracks = listOf(
                TrackInfo(dbusOffset = 10.0, spotifyDurationSec = 100.0),
                TrackInfo(dbusOffset = 120.0, spotifyDurationSec = 100.0),
                TrackInfo(dbusOffset = 230.0, spotifyDurationSec = 100.0),
            ),
        )

        assertEquals(3, results.size)
        results.forEachIndexed { i, r ->
            assertNotNull(r, "Track $i should have a result")
        }
        val cutPoints = results.mapNotNull { it?.cutPoint }
        for (i in 0 until cutPoints.size - 1) {
            assertTrue(cutPoints[i] < cutPoints[i + 1],
                "cutPoints should be increasing: $cutPoints")
        }
    }

    @Test
    fun `one track failing does not affect others`() {
        // Track 0 and 2: return valleys at estimated positions → success
        // Track 1: no valleys → null
        val mockDetector = mockk<ValleyDetector> {
            every { detectValleys(any(), any(), any()) } answers {
                val winStart = secondArg<Double>()
                val winEnd = thirdArg<Double>()
                val mid = (winStart + winEnd) / 2.0
                // Track 1 search windows center around 100±5 and 150±5 → mid in 95..155
                if (mid in 90.0..160.0) emptyList()
                else listOf(valley(mid, 0.001))
            }
        }
        val detector = TransitionDetector(valleyDetector = mockDetector, searchWindow = 5.0)
        val results = detector.refineStartTimes(
            reader = mockReader(duration = 500.0),
            tracks = listOf(
                TrackInfo(dbusOffset = 10.0, spotifyDurationSec = 50.0),
                TrackInfo(dbusOffset = 100.0, spotifyDurationSec = 50.0),
                TrackInfo(dbusOffset = 250.0, spotifyDurationSec = 50.0),
            ),
        )

        assertEquals(3, results.size)
        assertNotNull(results[0], "Track 0 should succeed")
        assertNull(results[1], "Track 1 should fail (no valleys)")
        assertNotNull(results[2], "Track 2 should succeed independently")
    }

    // ── Margin (user manual adjustment) ─────────────────────────────

    @Test
    fun `margin shifts cut point earlier`() {
        val mockDetector = mockk<ValleyDetector> {
            every { detectValleys(any(), any(), any()) } answers {
                val winStart = secondArg<Double>()
                if (winStart < 50.0) listOf(valley(10.0))
                else listOf(valley(110.0))
            }
        }
        // Without margin: rawCutPoint = max(10.0, 110.075-100) = 10.075
        val detectorNoMargin = TransitionDetector(valleyDetector = mockDetector)
        val resultNoMargin = detectorNoMargin.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 10.0, spotifyDurationSec = 100.0)),
        )[0]

        // With margin=0.5: cutPoint = rawCutPoint - 0.5
        val detectorWithMargin = TransitionDetector(valleyDetector = mockDetector, margin = 0.5)
        val resultWithMargin = detectorWithMargin.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 10.0, spotifyDurationSec = 100.0)),
        )[0]

        assertNotNull(resultNoMargin)
        assertNotNull(resultWithMargin)
        assertEquals(resultNoMargin.cutPoint - 0.5, resultWithMargin.cutPoint, 0.001,
            "Margin should shift cutPoint earlier by exactly 0.5s")
    }

    @Test
    fun `default margin is zero`() {
        val mockDetector = mockk<ValleyDetector> {
            every { detectValleys(any(), any(), any()) } answers {
                val winStart = secondArg<Double>()
                if (winStart < 50.0) listOf(valley(10.0))
                else listOf(valley(110.0))
            }
        }
        // Default margin = 0.0, rawCutPoint should equal cutPoint
        val detector = TransitionDetector(valleyDetector = mockDetector)
        val result = detector.refineStartTimes(
            reader = mockReader(),
            tracks = listOf(TrackInfo(dbusOffset = 10.0, spotifyDurationSec = 100.0)),
        )[0]

        assertNotNull(result)
        // rawCutPoint = max(10.0, 110.075-100) = 10.075. With margin=0, cutPoint = 10.075
        assertEquals(10.075, result.cutPoint, 0.01)
    }
}
