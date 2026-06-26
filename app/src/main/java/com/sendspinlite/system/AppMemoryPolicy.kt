package com.sendspinlite.system

import android.content.Context

/**
 * Central memory budget for kiosk / low-RAM targets (e.g. Echo Show class devices).
 * Prefer querying [isLeanDevice] once per component lifetime rather than hard-coding sizes.
 */
object AppMemoryPolicy {
    /** Native encoded-audio ring buffer when memory is plentiful (~2 MB). */
    const val RING_BUFFER_BYTES_DEFAULT = 2_000_000L

    /** Lean ring buffer (~375 KB) — enough for network jitter without pinning multi-MB RSS. */
    const val RING_BUFFER_BYTES_LEAN = 384_000L

    const val DIAGNOSTICS_INTERVAL_MS_DEFAULT = 250L
    const val DIAGNOSTICS_INTERVAL_MS_LEAN = 1_000L

    const val HEALTH_CHECK_INTERVAL_MS_DEFAULT = 200L
    const val HEALTH_CHECK_INTERVAL_MS_LEAN = 500L

    const val EVENT_BUFFER_CAPACITY_DEFAULT = 64
    const val EVENT_BUFFER_CAPACITY_LEAN = 16

    fun isLeanDevice(context: Context): Boolean =
        SendspinSystemUtils.checkIsLowMemoryDevice(context, "AppMemoryPolicy") ||
            SendspinSystemUtils.isSystemUnderMemoryPressure(context)

    fun nativeRingBufferBytes(lean: Boolean): Long = if (lean) RING_BUFFER_BYTES_LEAN else RING_BUFFER_BYTES_DEFAULT

    fun diagnosticsIntervalMs(lean: Boolean): Long = if (lean) DIAGNOSTICS_INTERVAL_MS_LEAN else DIAGNOSTICS_INTERVAL_MS_DEFAULT

    fun healthCheckIntervalMs(lean: Boolean): Long = if (lean) HEALTH_CHECK_INTERVAL_MS_LEAN else HEALTH_CHECK_INTERVAL_MS_DEFAULT

    fun eventBufferCapacity(lean: Boolean): Int = if (lean) EVENT_BUFFER_CAPACITY_LEAN else EVENT_BUFFER_CAPACITY_DEFAULT
}
