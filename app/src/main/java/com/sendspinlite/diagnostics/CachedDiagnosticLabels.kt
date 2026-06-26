package com.sendspinlite.diagnostics

import android.content.Context
import com.sendspinlite.system.SendspinSystemUtils

/**
 * Caches stable diagnostic strings so periodic publish loops do not allocate new
 * [String] instances when the underlying value has not changed tier.
 */
internal class CachedDiagnosticLabels(
    private val context: Context,
    private val logTag: String,
) {
    @Volatile
    private var connectionType: String = "UNKNOWN"

    @Volatile
    private var lastConnectionRefreshMs: Long = 0L

    @Volatile
    private var networkQuality: String = "UNKNOWN"

    @Volatile
    private var networkQualityTier: Int = -1

    @Volatile
    private var stability: String = "UNSTABLE"

    @Volatile
    private var stabilityKey: Long = Long.MIN_VALUE

    fun connectionType(nowMs: Long = System.currentTimeMillis()): String {
        if (nowMs - lastConnectionRefreshMs >= CONNECTION_REFRESH_MS) {
            lastConnectionRefreshMs = nowMs
            connectionType = SendspinSystemUtils.getConnectionType(context, logTag)
        }
        return connectionType
    }

    fun invalidateConnectionType() {
        lastConnectionRefreshMs = 0L
    }

    fun networkQuality(offsetUncertaintyUs: Long): String {
        val tier =
            when {
                offsetUncertaintyUs <= 0L -> 0
                offsetUncertaintyUs < 1_000L -> 1
                offsetUncertaintyUs < 5_000L -> 2
                else -> 3
            }
        if (tier != networkQualityTier) {
            networkQualityTier = tier
            networkQuality =
                when (tier) {
                    0 -> "UNKNOWN"
                    1 -> "GOOD"
                    2 -> "FAIR"
                    else -> "POOR"
                }
        }
        return networkQuality
    }

    fun clockStability(
        timeSynced: Boolean,
        clockUpdateCount: Int,
        firstTimeSyncedAtMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        if (!timeSynced || clockUpdateCount == 0 || firstTimeSyncedAtMs == 0L) {
            if (stabilityKey != STABILITY_KEY_UNSYNCED) {
                stabilityKey = STABILITY_KEY_UNSYNCED
                stability = "UNSTABLE"
            }
            return stability
        }

        val elapsedMs = nowMs - firstTimeSyncedAtMs
        val key =
            when {
                elapsedMs >= 5_000L -> STABILITY_KEY_STABLE
                elapsedMs >= 1_000L -> STABILITY_KEY_CONVERGING
                else -> STABILITY_KEY_UNSYNCED
            }
        if (key != stabilityKey) {
            stabilityKey = key
            stability =
                when (key) {
                    STABILITY_KEY_STABLE -> "STABLE"
                    STABILITY_KEY_CONVERGING -> "CONVERGING"
                    else -> "UNSTABLE"
                }
        }
        return stability
    }

    companion object {
        private const val CONNECTION_REFRESH_MS = 10_000L
        private const val STABILITY_KEY_UNSYNCED = 0L
        private const val STABILITY_KEY_CONVERGING = 1L
        private const val STABILITY_KEY_STABLE = 2L
    }
}
