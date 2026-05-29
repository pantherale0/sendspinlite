package com.sendspinlite.playback

/**
 * Playback recovery / pipeline status strings used in UI, logs, and audio issue reports.
 * Keep values stable — they are compared in reports and may appear in Sentry extras.
 */
object PlaybackDiagnostics {
    const val CLOCK_CONVERGED_MIN_UPDATES = 15
    const val PRESTART_BACKLOG_CHUNK_THRESHOLD = 60
    const val PRESTART_LATE_HEAD_MS = -30L
    const val HIGH_LATE_DROP_COUNT = 100L
    const val RECENT_AUDIO_CUT_MAX_AGE_MS = 10_000L
    const val AUDIO_CUT_SERVER_LATE_MS = 150L
    const val UI_SERVER_LATENESS_WARN_MS = 80L
    const val MAX_RECOVERY_EVENT_CHARS = 220

    const val STATUS_IDLE = "idle"
    const val STATUS_PLAYING = "playing"
    const val STATUS_WAITING_CLOCK = "waiting_clock_sync"
    const val STATUS_PRESTART_CATCHUP = "prestart_catchup"
    const val STATUS_PRESTART_BACKLOG_TRIM = "prestart_backlog_trim"
    const val STATUS_FORCE_RESYNC_PRESTART = "force_resync_prestart"
    const val STATUS_START_BACKOFF = "start_backoff"
    const val STATUS_LATE_START_RECOVERY = "late_start_recovery"
    const val STATUS_FORCE_RESYNC_RUNTIME = "force_resync_runtime"
    const val STATUS_CATCHUP_DROP = "catchup_drop"
    const val STATUS_AUDIO_CUT = "audio_cut"
    const val STATUS_DISCONTINUITY = "discontinuity_recovery"
    const val STATUS_UNDERRUN = "buffer_underrun"
}
