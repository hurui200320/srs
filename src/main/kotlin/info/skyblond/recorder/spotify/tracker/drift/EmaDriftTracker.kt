package info.skyblond.recorder.spotify.tracker.drift

/**
 * Drift tracker using Exponential Moving Average (EMA).
 *
 * The EMA approach is simple and effective for approximately linear drift patterns.
 * When a more sophisticated model is needed (e.g., non-linear drift or principled
 * uncertainty estimation), this can be replaced with a Kalman filter implementation
 * of the same [DriftTracker] interface.
 *
 * @param initialEstimate initial drift estimate in seconds (e.g., from the `-a` CLI parameter)
 * @param alpha smoothing factor (0.0 ~ 1.0); lower values give more weight to history
 * @param uncertainty fixed uncertainty value in seconds, since EMA has no real uncertainty model
 */
class EmaDriftTracker(
    initialEstimate: Double = 0.0,
    private val alpha: Double = 0.4,
    override val uncertainty: Double = 2.0,
) : DriftTracker {

    override var estimate: Double = initialEstimate
        private set

    override fun update(observedDrift: Double, confidence: Double) {
        val effectiveAlpha = alpha * confidence
        estimate = effectiveAlpha * observedDrift + (1.0 - effectiveAlpha) * estimate
    }
}
