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
 * Snapshot of time transformation parameters for lock-free reading.
 * Mirrors the reference TimeElement dataclass from time_sync.py.
 */
private data class TimeElement(
    val lastUpdate: Long = 0,
    val offset: Double = 0.0,
    val drift: Double = 0.0
)

/**
 * Two-dimensional Kalman filter for NTP-style time synchronization.
 *
 * Tracks clock offset and drift between client and server using NTP-style
 * 4-timestamp message exchanges. Both client and server timestamps are
 * monotonic microseconds -- the server uses its own monotonic clock and the
 * client uses System.nanoTime() / 1000.
 *
 * The offset between the two monotonic clocks can be very large (e.g. days
 * worth of microseconds) and this is perfectly normal -- no epoch or unit
 * conversion is needed.
 *
 * Port of the reference Python implementation:
 *   aiosendspin/client/time_sync.py - SendspinTimeFilter
 *
 * State vector: [offset, drift] where:
 *   - offset: timestamp offset server - client (us)
 *   - drift:  rate of change of offset (dimensionless, us/us)
 */
class ClockSync(
    processStdDev: Double = 0.01,
    private val forgetFactor: Double = 1.001,
    private val adaptiveCutoffFraction: Double = 0.75,
    private val minSamplesBeforeForgetting: Int = 100
) {
    private val tag = "ClockSync"

    // Derived process variance (matches reference: self._process_variance = process_std_dev ** 2)
    private val processVariance: Double = processStdDev * processStdDev

    // Kalman filter state -- mirrors reference fields exactly
    private var lastUpdateUs: Long = 0          // _last_update
    private var updateCount: Int = 0            // _count
    private var offset: Double = 0.0            // _offset
    private var drift: Double = 0.0             // _drift

    // Covariance matrix P (2x2, stored as 3 independent entries)
    private var offsetCovariance: Double = Double.POSITIVE_INFINITY      // _offset_covariance
    private var offsetDriftCovariance: Double = 0.0                      // _offset_drift_covariance
    private var driftCovariance: Double = 0.0                            // _drift_covariance

    // Snapshot for lock-free reads by convertServerToClient / convertClientToServer
    @Volatile
    private var currentTimeElement = TimeElement()

    // Diagnostic history
    private var lastMeasurementUs: Double = 0.0
    private var lastMaxErrorUs: Long = 0
    private var lastResidualUs: Double = 0.0
    private var kalmanErrorCount: Long = 0  // Count of large residuals indicating filter anomalies

    fun getKalmanErrorCount(): Long = kalmanErrorCount

    // Network metrics for adaptive sync frequency
    private val networkMetricsLock = Any()
    private val rttHistory = ArrayDeque<Long>(20)
    private val maxErrorHistory = ArrayDeque<Long>(20)
    private var estimatedNetworkJitterUs: Long = 0

    // Frequency recommendation with hysteresis
    private var lastRecommendedFrequencyMs: Long = 0
    private var lastFrequencyChangeTimeMs: Long = 0

    // Time-based stability tracking
    private var convergedAtTimeMs: Long = 0
    private val stabilizationTimeMs = 3000L

    /**
     * Process a server/time response.
     *
     * All four timestamps are raw monotonic microseconds -- no conversion
     * is needed. The NTP offset and delay are computed here and forwarded
     * to the Kalman update.
     *
     * @param clientTransmittedUs  T1 - client clock when request was sent
     * @param clientReceivedUs     T4 - client clock when response arrived
     * @param serverReceivedUs     T2 - server clock when request arrived
     * @param serverTransmittedUs  T3 - server clock when response was sent
     */
    fun onServerTime(
        clientTransmittedUs: Long,
        clientReceivedUs: Long,
        serverReceivedUs: Long,
        serverTransmittedUs: Long
    ) {
        // NTP offset: ((T2 - T1) + (T3 - T4)) / 2
        val measurement = (
            (serverReceivedUs - clientTransmittedUs)
            + (serverTransmittedUs - clientReceivedUs)
        ) / 2

        // Half round-trip (max error): ((T4 - T1) - (T3 - T2)) / 2
        val maxError = (
            (clientReceivedUs - clientTransmittedUs)
            - (serverTransmittedUs - serverReceivedUs)
        ) / 2

        // time_added = T4 (client timestamp when measurement was taken)
        update(measurement, maxError, clientReceivedUs)
    }

    /**
     * Process a new measurement through the Kalman filter.
     *
     * Matches the reference signature:
     *   update(measurement, max_error, time_added)
     */
    private fun update(measurement: Long, maxError: Long, timeAddedUs: Long) {
        if (timeAddedUs == lastUpdateUs) return   // skip duplicate timestamps

        // Track network metrics
        val rtt = 2 * maxError
        synchronized(networkMetricsLock) {
            rttHistory.addLast(rtt)
            if (rttHistory.size > 20) rttHistory.removeFirst()
            maxErrorHistory.addLast(maxError)
            if (maxErrorHistory.size > 20) maxErrorHistory.removeFirst()
            if (rttHistory.size >= 5) {
                val mean = rttHistory.average()
                val variance = rttHistory.map { (it - mean) * (it - mean) }.average()
                estimatedNetworkJitterUs = sqrt(variance).toLong()
            }
        }

        val dt: Double = (timeAddedUs - lastUpdateUs).toDouble()
        lastUpdateUs = timeAddedUs

        val updateStdDev: Double = maxError.toDouble()
        val measurementVariance: Double = updateStdDev * updateStdDev

        // --- First measurement: initialise offset baseline ---
        if (updateCount <= 0) {
            updateCount++
            offset = measurement.toDouble()
            offsetCovariance = measurementVariance
            drift = 0.0
            driftCovariance = 0.0
            offsetDriftCovariance = 0.0

            currentTimeElement = TimeElement(
                lastUpdate = lastUpdateUs, offset = offset, drift = drift
            )

            lastMeasurementUs = measurement.toDouble()
            lastMaxErrorUs = maxError
            return
        }

        // --- Second measurement: initial drift estimate from finite differences ---
        if (updateCount == 1) {
            updateCount++
            drift = (measurement - offset) / dt
            offset = measurement.toDouble()
            driftCovariance = if (dt > 0) (offsetCovariance + measurementVariance) / dt else 0.0
            offsetCovariance = measurementVariance
            offsetDriftCovariance = 0.0

            currentTimeElement = TimeElement(
                lastUpdate = lastUpdateUs, offset = offset, drift = drift
            )

            lastMeasurementUs = measurement.toDouble()
            lastMaxErrorUs = maxError
            Log.i(tag, "Second update: dt=${'$'}{dt.toLong()}us drift=${'$'}drift")
            return
        }

        // --- Standard Kalman predict + correct (count >= 2) ---
        updateCount++

        // == Prediction step ==
        val predictedOffset: Double = offset + drift * dt

        val dtSquared: Double = dt * dt

        // Covariance prediction: P_k|k-1 = F P F^T + Q
        val driftProcessVariance = 0.0   // Drift assumed stable
        val newDriftCovariance: Double = driftCovariance + driftProcessVariance

        val offsetDriftProcessVariance = 0.0
        val newOffsetDriftCovariance: Double =
            offsetDriftCovariance + driftCovariance * dt + offsetDriftProcessVariance

        val offsetProcessVariance: Double = dt * processVariance
        val newOffsetCovariance: Double =
            offsetCovariance +
            2.0 * offsetDriftCovariance * dt +
            driftCovariance * dtSquared +
            offsetProcessVariance

        // == Innovation & adaptive forgetting ==
        val residual: Double = measurement.toDouble() - predictedOffset
        lastResidualUs = residual

        // Track Kalman anomalies: large residuals indicate filter is struggling
        // (residual should be small if filter is well-calibrated and network is stable)
        if (abs(residual) > 50_000.0) {  // > 50ms residual is anomalous
            kalmanErrorCount++
        }

        var covOff = newOffsetCovariance
        var covDrift = newDriftCovariance
        var covOffDrift = newOffsetDriftCovariance

        if (updateCount >= minSamplesBeforeForgetting) {
            val forgetThreshold = adaptiveCutoffFraction * maxError.toDouble()
            if (abs(residual) > forgetThreshold) {
                val f2 = forgetFactor * forgetFactor
                covOff *= f2
                covDrift *= f2
                covOffDrift *= f2
            }
        }

        // == Measurement update ==
        val uncertainty: Double = 1.0 / (covOff + measurementVariance)

        val offsetGain: Double = covOff * uncertainty
        val driftGain: Double = covOffDrift * uncertainty

        // State update
        offset = predictedOffset + offsetGain * residual
        drift += driftGain * residual

        // Covariance update
        driftCovariance = covDrift - driftGain * covOffDrift
        offsetDriftCovariance = covOffDrift - driftGain * covOff
        offsetCovariance = covOff - offsetGain * covOff

        // Publish snapshot for lock-free reads
        currentTimeElement = TimeElement(
            lastUpdate = lastUpdateUs, offset = offset, drift = drift
        )

        lastMeasurementUs = measurement.toDouble()
        lastMaxErrorUs = maxError
    }

    /**
     * Convert a client timestamp to the equivalent server timestamp.
     *
     * T_server = T_client + offset + drift * (T_client - T_last_update)
     */
    fun convertClientToServer(clientTimeUs: Long): Long {
        val te = currentTimeElement
        val dt = (clientTimeUs - te.lastUpdate).toDouble()
        val o = te.offset + te.drift * dt
        return clientTimeUs + o.toLong()
    }

    /**
     * Convert a server timestamp to the equivalent client timestamp.
     *
     * Inverse of convertClientToServer:
     *   T_client = (T_server - offset + drift * T_last_update) / (1 + drift)
     */
    fun convertServerToClient(serverTimeUs: Long): Long {
        val te = currentTimeElement
        val driftFactor = 1.0 + te.drift
        if (driftFactor == 0.0) return (serverTimeUs.toDouble() - te.offset).toLong()
        return (
            (serverTimeUs.toDouble() - te.offset + te.drift * te.lastUpdate) / driftFactor
        ).toLong()
    }

    /** Estimated offset in microseconds (server - client). */
    fun estimatedOffsetUs(): Long = offset.toLong()

    /** Estimated drift (dimensionless). */
    fun estimatedDriftPpm(): Double = drift

    /** Standard deviation of offset estimate in microseconds. */
    fun getOffsetUncertaintyUs(): Long = sqrt(offsetCovariance.coerceAtLeast(0.0)).toLong()

    /** Standard deviation of drift estimate. */
    fun getDriftUncertaintyPpm(): Double = sqrt(driftCovariance.coerceAtLeast(0.0))

    /** True after at least 2 measurements with finite covariance (matches reference is_synchronized). */
    fun hasConverged(): Boolean = updateCount >= 2 && offsetCovariance.isFinite()

    /** Number of Kalman updates processed. */
    fun getUpdateCount(): Int = updateCount

    /** Last innovation / residual from filter update (us). */
    fun getLastResidualUs(): Double = lastResidualUs

    /** Drift signal-to-noise ratio. */
    fun getDriftSnr(): Double {
        if (!hasConverged()) return 0.0
        val std = getDriftUncertaintyPpm()
        return if (std > 0) abs(drift) / std else Double.POSITIVE_INFINITY
    }

    fun getNetworkConditionQuality(): NetworkQuality {
        synchronized(networkMetricsLock) {
            if (maxErrorHistory.isEmpty()) return NetworkQuality.FAIR
            val avgMaxError = maxErrorHistory.average().toLong()
            val maxJitter = if (rttHistory.size >= 2) {
                (rttHistory.maxOrNull() ?: 0L) - (rttHistory.minOrNull() ?: 0L)
            } else 0L
            return when {
                avgMaxError < 20_000 && maxJitter < 20_000 -> NetworkQuality.GOOD
                avgMaxError > 100_000 || maxJitter > 50_000 -> NetworkQuality.POOR
                else -> NetworkQuality.FAIR
            }
        }
    }

    fun getClockStability(): ClockStability {
        if (!hasConverged()) {
            convergedAtTimeMs = 0
            return ClockStability.UNSTABLE
        }
        if (convergedAtTimeMs == 0L) convergedAtTimeMs = System.currentTimeMillis()
        val elapsed = System.currentTimeMillis() - convergedAtTimeMs
        return when {
            elapsed >= stabilizationTimeMs -> ClockStability.STABLE
            elapsed >= 1000L -> ClockStability.CONVERGING
            else -> ClockStability.UNSTABLE
        }
    }

    fun getRecommendedSyncFrequencyMs(): Long {
        var intervalMs: Long
        if (!hasConverged()) {
            intervalMs = 50L
        } else {
            intervalMs = when (getNetworkConditionQuality()) {
                NetworkQuality.POOR -> 150L
                NetworkQuality.GOOD -> 500L
                NetworkQuality.FAIR -> 250L
            }
            intervalMs = when (getClockStability()) {
                ClockStability.UNSTABLE -> (intervalMs * 0.8).toLong()
                ClockStability.CONVERGING -> intervalMs
                ClockStability.STABLE -> (intervalMs * 1.2).toLong()
            }
        }
        intervalMs = intervalMs.coerceIn(25L, 2000L)

        // Hysteresis
        val now = System.currentTimeMillis()
        val prev = lastRecommendedFrequencyMs
        if (prev > 0) {
            val elapsed = now - lastFrequencyChangeTimeMs
            val change = abs(intervalMs.toDouble() - prev.toDouble()) / prev.toDouble()
            if (elapsed < 2000L || change < 0.1) {
                intervalMs = prev
            } else {
                lastFrequencyChangeTimeMs = now
            }
        } else {
            lastFrequencyChangeTimeMs = now
        }
        lastRecommendedFrequencyMs = intervalMs
        return intervalMs
    }

    fun getLastRecommendedFrequencyMs(): Long = lastRecommendedFrequencyMs
    fun getEstimatedNetworkJitterUs(): Long = estimatedNetworkJitterUs

    /** Get the average RTT from recent measurements. Returns 0 if no data. */
    fun getAverageRttUs(): Long {
        synchronized(networkMetricsLock) {
            return if (rttHistory.isEmpty()) 0L else rttHistory.average().toLong()
        }
    }

    fun reset() {
        offset = 0.0
        drift = 0.0
        lastUpdateUs = 0
        updateCount = 0
        offsetCovariance = Double.POSITIVE_INFINITY
        offsetDriftCovariance = 0.0
        driftCovariance = 0.0
        currentTimeElement = TimeElement()
        lastMeasurementUs = 0.0
        lastMaxErrorUs = 0
        lastResidualUs = 0.0
        kalmanErrorCount = 0
        synchronized(networkMetricsLock) {
            rttHistory.clear()
            maxErrorHistory.clear()
        }
        estimatedNetworkJitterUs = 0
        lastRecommendedFrequencyMs = 0
        lastFrequencyChangeTimeMs = 0
        convergedAtTimeMs = 0
    }
}
