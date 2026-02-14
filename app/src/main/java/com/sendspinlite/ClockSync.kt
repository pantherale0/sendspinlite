package com.sendspinlite

import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Network condition quality levels.
 */
enum class NetworkQuality {
    GOOD,       // Low latency, low jitter
    FAIR,       // Moderate latency or jitter
    POOR        // High latency or jitter
}

/**
 * Clock stability states.
 */
enum class ClockStability {
    UNSTABLE,   // High drift uncertainty
    CONVERGING, // Drift uncertainty decreasing
    STABLE      // Drift well-estimated
}

/**
 * Clock Synchronization with Kalman Filter and NTP-Style Time Messages
 *
 * Implements a two-dimensional Kalman filter that tracks clock offset and drift
 * between client and server using NTP-style 4-timestamp message exchange.
 *
 * Specification: Clock Synchronization with Kalman Filters and NTP-Style Time Messages
 *
 * State vector: [offset, drift] where:
 *  - offset: timestamp offset between client and server (µs)
 *  - drift: rate of change of offset (dimensionless, for time conversion)
 *
 * The filter maintains a 2×2 covariance matrix tracking uncertainty in both dimensions,
 * adapts to network conditions using measurement variance, and recovers from disruptions
 * via an adaptive forgetting mechanism.
 */
class ClockSync(
    private val processStdDevUs: Double = 0.01,        // Process noise for offset (µs)
    private val driftProcessStdDevPpm: Double = 0.0,    // Process noise for drift (ppm)
    private val forgetFactor: Double = 1.001,           // Forgetting factor λ for recovery
    private val adaptiveCutoffFraction: Double = 0.75,  // Fraction of max_error to trigger forgetting
    private val minSamplesBeforeForgetting: Int = 100   // Stabilization period
) {
    private val tag = "ClockSync"
    // Kalman filter state
    private var offsetUs: Double = 0.0                   // x[0]
    private var driftPpm: Double = 0.0                   // x[1]
    private var lastUpdateTimeUs: Long = 0              // T_{k-1} for time predictions
    private var updateCount: Int = 0                     // Number of updates received

    // Covariance matrix P (2×2)
    private var covOffsetUs2: Double = Double.POSITIVE_INFINITY     // σ²_offset
    private var covDriftPpm2: Double = Double.POSITIVE_INFINITY     // σ²_drift
    private var covOffsetDriftUsPpm: Double = 0.0                   // σ_offset,drift

    // Measurement history for validation
    private var lastMeasurementUs: Double = 0.0
    private var lastMaxErrorUs: Long = 0
    private var lastResidualUs: Double = 0.0

    // Network metrics for adaptive frequency
    private val networkMetricsLock = Any()  // Synchronization lock for network metrics
    private val rttHistory = ArrayDeque<Long>(20)        // RTT samples (µs)
    private val maxErrorHistory = ArrayDeque<Long>(20)   // Max error samples (µs)
    private var estimatedNetworkJitterUs: Long = 0       // Running estimate of jitter
    private var lastRecommendedFrequencyMs: Long = 0     // Cache frequency recommendation
    
    // Hysteresis for frequency changes to prevent oscillation
    private var lastFrequencyChangeTimeMs: Long = 0      // Timestamp of last frequency change
    
    // Time-based stability tracking (LAN-optimized)
    private var convergedAtTimeMs: Long = 0              // When we first reached convergence
    private val stabilizationTimeMs = 3000L              // 3 seconds of stable measurements = STABLE state (LAN only)

    /**
     * Update the Kalman filter with a new measurement from NTP exchange.
     *
     * @param clientTransmittedUs T1: Client transmission timestamp (client clock, µs)
     * @param clientReceivedUs T4: Client reception timestamp (client clock, µs)
     * @param serverReceivedUs T2: Server reception timestamp (server clock, µs)
     * @param serverTransmittedUs T3: Server transmission timestamp (server clock, µs)
     */
    fun onServerTime(
        clientTransmittedUs: Long,
        clientReceivedUs: Long,
        serverReceivedUs: Long,
        serverTransmittedUs: Long
    ) {
        val t1 = clientTransmittedUs.toDouble()
        val t4 = clientReceivedUs.toDouble()
        val t2 = serverReceivedUs.toDouble()
        val t3 = serverTransmittedUs.toDouble()

        // NTP-style offset and delay calculations (Specification Section 1.2)
        // offset = ((T2 - T1) + (T3 - T4)) / 2
        val measurementUs = ((t2 - t1) + (t3 - t4)) / 2.0

        // delay = (T4 - T1) - (T3 - T2)
        val delayUs = (t4 - t1) - (t3 - t2)

        // max_error = delay / 2
        val maxErrorUs = (delayUs / 2.0).toLong()


        // Perform Kalman update
        kalmanUpdate(measurementUs, maxErrorUs, t4.toLong())
    }

    /**
     * Kalman filter update: prediction and measurement correction steps.
     *
     * @param measurementUs Measured offset from NTP exchange
     * @param maxErrorUs Measurement uncertainty (half the round-trip delay)
     * @param timeAddedUs Client timestamp when measurement was taken
     */
    private fun kalmanUpdate(
        measurementUs: Double,
        maxErrorUs: Long,
        timeAddedUs: Long
    ) {
        // Track network metrics for adaptive frequency
        val rtt = 2 * maxErrorUs  // Rough estimate of RTT from max_error
        synchronized(networkMetricsLock) {
            rttHistory.addLast(rtt)
            if (rttHistory.size > 20) rttHistory.removeFirst()
            
            maxErrorHistory.addLast(maxErrorUs)
            if (maxErrorHistory.size > 20) maxErrorHistory.removeFirst()
            
            // Debug: Log suspicious zero RTT values to diagnose convergence issue
            if (rtt < 1000 && rttHistory.size > 10) {
                Log.w(tag, "DEBUG: RTT collapsed to ${rtt}us (maxError=${maxErrorUs}us) - check timestamp precision or network conditions")
            }
            
            // Update jitter estimate (standard deviation of RTT)
            if (rttHistory.size >= 5) {
                val meanRtt = rttHistory.average()
                val variance = rttHistory.map { (it - meanRtt) * (it - meanRtt) }.average()
                estimatedNetworkJitterUs = sqrt(variance).toLong()
            }
        }
        
        when (updateCount) {
            0 -> initializeFirstUpdate(measurementUs, maxErrorUs, timeAddedUs)
            1 -> initializeSecondUpdate(measurementUs, maxErrorUs, timeAddedUs)
            else -> standardKalmanUpdate(measurementUs, maxErrorUs, timeAddedUs)
        }
        updateCount++
    }

    /**
     * First Kalman update (Specification Section 3.1).
     * Initialize offset from measurement, set drift to 0.
     */
    private fun initializeFirstUpdate(
        measurementUs: Double,
        maxErrorUs: Long,
        timeAddedUs: Long
    ) {
        offsetUs = measurementUs
        driftPpm = 0.0
        lastUpdateTimeUs = timeAddedUs
        lastMeasurementUs = measurementUs
        lastMaxErrorUs = maxErrorUs

        // Initialize covariances
        val measVarianceUs2 = maxErrorUs.toDouble() * maxErrorUs.toDouble()
        covOffsetUs2 = measVarianceUs2
        covDriftPpm2 = Double.POSITIVE_INFINITY  // Unknown drift initially
        covOffsetDriftUsPpm = 0.0
    }

    /**
     * Second Kalman update (Specification Section 3.1).
     * Compute initial drift estimate from finite differences.
     */
    private fun initializeSecondUpdate(
        measurementUs: Double,
        maxErrorUs: Long,
        timeAddedUs: Long
    ) {
        val dt = (timeAddedUs - lastUpdateTimeUs).toDouble()

        // Compute drift from change in offset
        driftPpm = if (dt > 0) {
            (measurementUs - offsetUs) / dt
        } else {
            0.0
        }

        Log.i(tag, "Second Kalman update: dt=${String.format("%.0f", dt)}us offsetChange=${String.format("%.1f", measurementUs - offsetUs)}us initialDriftPpm=$driftPpm")

        // Update offset
        offsetUs = measurementUs
        lastUpdateTimeUs = timeAddedUs
        lastMeasurementUs = measurementUs
        lastMaxErrorUs = maxErrorUs

        // Estimate drift variance: σ²_drift = (σ²_offset_k-1 + σ²_meas_k) / (Δt)²
        val measVarianceUs2 = maxErrorUs.toDouble() * maxErrorUs.toDouble()
        val driftVar = if (dt > 0) {
            (covOffsetUs2 + measVarianceUs2) / (dt * dt)
        } else {
            Double.POSITIVE_INFINITY
        }

        covOffsetUs2 = measVarianceUs2
        covDriftPpm2 = driftVar
        covOffsetDriftUsPpm = 0.0
    }

    /**
     * Standard Kalman filter update with prediction and measurement correction.
     * (Specification Section 3.2 and 3.3)
     */
    private fun standardKalmanUpdate(
        measurementUs: Double,
        maxErrorUs: Long,
        timeAddedUs: Long
    ) {
        val dt = (timeAddedUs - lastUpdateTimeUs).toDouble()

        // --- PREDICTION STEP (Section 3.2) ---
        // Predict state: [offset_pred, drift_pred] = F * [offset, drift]
        // where F = [[1, dt], [0, 1]]
        val offsetPredUs = offsetUs + driftPpm * dt
        val driftPredPpm = driftPpm  // Drift assumed constant

        // Predict covariance: P_pred = F * P * F^T + Q
        val q_offset = processStdDevUs * processStdDevUs * dt  // Process noise for offset
        val q_drift = (driftProcessStdDevPpm * driftProcessStdDevPpm) * dt  // Process noise for drift

        val covOffsetPredUs2 = covOffsetUs2 +
                2.0 * covOffsetDriftUsPpm * dt +
                covDriftPpm2 * dt * dt +
                q_offset

        val covDriftPredPpm2 = covDriftPpm2 + q_drift

        val covOffsetDriftPredUsPpm = covOffsetDriftUsPpm + covDriftPpm2 * dt

        // --- MEASUREMENT UPDATE STEP (Section 3.3) ---
        // Innovation: y = z - H * x_pred where H = [1, 0]
        val innovationUs = measurementUs - offsetPredUs
        lastResidualUs = innovationUs

        // Check for adaptive forgetting trigger (Section 3.4)
        var covOffsetUpdateUs2 = covOffsetPredUs2
        var covDriftUpdatePpm2 = covDriftPredPpm2
        var covOffsetDriftUpdateUsPpm = covOffsetDriftPredUsPpm

        if (updateCount >= minSamplesBeforeForgetting) {
            val forgetThreshold = adaptiveCutoffFraction * maxErrorUs
            if (abs(innovationUs) > forgetThreshold) {
                // Apply adaptive forgetting to covariances
                val forgetVarianceFactor = forgetFactor * forgetFactor
                covOffsetUpdateUs2 = forgetVarianceFactor * covOffsetPredUs2
                covDriftUpdatePpm2 = forgetVarianceFactor * covDriftPredPpm2
                covOffsetDriftUpdateUsPpm = forgetVarianceFactor * covOffsetDriftPredUsPpm
            }
        }

        // Innovation covariance: S = H * P_pred * H^T + R
        val measVarianceUs2 = maxErrorUs.toDouble() * maxErrorUs.toDouble()
        val innovationCovarianceUs2 = covOffsetUpdateUs2 + measVarianceUs2

        // Kalman gains: K = P_pred * H^T / S
        val kalmanGainOffset = if (innovationCovarianceUs2 > 0) {
            covOffsetUpdateUs2 / innovationCovarianceUs2
        } else {
            0.0
        }

        val kalmanGainDrift = if (innovationCovarianceUs2 > 0) {
            covOffsetDriftUpdateUsPpm / innovationCovarianceUs2
        } else {
            0.0
        }

        // State update: x = x_pred + K * y
        offsetUs = offsetPredUs + kalmanGainOffset * innovationUs
        driftPpm = driftPredPpm + kalmanGainDrift * innovationUs

        // Covariance update: P = (I - K*H) * P_pred
        covOffsetUs2 = covOffsetUpdateUs2 - kalmanGainOffset * covOffsetUpdateUs2
        covDriftPpm2 = covDriftUpdatePpm2 - kalmanGainDrift * covOffsetDriftUpdateUsPpm
        covOffsetDriftUsPpm = covOffsetDriftUpdateUsPpm - kalmanGainDrift * covOffsetUpdateUs2

        lastUpdateTimeUs = timeAddedUs
        lastMeasurementUs = measurementUs
        lastMaxErrorUs = maxErrorUs
    }

    /**
     * Convert a client-side timestamp to server time.
     *
     * T_server = T_client + offset + drift * (T_client - T_last_update)
     *
     * Drift is only applied if statistically significant (SNR > 2.0).
     *
     * @param clientTimeUs Timestamp in client clock domain
     * @return Equivalent timestamp in server clock domain
     */
    fun convertClientToServer(clientTimeUs: Long): Long {
        val clientTime = clientTimeUs.toDouble()
        var serverTime = clientTime + offsetUs

        // Apply drift compensation only if it's statistically significant
        if (isDriftSignificant()) {
            val timeSinceUpdateUs = clientTime - lastUpdateTimeUs
            serverTime += driftPpm * timeSinceUpdateUs
        }

        return serverTime.toLong()
    }

    /**
     * Convert a server-side timestamp to client time.
     *
     * T_client = (T_server - offset + drift * T_last_update) / (1 + drift)
     *
     * Drift is only applied if statistically significant (SNR > 2.0).
     *
     * @param serverTimeUs Timestamp in server clock domain
     * @return Equivalent timestamp in client clock domain
     */
    fun convertServerToClient(serverTimeUs: Long): Long {
        val serverTime = serverTimeUs.toDouble()
        var clientTime = serverTime - offsetUs

        // Apply drift compensation only if it's statistically significant
        if (isDriftSignificant()) {
            clientTime += driftPpm * lastUpdateTimeUs
            val driftFactor = 1.0 + driftPpm
            if (driftFactor != 0.0) {
                clientTime /= driftFactor
            }
        }

        return clientTime.toLong()
    }

    /**
     * Check if drift estimate is statistically significant.
     * (Specification Section 4.3)
     *
     * Drift is considered significant if |drift| > k * σ_drift, where k ≈ 2.0
     * (corresponding to ~95% confidence).
     *
     * @return true if drift is significant and should be applied, false otherwise
     */
    private fun isDriftSignificant(significanceThreshold: Double = 2.0): Boolean {
        if (covDriftPpm2 < 0 || !covDriftPpm2.isFinite()) {
            return false
        }

        val driftStdDev = sqrt(covDriftPpm2)
        return abs(driftPpm) > significanceThreshold * driftStdDev
    }

    // === Public API for diagnostics and integration ===

    /**
     * Get the estimated offset in microseconds.
     * This is the primary sync parameter.
     */
    fun estimatedOffsetUs(): Long = offsetUs.toLong()

    /**
     * Get the estimated drift in parts per million.
     * Note: Use convertClientToServer/convertServerToClient instead of applying this manually.
     */
    fun estimatedDriftPpm(): Double = driftPpm

    /**
     * Get the uncertainty (variance) of the offset estimate.
     * Useful for monitoring sync quality.
     */
    fun getOffsetUncertaintyUs(): Long = sqrt(covOffsetUs2).toLong()

    /**
     * Get the uncertainty (standard deviation) of the drift estimate.
     * Returns 0 if drift is not yet estimated.
     */
    fun getDriftUncertaintyPpm(): Double = sqrt(covDriftPpm2.coerceAtLeast(0.0))

    /**
     * Check if the filter has converged (received enough measurements).
     */
    fun hasConverged(): Boolean = updateCount >= 2

    /**
     * Get the last measured residual (innovation) from the filter update.
     * Useful for diagnostics.
     */
    fun getLastResidualUs(): Double = lastResidualUs

    // === Adaptive Frequency API ===

    /**
     * Assess network condition quality based on recent measurements.
     *
     * @return NetworkQuality enum (GOOD, FAIR, POOR)
     */
    fun getNetworkConditionQuality(): NetworkQuality {
        synchronized(networkMetricsLock) {
            if (maxErrorHistory.isEmpty()) {
                return NetworkQuality.FAIR  // Unknown, assume fair
            }

            val avgMaxErrorUs = maxErrorHistory.average().toLong()
            val maxJitterUs = if (rttHistory.size >= 2) {
                rttHistory.maxOrNull()?.minus(rttHistory.minOrNull() ?: 0L) ?: 0L
            } else {
                0L
            }

            return when {
                avgMaxErrorUs < 20_000 && maxJitterUs < 20_000 -> NetworkQuality.GOOD
                avgMaxErrorUs > 100_000 || maxJitterUs > 50_000 -> NetworkQuality.POOR
                else -> NetworkQuality.FAIR
            }
        }
    }

    /**
     * Assess clock stability based on time-based convergence (LAN-optimized).
     *
     * For LAN-only scenarios:
     * - Synchronized clocks mean drift is typically unmeasurable
     * - Time-based stability is more practical than SNR-based
     * - 3 seconds of convergence ≈ 10-15 NTP updates = STABLE
     *
     * @return ClockStability enum (UNSTABLE, CONVERGING, STABLE)
     */
    fun getClockStability(): ClockStability {
        if (!hasConverged()) {
            convergedAtTimeMs = 0  // Reset convergence timer when not converged
            return ClockStability.UNSTABLE
        }

        // Track when we first converged
        if (convergedAtTimeMs == 0L) {
            convergedAtTimeMs = System.currentTimeMillis()
        }

        val driftStdDev = getDriftUncertaintyPpm()
        val absDrift = abs(driftPpm)

        // Signal-to-noise ratio: drift / uncertainty
        val snr = if (driftStdDev > 0) {
            absDrift / driftStdDev
        } else {
            Double.POSITIVE_INFINITY
        }

        val timeSinceConvergenceMs = System.currentTimeMillis() - convergedAtTimeMs

        return when {
            // Time-based: LAN reaches STABLE after 3 seconds of measurements
            // (~10-15 NTP updates at 250ms base frequency)
            timeSinceConvergenceMs >= stabilizationTimeMs -> ClockStability.STABLE
            // Early CONVERGING phase
            timeSinceConvergenceMs >= 1000L -> ClockStability.CONVERGING
            // Fresh convergence
            else -> ClockStability.UNSTABLE
        }
    }

    /**
     * Get recommended client/time sync frequency in milliseconds.
     *
     * Frequency is adapted based on:
     * - Network conditions (latency, jitter)
     * - Clock stability (drift uncertainty)
     * - Convergence state
     *
     * @return Recommended interval in milliseconds, clamped to [50ms, 2000ms]
     */
    fun getRecommendedSyncFrequencyMs(): Long {
        // Base frequency: 250ms
        var intervalMs = 250L

        // --- CONVERGENCE STATE ADJUSTMENT ---
        if (!hasConverged()) {
            // Not converged yet, need frequent samples
            intervalMs = 100L
        } else {
            // --- NETWORK CONDITION ADJUSTMENT ---
            val networkQuality = getNetworkConditionQuality()
            val avgMaxErrorUs = synchronized(networkMetricsLock) {
                maxErrorHistory.average().toLong()
            }

            when (networkQuality) {
                NetworkQuality.POOR -> {
                    // Poor network: increase frequency to overcome jitter
                    // max_error > 100ms, reduce interval by 50%
                    intervalMs = 150L
                }
                NetworkQuality.GOOD -> {
                    // Good network: can relax frequency, reduce overhead
                    // max_error < 20ms, increase interval by 100%
                    intervalMs = 500L
                }
                NetworkQuality.FAIR -> {
                    // Fair network: keep base frequency
                    intervalMs = 250L
                }
            }

            // --- CLOCK STABILITY ADJUSTMENT ---
            val clockStability = getClockStability()

            when (clockStability) {
                ClockStability.UNSTABLE -> {
                    // Uncertain drift: increase frequency by 20%
                    intervalMs = (intervalMs * 0.8).toLong()
                }
                ClockStability.CONVERGING -> {
                    // Moderately confident: keep current interval
                }
                ClockStability.STABLE -> {
                    // Confident drift: can relax by 20%
                    intervalMs = (intervalMs * 1.2).toLong()
                }
            }
        }

        // Clamp to reasonable bounds
        intervalMs = intervalMs.coerceIn(50L, 2000L)

        // --- HYSTERESIS: Prevent oscillation by dampening frequent changes ---
        val currentTimeMs = System.currentTimeMillis()
        val timeSinceLastChangeMs = currentTimeMs - lastFrequencyChangeTimeMs
        val changeThresholdMs = 2000L  // Wait at least 2 seconds before changing frequency again
        val hysteresisPercent = 0.1     // Need 10% difference to trigger change
        
        val currentFrequencyMs = lastRecommendedFrequencyMs
        if (currentFrequencyMs > 0) {
            // Only change if: (1) enough time has passed AND (2) significant change (>10%)
            val changePercent = Math.abs(intervalMs.toDouble() - currentFrequencyMs.toDouble()) / currentFrequencyMs.toDouble()
            
            if (timeSinceLastChangeMs >= changeThresholdMs && changePercent >= hysteresisPercent) {
                // Accept the change
                lastFrequencyChangeTimeMs = currentTimeMs
            } else {
                // Reject the change, use previous frequency
                intervalMs = currentFrequencyMs
            }
        } else {
            // First time, accept the value
            lastFrequencyChangeTimeMs = currentTimeMs
        }

        // Cache the recommendation
        lastRecommendedFrequencyMs = intervalMs

        return intervalMs
    }

    /**
     * Get the last recommended frequency (cached value).
     * Useful for logging without recalculation.
     */
    fun getLastRecommendedFrequencyMs(): Long = lastRecommendedFrequencyMs

    /**
     * Get estimated network jitter in microseconds.
     * Useful for diagnostics and monitoring.
     */
    fun getEstimatedNetworkJitterUs(): Long = estimatedNetworkJitterUs

    /**
     * Get drift SNR for UI display (signal-to-noise ratio of drift estimate).
     * @return SNR value, or 0.0 if not converged
     */
    fun getDriftSnr(): Double {
        if (!hasConverged()) return 0.0
        val driftStdDev = getDriftUncertaintyPpm()
        val absDrift = abs(driftPpm)
        return if (driftStdDev > 0) absDrift / driftStdDev else Double.POSITIVE_INFINITY
    }

    /**
     * Reset the filter to initial state (for debugging or reconnections).
     */
    fun reset() {
        offsetUs = 0.0
        driftPpm = 0.0
        lastUpdateTimeUs = 0
        updateCount = 0
        covOffsetUs2 = Double.POSITIVE_INFINITY
        covDriftPpm2 = Double.POSITIVE_INFINITY
        covOffsetDriftUsPpm = 0.0
        lastMeasurementUs = 0.0
        lastMaxErrorUs = 0
        lastResidualUs = 0.0
        synchronized(networkMetricsLock) {
            rttHistory.clear()
            maxErrorHistory.clear()
        }
        estimatedNetworkJitterUs = 0
        lastRecommendedFrequencyMs = 0
        lastFrequencyChangeTimeMs = 0
        convergedAtTimeMs = 0  // Reset convergence timer
    }
}
