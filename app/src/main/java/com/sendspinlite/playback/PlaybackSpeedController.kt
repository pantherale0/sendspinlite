package com.sendspinlite.playback


class PlaybackSpeedController(
    private val output: PcmAudioOutput,
    private val jitter: AudioJitterBuffer
) {
    private var currentSpeed = 1.0f
    private var emaBufferAheadMs = Double.NaN
    private var lastSuccessfulSpeedUs = 0L

    // Startup boost: wider speed bounds for the first few seconds after stream start
    private var outputStartedAtUs: Long = 0L
    private val startupBoostDurationUs = 4_000_000L // 4 seconds

    // Hard correction threshold: if buffer error exceeds this during startup, request a hard correction
    private val hardCorrectionThresholdMs = 80.0

    /**
     * Notify the controller that audio output has (re-)started.
     * Resets EMA and enables the startup speed boost window.
     */
    fun notifyOutputStarted(nowUs: Long) {
        outputStartedAtUs = nowUs
        emaBufferAheadMs = Double.NaN
        lastSuccessfulSpeedUs = 0L
    }

    /**
     * Returns true if the controller determines that a hard correction (sample skip/drop)
     * is needed instead of gradual speed adjustment.
     * The caller is responsible for performing the actual correction.
     */
    fun adjustSpeed(nowUs: Long): Boolean {
        if (output.isStarted()) {
            if (outputStartedAtUs == 0L) return false
            val snapshot = jitter.snapshot()
            val rawAheadMs = snapshot.bufferAheadMs.toDouble()

            // EMA smoothing: α=0.3 → ~3-sample window; filters single-tick noise
            emaBufferAheadMs =
                if (emaBufferAheadMs.isNaN()) {
                    rawAheadMs
                } else {
                    0.3 * rawAheadMs + 0.7 * emaBufferAheadMs
                }

            // Target buffer-ahead = AudioTrack pipeline latency.
            val targetAheadMs = output.getEstimatedPipelineLatencyUs() / 1000.0
            val bufferErrorMs = emaBufferAheadMs - targetAheadMs

            // Determine if we're in the startup boost window
            val inStartupPhase = outputStartedAtUs > 0L &&
                (nowUs - outputStartedAtUs) < startupBoostDurationUs

            // During startup, if the buffer error exceeds the hard correction threshold,
            // signal the caller to perform an instant correction instead of gradual speed bend
            if (inStartupPhase && kotlin.math.abs(bufferErrorMs) > hardCorrectionThresholdMs) {
                return true // Request hard correction
            }

            // Proportional control with phase-dependent tuning
            val kP: Double
            val speedMin: Double
            val speedMax: Double
            val deadbandMs: Double
            val rateLimitUs: Long

            if (inStartupPhase) {
                // Startup phase: aggressive correction to lock in quickly
                // ±1.5% speed bounds, tighter deadband, faster rate limit
                kP = 0.0003
                speedMin = 0.985
                speedMax = 1.015
                deadbandMs = 4.0
                rateLimitUs = 200_000L // Allow adjustments every 200ms during startup
            } else {
                // Steady-state: gentle correction for pristine audio quality
                // ±0.2% speed bounds, wide deadband, slow rate limit
                kP = 0.00005
                speedMin = 0.998
                speedMax = 1.002
                deadbandMs = 12.0
                rateLimitUs = 1_000_000L // Once per second
            }

            // Positive error => too far ahead => slow down (<1.0)
            // Negative error => behind => speed up (>1.0)
            var desiredSpeed = (1.0 - (kP * bufferErrorMs)).coerceIn(speedMin, speedMax)

            // Deadband prevents audible hunt around target.
            if (kotlin.math.abs(bufferErrorMs) < deadbandMs) {
                desiredSpeed = 1.0
            }

            // Quantize to 0.001x to reduce rapid tiny parameter churn.
            desiredSpeed = kotlin.math.round(desiredSpeed * 1000.0) / 1000.0

            // Rate-limited speed adjustments
            val desiredSpeedF = desiredSpeed.toFloat()
            if (kotlin.math.abs(desiredSpeedF - currentSpeed) > 0.0001f) {
                if (nowUs - lastSuccessfulSpeedUs >= rateLimitUs) {
                    output.setPlaybackSpeed(desiredSpeedF)
                    currentSpeed = desiredSpeedF
                    lastSuccessfulSpeedUs = nowUs
                }
            }
        } else {
            // Reset state when output stops so we start fresh on next stream
            reset()
        }
        return false // No hard correction needed
    }

    fun reset() {
        currentSpeed = 1.0f
        emaBufferAheadMs = Double.NaN
        lastSuccessfulSpeedUs = 0L
        outputStartedAtUs = 0L
    }
}
