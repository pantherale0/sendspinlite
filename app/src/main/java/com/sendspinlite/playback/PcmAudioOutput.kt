package com.sendspinlite.playback

import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

class PcmAudioOutput {
    private val tag = "PcmAudioOutput"

    data class PcmFormat(
        val sampleRate: Int,
        val channels: Int,
        val bitDepth: Int,
    )

    /**
     * Snapshot of playback progress fed back to the native client via notify_audio_played.
     *
     * @param framesPlayed Frames presented since the previous snapshot (a delta).
     * @param finishTimestampUs Monotonic time (CLOCK_MONOTONIC, microseconds) when those frames
     * exit the DAC. This matches sendspin-cpp's host clock (std::chrono::steady_clock), which on
     * Android is the same domain as System.nanoTime().
     */
    data class PlaybackProgress(
        val framesPlayed: Long,
        val finishTimestampUs: Long,
    )

    companion object {
        /** Safety ceiling for pipeline latency estimation (2 seconds). */
        private const val MAX_PIPELINE_LATENCY_US = 2_000_000L
        private const val SOFT_START_RAMP_MS = 35L

        private const val BUFFER_FLOOR_MS_DEFAULT = 250
        private const val BUFFER_FLOOR_MS_LEAN = 120
    }

    @Volatile
    private var leanMode = false

    fun setLeanMode(lean: Boolean) {
        leanMode = lean
    }

    @Volatile
    private var track: AudioTrack? = null
    private val started = AtomicBoolean(false)

    // Track active writers to prevent releasing native object while in use
    private val writingCount = AtomicInteger(0)

    // Lock for synchronizing start/stop/pause operations
    private val lock = Any()

    private var currentSampleRate = 48000
    private var currentChannels = 2
    private var currentBitDepth = 16

    // Last reported minimum buffer size from AudioTrack HAL (bytes)
    @Volatile
    private var lastMinBufBytes: Int = 0

    // Dynamic latency estimation state (frames)
    private val totalFramesWritten = AtomicLong(0L)
    private var playbackHeadRaw: Long = 0L
    private var playbackHeadWraps: Long = 0L

    // Last frame position reported to the native client, used to emit per-poll deltas.
    private var lastReportedFrames: Long = 0L

    @Volatile
    private var smoothedLatencyUs: Long = 0L

    // Current playback speed
    @Volatile
    private var currentPlaybackSpeed: Float = 1.0f

    @Volatile
    private var softStartBeganAtMs: Long = 0L

    @Volatile
    private var lastAppliedTrackGain: Float = 1.0f

    /**
     * Soft player mute. Applied as AudioTrack gain only — never touches
     * [AudioManager.STREAM_MUSIC], so other apps can keep using the shared media stream.
     */
    @Volatile
    private var playbackMuted: Boolean = false

    /** Independent app software volume (0–1). Composed into AudioTrack gain. */
    @Volatile
    private var appVolumeGain: Float = 1.0f

    /** Temporary duck multiplier (0–1). Composed into AudioTrack gain. */
    @Volatile
    private var duckGain: Float = 1.0f

    fun isStarted(): Boolean = started.get()

    fun isPlaybackMuted(): Boolean = playbackMuted

    /**
     * Mute/unmute this player's AudioTrack only. Does not change system stream volume
     * or request audio focus, so concurrent STREAM_MUSIC users are unaffected.
     */
    fun setPlaybackMuted(muted: Boolean) {
        synchronized(lock) {
            if (playbackMuted == muted) return
            playbackMuted = muted
            val trackRef = track
            if (trackRef != null && trackRef.state == AudioTrack.STATE_INITIALIZED) {
                applyEffectiveGainLocked(trackRef, currentSoftStartGainLocked())
            }
        }
        Log.i(tag, "Playback muted=$muted (AudioTrack gain only; STREAM_MUSIC unchanged)")
    }

    /**
     * Set independent app software volume (0–1). Does not change system STREAM_MUSIC
     * or protocol player volume.
     */
    fun setAppVolumeGain(gain: Float) {
        val clamped = gain.coerceIn(0f, 1.0f)
        synchronized(lock) {
            if (appVolumeGain == clamped) return
            appVolumeGain = clamped
            val trackRef = track
            if (trackRef != null && trackRef.state == AudioTrack.STATE_INITIALIZED) {
                applyEffectiveGainLocked(trackRef, currentSoftStartGainLocked())
            }
        }
    }

    /**
     * Set temporary duck multiplier (0–1). Does not change system STREAM_MUSIC
     * or protocol player volume.
     */
    fun setDuckGain(gain: Float) {
        val clamped = gain.coerceIn(0f, 1.0f)
        synchronized(lock) {
            if (duckGain == clamped) return
            duckGain = clamped
            val trackRef = track
            if (trackRef != null && trackRef.state == AudioTrack.STATE_INITIALIZED) {
                applyEffectiveGainLocked(trackRef, currentSoftStartGainLocked())
            }
        }
    }

    fun start(
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
    ) {
        synchronized(lock) {
            // Check if we can reuse the existing track
            val existingTrack = track
            if (existingTrack != null &&
                currentSampleRate == sampleRate &&
                currentChannels == channels &&
                currentBitDepth == bitDepth &&
                existingTrack.state == AudioTrack.STATE_INITIALIZED
            ) {
                // Just flush and restart playback (Resume)
                try {
                    // Always flush before restarting to clear old data
                    existingTrack.pause()
                    applyTrackGainLocked(existingTrack, 0f)
                    existingTrack.flush()
                    resetPlaybackSpeedLocked(existingTrack)
                    resetLatencyEstimatorLocked()
                    existingTrack.play()
                    beginSoftStartLocked(existingTrack)
                    started.set(true)
                    Log.i(tag, "AudioTrack reused and resumed for new stream")
                    return
                } catch (e: Exception) {
                    Log.w(tag, "Failed to reuse AudioTrack, recreating...", e)
                    // Fall through to full recreate
                }
            }

            // Full recreate needed (format changed or track invalid)
            stop()

            require(bitDepth in listOf(16, 24, 32)) { "Unsupported bit depth: $bitDepth. Must be 16, 24, or 32-bit PCM" }

            val format =
                PcmFormatSupport.buildPlaybackFormat(sampleRate, channels, bitDepth)
                    ?: run {
                        Log.e(tag, "Unsupported PCM format sr=$sampleRate ch=$channels bd=$bitDepth")
                        started.set(false)
                        track = null
                        return
                    }

            val safeSampleRate = format.sampleRate
            val minBuf =
                PcmFormatSupport.getMinBufferSizeIfSupported(sampleRate, channels, bitDepth)
            if (minBuf <= 0) {
                Log.e(tag, "Unsupported PCM format sr=$safeSampleRate ch=$channels bd=$bitDepth")
                started.set(false)
                track = null
                return
            }
            lastMinBufBytes = minBuf
            val bytesPerFrame = channels * (bitDepth / 8)

            // Define minimum baselines (100ms floor for raw minimum buffer, device-specific playback track floor)
            val buffer100ms = (safeSampleRate * 0.10 * bytesPerFrame).toInt()
            val bufferFloorMs = if (leanMode) BUFFER_FLOOR_MS_LEAN else BUFFER_FLOOR_MS_DEFAULT
            val bufferFloorBytes = (safeSampleRate * bufferFloorMs / 1000.0 * bytesPerFrame).toInt()

            // Safeguard minBuf: if HAL returns invalid or tiny value, use a solid 100ms baseline
            val safeMinBuf = maxOf(minBuf, buffer100ms)

            // Calculate minimum buffer duration to detect devices with naturally large buffers.
            // On high-latency devices (e.g. Snapdragon MSM8916, where minBuf is 80ms+), using a 4x multiplier
            // inflates the buffer to 400ms+ and forces the Android OS deep-buffer playback path, causing massive
            // hardware latency. Using a smaller 2x multiplier for these prevents triggering deep-buffer routing.
            val minBufMs =
                if (safeSampleRate > 0 && bytesPerFrame > 0) {
                    (minBuf.toLong() * 1000L) / (safeSampleRate.toLong() * bytesPerFrame)
                } else {
                    0L
                }
            val multiplier =
                if (leanMode) {
                    2
                } else if (minBufMs > 40L) {
                    2
                } else {
                    4
                }

            // Use at least bufferFloorBytes, or multiplier * safeMinBuf, whichever is larger
            val bufferBytes = maxOf(safeMinBuf * multiplier, bufferFloorBytes)

            // USAGE_MEDIA → shared STREAM_MUSIC mixer path. No exclusive/low-latency flags,
            // no AudioFocus request — other apps may play on STREAM_MUSIC concurrently.
            val attrs =
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()

            try {
                val audioTrack =
                    AudioTrack(
                        attrs,
                        format,
                        bufferBytes,
                        AudioTrack.MODE_STREAM,
                        AudioManager.AUDIO_SESSION_ID_GENERATE,
                    )

                if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
                    Log.e(tag, "AudioTrack init failed (state=${audioTrack.state})")
                    try {
                        audioTrack.release()
                    } catch (_: Exception) {
                    }
                    started.set(false)
                    track = null
                    return
                }

                resetPlaybackSpeedLocked(audioTrack)
                applyTrackGainLocked(audioTrack, 0f)
                resetLatencyEstimatorLocked()
                audioTrack.play()
                beginSoftStartLocked(audioTrack)
                track = audioTrack
                started.set(true)

                currentSampleRate = safeSampleRate
                currentChannels = channels
                currentBitDepth = bitDepth

                Log.i(tag, "AudioTrack started sr=$safeSampleRate ch=$channels bd=$bitDepth minBuf=$minBuf bufferBytes=$bufferBytes")
            } catch (e: Exception) {
                Log.e(tag, "Failed to create/start AudioTrack", e)
                started.set(false)
                track = null
            }
        }
    }

    /**
     * Pauses and flushes the audio track but keeps the instance alive for reuse.
     * Use this for seeking or stopping temporarily.
     */
    fun pause() {
        started.set(false)

        // Wait briefly for active writers to exit before touching AudioTrack state.
        var attempts = 30 // up to 300ms
        while (writingCount.get() > 0 && attempts > 0) {
            try {
                Thread.sleep(10)
            } catch (_: Exception) {
            }
            attempts--
        }

        synchronized(lock) {
            val t = track
            if (t != null && t.state == AudioTrack.STATE_INITIALIZED) {
                try {
                    applyTrackGainLocked(t, 0f)
                    resetPlaybackSpeedLocked(t)
                    t.pause()
                    t.flush()
                    clearSoftStartLocked()
                    resetLatencyEstimatorLocked()
                    Log.i(tag, "AudioTrack paused and flushed")
                } catch (e: Exception) {
                    Log.w(tag, "Error pausing/flushing AudioTrack", e)
                    stopInternal(t)
                }
            }
        }
    }

    fun writePcm(pcm: ByteArray): Boolean {
        if (pcm.isEmpty()) return true
        if (!started.get()) return false

        // Signal we are using the track
        writingCount.incrementAndGet()
        try {
            // Double check state after increment
            if (!started.get()) return false

            val t = track ?: return false

            if (t.state == AudioTrack.STATE_UNINITIALIZED) return false
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) return false
            updateSoftStartGain(t)

            var off = 0
            val bytesPerFrame = currentChannels * (currentBitDepth / 8)
            var zeroWriteCount = 0
            val maxZeroWrites = 50 // ~100ms of zero writes before fallback strategy
            val writeStartTime = System.currentTimeMillis()
            val maxWriteTime = 500L // 500ms timeout to prevent blocking audio thread

            while (off < pcm.size && System.currentTimeMillis() - writeStartTime < maxWriteTime) {
                if (!started.get()) break
                updateSoftStartGain(t)

                try {
                    val n = t.write(pcm, off, pcm.size - off, AudioTrack.WRITE_NON_BLOCKING)
                    if (n < 0) {
                        Log.w(tag, "AudioTrack.write() returned error: $n")
                        markTrackDeadAndRelease(t, "write_error_$n")
                        return false
                    }
                    if (n == 0) {
                        // Buffer full - yield and retry a few times then give up
                        zeroWriteCount++
                        if (zeroWriteCount > maxZeroWrites) {
                            Log.w(
                                tag,
                                "AudioTrack buffer remained full after retries; dropping tail (${pcm.size - off} bytes)",
                            )
                            return off > 0
                        }
                        // Yield to other threads briefly
                        Thread.yield()
                        Thread.sleep(2)
                        continue
                    }
                    // Reset zero write counter on successful write
                    zeroWriteCount = 0
                    if (bytesPerFrame > 0) {
                        totalFramesWritten.addAndGet((n / bytesPerFrame).toLong())
                    }
                    off += n
                } catch (e: Exception) {
                    Log.e(tag, "Error writing to AudioTrack", e)
                    markTrackDeadAndRelease(t, "write_exception")
                    return false
                }
            }

            // Log if we timed out
            if (off < pcm.size) {
                Log.w(tag, "writePcm timeout/incomplete: wrote $off of ${pcm.size} bytes")
                return off > 0
            }
            return true
        } catch (e: Exception) {
            Log.e(tag, "Error in writePcm", e)
            return false
        } finally {
            writingCount.decrementAndGet()
        }
    }

    /**
     * Writes PCM from a (typically direct) ByteBuffer to the AudioTrack, honouring the
     * sendspin-cpp on_audio_write contract: write up to [length] bytes, block at most
     * [timeoutMs], and return the number of bytes actually written. The buffer's position is
     * advanced by the number of bytes consumed.
     */
    fun writePcm(
        buffer: ByteBuffer,
        length: Int,
        timeoutMs: Int,
    ): Int {
        if (length <= 0) return 0
        if (!started.get()) return 0

        writingCount.incrementAndGet()
        try {
            if (!started.get()) return 0
            val t = track ?: return 0
            if (t.state == AudioTrack.STATE_UNINITIALIZED) return 0
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) return 0
            updateSoftStartGain(t)

            val bytesPerFrame = currentChannels * (currentBitDepth / 8)
            val end = buffer.position() + length.coerceAtMost(buffer.remaining())
            buffer.limit(end)

            var written = 0
            var zeroWriteCount = 0
            val maxZeroWrites = 50
            val writeStartTime = System.currentTimeMillis()
            val maxWriteTime = if (timeoutMs > 0) timeoutMs.toLong() else 500L

            while (buffer.position() < end) {
                if (!started.get()) break
                if (System.currentTimeMillis() - writeStartTime >= maxWriteTime) break
                updateSoftStartGain(t)

                try {
                    val remaining = end - buffer.position()
                    val n = t.write(buffer, remaining, AudioTrack.WRITE_NON_BLOCKING)
                    if (n < 0) {
                        Log.w(tag, "AudioTrack.write(ByteBuffer) returned error: $n")
                        markTrackDeadAndRelease(t, "write_error_$n")
                        return written
                    }
                    if (n == 0) {
                        zeroWriteCount++
                        if (zeroWriteCount > maxZeroWrites) {
                            Log.w(tag, "AudioTrack buffer remained full; dropping ${end - buffer.position()} bytes")
                            return written
                        }
                        Thread.yield()
                        Thread.sleep(2)
                        continue
                    }
                    zeroWriteCount = 0
                    written += n
                    if (bytesPerFrame > 0) {
                        totalFramesWritten.addAndGet((n / bytesPerFrame).toLong())
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error writing ByteBuffer to AudioTrack", e)
                    markTrackDeadAndRelease(t, "write_exception")
                    return written
                }
            }
            return written
        } catch (e: Exception) {
            Log.e(tag, "Error in writePcm(ByteBuffer)", e)
            return 0
        } finally {
            writingCount.decrementAndGet()
        }
    }

    /**
     * Resets the playback-feedback baseline after [start] so the next [getPlaybackProgress] call
     * does not emit a spurious delta (e.g. when an AudioTrack is reused and framePosition did not
     * reset to zero after flush).
     */
    fun syncPlaybackFeedbackBaseline() {
        if (!started.get()) return
        synchronized(lock) {
            lastReportedFrames = snapshotPresentedFramesLocked(System.nanoTime())
        }
    }

    /**
     * Returns the frames presented since the previous call and the monotonic timestamp at which
     * they exit the DAC, or null when no new frames have been presented.
     *
     * @param nowUs Monotonic time in the sendspin-cpp clock domain
     *     ([SendspinNative.nativeMonotonicTimeUs]), not [System.nanoTime].
     */
    fun getPlaybackProgress(nowUs: Long): PlaybackProgress? {
        if (!started.get() || currentSampleRate <= 0) return null
        synchronized(lock) {
            val trackRef = track ?: return null
            if (trackRef.state != AudioTrack.STATE_INITIALIZED) return null

            val nowNs = System.nanoTime()
            val presentedFrames = snapshotPresentedFramesLocked(nowNs)

            val delta = (presentedFrames - lastReportedFrames).coerceAtLeast(0L)
            lastReportedFrames = presentedFrames
            if (delta <= 0L) return null

            // Last frame in this delta reached presentation at ~now; remaining pipeline depth is
            // accounted separately via buffered_frames in the native sync task (unplayed_us).
            return PlaybackProgress(delta, nowUs)
        }
    }

    private fun snapshotPresentedFramesLocked(nowNs: Long): Long {
        val trackRef = track ?: return 0L
        if (trackRef.state != AudioTrack.STATE_INITIALIZED) return 0L

        val sampleRate = currentSampleRate.toLong().coerceAtLeast(1L)
        val audioTimestamp = AudioTimestamp()
        if (trackRef.getTimestamp(audioTimestamp) && audioTimestamp.nanoTime != 0L) {
            val elapsedNs = (nowNs - audioTimestamp.nanoTime).coerceAtLeast(0L)
            val framesElapsed = (elapsedNs.toDouble() * sampleRate / 1_000_000_000.0).toLong()
            return audioTimestamp.framePosition + framesElapsed
        }

        val raw = trackRef.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        if (raw < playbackHeadRaw) {
            playbackHeadWraps++
        }
        playbackHeadRaw = raw
        return raw + (playbackHeadWraps shl 32)
    }

    private fun markTrackDeadAndRelease(
        t: AudioTrack,
        reason: String,
    ) {
        Log.w(tag, "Marking AudioTrack dead ($reason), forcing recreate")
        started.set(false)

        synchronized(lock) {
            if (track === t) {
                track = null
                resetLatencyEstimatorLocked()
            }
        }

        try {
            applyTrackGainLocked(t, 0f)
        } catch (_: Exception) {
        }
        try {
            t.pause()
        } catch (_: Exception) {
        }
        try {
            t.flush()
        } catch (_: Exception) {
        }
        try {
            t.stop()
        } catch (_: Exception) {
        }
        try {
            t.release()
        } catch (_: Exception) {
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        if (!started.get()) return

        // Clamp speed to valid range (0.5x to 2.0x typical for AudioTrack)
        val clampedSpeed = speed.coerceIn(0.5f, 2.0f)

        // Skip if speed hasn't changed (avoid redundant calls)
        if (kotlin.math.abs(clampedSpeed - currentPlaybackSpeed) < 0.0001f) {
            return
        }

        synchronized(lock) {
            val t = track ?: return
            if (!started.get()) return
            if (t.state != AudioTrack.STATE_INITIALIZED) {
                Log.w(tag, "Cannot set playback speed: AudioTrack not initialized (state=${t.state})")
                return
            }

            // On low-end devices, playState might briefly not be PLAYING even though track is active.
            // Check both PLAYING and PAUSED to handle edge cases.
            val isPlayable =
                t.playState == AudioTrack.PLAYSTATE_PLAYING ||
                    t.playState == AudioTrack.PLAYSTATE_PAUSED
            if (!isPlayable) {
                Log.w(tag, "Cannot set playback speed: AudioTrack not in playable state (playState=${t.playState})")
                return
            }

            try {
                // PlaybackParams requires API 23+
                val params = PlaybackParams().setSpeed(clampedSpeed)
                t.playbackParams = params
                currentPlaybackSpeed = clampedSpeed
                Log.d(tag, "Playback speed adjusted to ${String.format("%.3f", clampedSpeed)}x")
            } catch (e: UnsupportedOperationException) {
                Log.w(tag, "Playback speed not supported on this device/API level")
            } catch (e: Exception) {
                Log.w(tag, "Failed to set playback speed to $clampedSpeed: ${e.message}")
            }
        }
    }

    private fun resetPlaybackSpeedLocked(track: AudioTrack) {
        try {
            track.playbackParams = PlaybackParams().setSpeed(1.0f)
        } catch (_: Exception) {
        }
        currentPlaybackSpeed = 1.0f
    }

    private fun beginSoftStartLocked(track: AudioTrack) {
        softStartBeganAtMs = SystemClock.elapsedRealtime()
        applyEffectiveGainLocked(track, 0f)
    }

    private fun clearSoftStartLocked() {
        softStartBeganAtMs = 0L
        lastAppliedTrackGain = 0f
    }

    /** Soft-start envelope only (0–1), ignoring mute/volume. Caller must hold [lock]. */
    private fun currentSoftStartGainLocked(): Float {
        val beganAtMs = softStartBeganAtMs
        if (beganAtMs == 0L) return 1.0f
        val elapsedMs = (SystemClock.elapsedRealtime() - beganAtMs).coerceAtLeast(0L)
        return if (elapsedMs >= SOFT_START_RAMP_MS) {
            1.0f
        } else {
            (elapsedMs.toFloat() / SOFT_START_RAMP_MS.toFloat()).coerceIn(0f, 1.0f)
        }
    }

    private fun updateSoftStartGain(track: AudioTrack) {
        val beganAtMs = softStartBeganAtMs
        if (beganAtMs == 0L) return

        val softGain = currentSoftStartGainLocked()

        synchronized(lock) {
            if (this.track !== track || track.state != AudioTrack.STATE_INITIALIZED) return
            val effective = effectiveGainLocked(softGain)
            val shouldApply =
                kotlin.math.abs(effective - lastAppliedTrackGain) >= 0.05f ||
                    softGain >= 0.999f ||
                    lastAppliedTrackGain == 0f
            if (shouldApply) {
                applyEffectiveGainLocked(track, softGain)
            }
            if (softGain >= 0.999f) {
                softStartBeganAtMs = 0L
            }
        }
    }

    /**
     * Combine soft-start, mute, app volume, and duck into a single AudioTrack gain.
     * Caller holds [lock].
     */
    private fun effectiveGainLocked(softStartGain: Float): Float {
        if (playbackMuted) return 0f
        return (softStartGain * appVolumeGain * duckGain).coerceIn(0f, 1.0f)
    }

    private fun applyEffectiveGainLocked(
        track: AudioTrack,
        softStartGain: Float,
    ) {
        applyTrackGainLocked(track, effectiveGainLocked(softStartGain))
    }

    private fun applyTrackGainLocked(
        track: AudioTrack,
        gain: Float,
    ) {
        val clampedGain = gain.coerceIn(0f, 1.0f)
        try {
            track.setVolume(clampedGain)
            lastAppliedTrackGain = clampedGain
        } catch (e: Exception) {
            Log.w(tag, "Failed to set AudioTrack gain=$clampedGain", e)
        }
    }

    /**
     * Get the current playback speed (1.0 = normal speed).
     */
    fun getCurrentPlaybackSpeed(): Float = currentPlaybackSpeed

    /**
     * Get the smoothed latency in milliseconds.
     */
    fun getSmoothedLatencyMs(): Double {
        getEstimatedPipelineLatencyUs()
        return smoothedLatencyUs / 1000.0
    }

    /**
     * PCM queued in the AudioTrack (written but not yet presented), in milliseconds.
     * Used for native-client buffer diagnostics (replaces the old jitter-buffer ahead metric).
     */
    fun getOutputQueueMs(): Long {
        if (!started.get() || currentSampleRate <= 0) return 0L
        val bytesPerFrame = currentChannels * (currentBitDepth / 8)
        if (bytesPerFrame <= 0) return 0L

        synchronized(lock) {
            val trackRef = track ?: return 0L
            if (trackRef.state != AudioTrack.STATE_INITIALIZED) return 0L

            val writtenFrames = totalFramesWritten.get()
            val presentedFrames = snapshotPresentedFramesLocked(System.nanoTime())
            val queuedFrames = (writtenFrames - presentedFrames).coerceAtLeast(0L)
            return (queuedFrames * 1_000L) / currentSampleRate.toLong()
        }
    }

    /**
     * Estimated pipeline latency from AudioTrack write to speaker output (microseconds).
     * Prefers AudioTimestamp (which captures DSP and hardware-buffer latency that
     * playbackHeadPosition misses on devices such as Amazon Echo Show 8 running LineageOS
     * (android_device_amazon_crown)),
     * falling back to playbackHeadPosition when a timestamp is unavailable.
     * No fixed 250 ms upper cap: high-latency devices are reported accurately.
     */
    fun getEstimatedPipelineLatencyUs(): Long {
        val bytesPerFrame = currentChannels * (currentBitDepth / 8)
        val floorUs =
            if (lastMinBufBytes > 0 && currentSampleRate > 0 && bytesPerFrame > 0) {
                (lastMinBufBytes.toLong() * 1_000_000L) / (currentSampleRate.toLong() * bytesPerFrame)
            } else {
                40_000L
            }

        val t = track
        if (!started.get() || t == null || currentSampleRate <= 0) {
            return floorUs
        }

        synchronized(lock) {
            val trackRef = track ?: return floorUs
            if (trackRef.state != AudioTrack.STATE_INITIALIZED) return floorUs

            // Always update playback-head state so the wrap counter stays consistent
            // even when AudioTimestamp is used as the primary measurement.
            val raw = trackRef.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
            if (raw < playbackHeadRaw) {
                playbackHeadWraps++
            }
            playbackHeadRaw = raw
            val playedFrames = raw + (playbackHeadWraps shl 32)

            val writtenFrames = totalFramesWritten.get()

            // Prefer AudioTimestamp: it captures DSP / HAL / hardware-buffer latency that
            // playbackHeadPosition misses on devices like Amazon Echo Show 8 running LineageOS
            // (android_device_amazon_crown).
            val audioTimestamp = AudioTimestamp()
            val dynamicUs: Long =
                if (trackRef.getTimestamp(audioTimestamp) &&
                    audioTimestamp.nanoTime != 0L
                ) {
                    val nowNs = System.nanoTime()
                    // Extrapolate the presented-frame position from the timestamp instant to now.
                    val elapsedNs = (nowNs - audioTimestamp.nanoTime).coerceAtLeast(0L)
                    val framesElapsed =
                        (elapsedNs.toDouble() * currentSampleRate / 1_000_000_000.0).toLong()
                    val currentPresentedFrames = audioTimestamp.framePosition + framesElapsed
                    val framesInFlight = (writtenFrames - currentPresentedFrames).coerceAtLeast(0L)
                    framesInFlight * 1_000_000L / currentSampleRate.toLong()
                } else {
                    // Fallback: derive latency from the software playback-head position only.
                    val queuedFrames = (writtenFrames - playedFrames).coerceAtLeast(0L)
                    (queuedFrames * 1_000_000L) / currentSampleRate.toLong()
                }

            val combinedUs = max(dynamicUs, floorUs)

            smoothedLatencyUs =
                if (smoothedLatencyUs == 0L) {
                    combinedUs
                } else {
                    // 70/30 IIR smoothing to avoid jittery control decisions
                    ((smoothedLatencyUs * 7L) + (combinedUs * 3L)) / 10L
                }

            // No fixed 250 ms upper cap: allow accurate reporting for high-latency devices
            // (e.g. Amazon Echo Show 8 running LineageOS, which can have 700 ms+ pipeline latency).
            // MAX_PIPELINE_LATENCY_US guards against completely unrealistic values.
            return smoothedLatencyUs.coerceIn(20_000L, MAX_PIPELINE_LATENCY_US)
        }
    }

    fun flushSilence(ms: Int) {
        if (!started.get()) return
        val t = track ?: return

        try {
            val bytesPerSample = currentBitDepth / 8
            val bytesPerFrame = currentChannels * bytesPerSample
            val frames = (currentSampleRate * ms) / 1000
            val bytes = (frames * bytesPerFrame).coerceAtMost(8192)

            val buf = ByteArray(bytes)
            t.write(buf, 0, buf.size)
        } catch (e: Exception) {
            Log.w(tag, "Error flushing silence", e)
        }
    }

    fun stop() {
        started.set(false)
        synchronized(lock) {
            val t = track
            stopInternal(t)
        }
    }

    private fun stopInternal(t: AudioTrack?) {
        track = null // Clear reference so new writes fail fast
        resetLatencyEstimatorLocked()
        if (t != null) {
            try {
                if (t.state == AudioTrack.STATE_INITIALIZED) {
                    // Pause first to unblock any writers blocked in native write()
                    try {
                        applyTrackGainLocked(t, 0f)
                    } catch (_: Exception) {
                    }
                    try {
                        t.pause()
                    } catch (_: Exception) {
                    }
                    try {
                        t.flush()
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Error accessing AudioTrack state during stop", e)
            }

            // Wait for active writers to exit to prevent SIGABRT on release
            var attempts = 50 // 500ms max wait
            while (writingCount.get() > 0 && attempts > 0) {
                try {
                    Thread.sleep(10)
                } catch (_: Exception) {
                }
                attempts--
            }

            if (writingCount.get() > 0) {
                Log.e(tag, "WARNING: Releasing AudioTrack with ${writingCount.get()} active writers. Crash likely.")
            }

            try {
                t.stop()
            } catch (_: Exception) {
            }

            try {
                t.release()
                Log.i(tag, "AudioTrack released")
            } catch (e: Exception) {
                Log.w(tag, "Error releasing AudioTrack", e)
            }
        }
    }

    private fun resetLatencyEstimatorLocked() {
        totalFramesWritten.set(0L)
        playbackHeadRaw = 0L
        playbackHeadWraps = 0L
        smoothedLatencyUs = 0L
        currentPlaybackSpeed = 1.0f
        softStartBeganAtMs = 0L
        lastAppliedTrackGain = 1.0f
        // Seed from the current presentation position so a reused/flushed track does not emit a
        // bogus multi-second delta on the first feedback poll.
        lastReportedFrames = snapshotPresentedFramesLocked(System.nanoTime())
    }

    fun checkAudioCapabilities(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val pm = context.packageManager
        val hasLowLatency = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)
        val hasPro = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO)
        val optimalFramesStr = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
        val optimalFrames = optimalFramesStr?.toIntOrNull() ?: 256
        val optimalRateStr = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
        val optimalRate = optimalRateStr?.toIntOrNull() ?: 48000
        val deviceOptimalBufferMs = (optimalFrames * 1000) / optimalRate
        Log.i(
            tag,
            "Audio capabilities: lowLatency=$hasLowLatency pro=$hasPro optimalFrames=$optimalFrames optimalRate=$optimalRate optimalBufferMs=$deviceOptimalBufferMs",
        )
        // For now we won't do anything with this, but we can explore adjusting the 250ms HAL buffer based on the deviceOptimalBuffer
        // Buffer sizing might need to be dynamic depending on the spec of the device
        // Will put us more at risk of underruns, chunk drops and recovery events via audibleSyncs
        // The app will also be more sensitive to jitter, which can happen at anypoint on the audio pipeline
        // Benefits to this would be a lower base output latency which will be good for responsiveness
    }

    fun getDevicePreferredSampleRate(context: Context): Int {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        } catch (_: Exception) {
            48000
        }
    }

    /**
     * Select the highest native PCM format by probing AudioTrack initialization.
     * Preference order:
     *  1) preferred sample rate first (when present in candidates)
     *  2) higher sample rates
     *  3) more channels
     *  4) higher bit depth (32 -> 24 -> 16)
     */
    fun selectHighestNativePcmFormat(
        context: Context,
        sampleRateCandidates: List<Int>,
        channelCandidates: List<Int>,
        bitDepthCandidates: List<Int>,
    ): PcmFormat? {
        val preferredSampleRate = getDevicePreferredSampleRate(context)
        val orderedSampleRates =
            sampleRateCandidates
                .distinct()
                .sortedWith(
                    compareByDescending<Int> { it == preferredSampleRate }
                        .thenByDescending { it },
                )
        val orderedChannels = channelCandidates.distinct().sortedDescending()
        val orderedBitDepths = bitDepthCandidates.distinct().sortedDescending()

        for (sampleRate in orderedSampleRates) {
            for (channels in orderedChannels) {
                for (bitDepth in orderedBitDepths) {
                    val format = PcmFormat(sampleRate, channels, bitDepth)
                    if (isNativePcmFormatSupported(format)) {
                        Log.i(
                            tag,
                            "Selected native warmup format sr=${format.sampleRate} ch=${format.channels} bd=${format.bitDepth}",
                        )
                        return format
                    }
                }
            }
        }
        return null
    }

    private fun isNativePcmFormatSupported(format: PcmFormat): Boolean {
        return PcmFormatSupport.isPlaybackFormatSupported(
            format.sampleRate,
            format.channels,
            format.bitDepth,
        )
    }
}
