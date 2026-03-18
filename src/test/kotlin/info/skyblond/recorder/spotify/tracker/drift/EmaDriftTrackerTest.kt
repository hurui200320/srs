package info.skyblond.recorder.spotify.tracker.drift

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmaDriftTrackerTest {

    // ── Initial State ───────────────────────────────────────────────

    @Test
    fun `initial estimate matches constructor parameter`() {
        val tracker = EmaDriftTracker(initialEstimate = 1.5)
        assertEquals(1.5, tracker.estimate, 1e-10)
    }

    @Test
    fun `initial estimate defaults to zero`() {
        val tracker = EmaDriftTracker()
        assertEquals(0.0, tracker.estimate, 1e-10)
    }

    @Test
    fun `uncertainty returns fixed value regardless of updates`() {
        val tracker = EmaDriftTracker(uncertainty = 3.0)
        assertEquals(3.0, tracker.uncertainty, 1e-10)

        tracker.update(10.0, 1.0)
        tracker.update(20.0, 0.5)
        assertEquals(3.0, tracker.uncertainty, 1e-10, "Uncertainty should remain constant after updates")
    }

    // ── Update Behavior ─────────────────────────────────────────────

    @Test
    fun `single update with full confidence`() {
        val tracker = EmaDriftTracker(initialEstimate = 0.0, alpha = 0.4)
        tracker.update(2.0, 1.0)

        // effectiveAlpha = 0.4 * 1.0 = 0.4
        // estimate = 0.4 * 2.0 + 0.6 * 0.0 = 0.8
        assertEquals(0.8, tracker.estimate, 1e-10)
    }

    @Test
    fun `single update with zero confidence has no effect`() {
        val tracker = EmaDriftTracker(initialEstimate = 1.0, alpha = 0.4)
        tracker.update(100.0, 0.0)

        // effectiveAlpha = 0.4 * 0.0 = 0.0
        // estimate = 0.0 * 100.0 + 1.0 * 1.0 = 1.0
        assertEquals(1.0, tracker.estimate, 1e-10)
    }

    @Test
    fun `low confidence reduces update weight`() {
        val tracker = EmaDriftTracker(initialEstimate = 0.0, alpha = 0.4)
        tracker.update(2.0, 0.5)

        // effectiveAlpha = 0.4 * 0.5 = 0.2
        // estimate = 0.2 * 2.0 + 0.8 * 0.0 = 0.4
        assertEquals(0.4, tracker.estimate, 1e-10)
    }

    // ── Convergence Behavior ────────────────────────────────────────

    @Test
    fun `repeated updates converge to observed value`() {
        val tracker = EmaDriftTracker(initialEstimate = 0.0, alpha = 0.4)

        repeat(50) {
            tracker.update(5.0, 1.0)
        }

        assertEquals(5.0, tracker.estimate, 0.01, "Should converge to 5.0")
    }

    @Test
    fun `tracks changing drift`() {
        val tracker = EmaDriftTracker(initialEstimate = 0.0, alpha = 0.4)

        // First: converge toward 1.0
        repeat(20) {
            tracker.update(1.0, 1.0)
        }
        assertTrue(tracker.estimate > 0.9, "Should be near 1.0 after first phase, got ${tracker.estimate}")

        // Then: drift changes to 3.0
        repeat(20) {
            tracker.update(3.0, 1.0)
        }
        assertTrue(tracker.estimate > 2.9, "Should track toward 3.0, got ${tracker.estimate}")
    }
}
