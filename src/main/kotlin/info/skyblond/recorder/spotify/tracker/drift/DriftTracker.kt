package info.skyblond.recorder.spotify.tracker.drift

/**
 * Tracks the cumulative drift between DBus timestamps and actual audio playback times.
 *
 * Implementations maintain a running estimate of the current drift and an uncertainty
 * measure. The uncertainty can be used to dynamically adjust the search window size
 * for boundary detection: higher uncertainty → wider search window.
 */
interface DriftTracker {
    /** Current drift estimate in seconds. */
    val estimate: Double

    /**
     * Uncertainty of the current estimate in seconds.
     *
     * Simple implementations (e.g., EMA) may return a constant value.
     * More advanced implementations (e.g., Kalman filter) return a real
     * error covariance that shrinks as more observations are incorporated.
     */
    val uncertainty: Double

    /**
     * Update the drift estimate with a new observation.
     *
     * @param observedDrift the drift measured at the current track boundary (seconds)
     * @param confidence confidence of the observation (0.0 ~ 1.0);
     *                   low-confidence observations have less influence on the estimate
     */
    fun update(observedDrift: Double, confidence: Double)
}
