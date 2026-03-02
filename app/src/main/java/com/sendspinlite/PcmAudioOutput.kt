package com.sendspinlite

import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.util.Log
import kotlin.math.max
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class PcmAudioOutput {
    private val tag = "PcmAudioOutput"

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
    private var smoothedLatencyUs: Long = 0L
    
    // Current playback speed
    @Volatile
    private var currentPlaybackSpeed: Float = 1.0f

    fun isStarted(): Boolean = started.get()

    fun start(sampleRate: Int, channels: Int, bitDepth: Int) {
        synchronized(lock) {
            // Check if we can reuse the existing track
            val existingTrack = track
            if (existingTrack != null &&
                currentSampleRate == sampleRate &&
                currentChannels == channels &&
                currentBitDepth == bitDepth &&
                existingTrack.state == AudioTrack.STATE_INITIALIZED) {
                
                // Just flush and restart playback (Resume)
                try {
                    // Always flush before restarting to clear old data
                    existingTrack.pause()
                    existingTrack.flush()
                    existingTrack.play()
                    resetLatencyEstimatorLocked()
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

            val channelMask = when (channels) {
                1 -> AudioFormat.CHANNEL_OUT_MONO
                2 -> AudioFormat.CHANNEL_OUT_STEREO
                else -> error("Unsupported channel count: $channels")
            }

            val encoding = when (bitDepth) {
                16 -> AudioFormat.ENCODING_PCM_16BIT
                24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                32 -> AudioFormat.ENCODING_PCM_32BIT
                else -> error("Unsupported bit depth: $bitDepth")
            }

            // Ensure valid sample rate
            val safeSampleRate = sampleRate.coerceAtLeast(4000)

            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(safeSampleRate)
                .setChannelMask(channelMask)
                .build()

            val minBuf = AudioTrack.getMinBufferSize(safeSampleRate, channelMask, encoding)
            lastMinBufBytes = minBuf
            // Calculate 250ms buffer size
            val bytesPerFrame = channels * (bitDepth / 8)
            val buffer250ms = (safeSampleRate * 0.25 * bytesPerFrame).toInt()
            
            // Use at least 250ms buffer, or 4x minBuf, whichever is larger, to ensure stability
            val bufferBytes = max(minBuf * 4, buffer250ms)

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            try {
                val audioTrack = AudioTrack(
                    attrs,
                    format,
                    bufferBytes,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )

                if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
                    Log.e(tag, "AudioTrack init failed (state=${audioTrack.state})")
                    try { audioTrack.release() } catch (_: Exception) {}
                    started.set(false)
                    track = null
                    return
                }

                audioTrack.play()
                track = audioTrack
                resetLatencyEstimatorLocked()
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
            try { Thread.sleep(10) } catch (_: Exception) {}
            attempts--
        }

        synchronized(lock) {
            val t = track
            if (t != null && t.state == AudioTrack.STATE_INITIALIZED) {
                try {
                    t.pause()
                    t.flush()
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
            
            var off = 0
            val bytesPerFrame = currentChannels * (currentBitDepth / 8)
            var zeroWriteCount = 0
            val maxZeroWrites = 50  // ~100ms of zero writes before giving up
            val writeStartTime = System.currentTimeMillis()
            val maxWriteTime = 500L  // 500ms timeout to prevent blocking audio thread
            
            while (off < pcm.size && System.currentTimeMillis() - writeStartTime < maxWriteTime) {
                if (!started.get()) break
                
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
                            Log.w(tag, "AudioTrack buffer persistently full after $zeroWriteCount attempts, skipping rest of chunk")
                            return false
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
                Log.w(tag, "writePcm timeout/incomplete: wrote ${off} of ${pcm.size} bytes")
                return false
            }
            return true
        } catch (e: Exception) {
            Log.e(tag, "Error in writePcm", e)
            return false
        } finally {
            writingCount.decrementAndGet()
        }
    }

    private fun markTrackDeadAndRelease(t: AudioTrack, reason: String) {
        Log.w(tag, "Marking AudioTrack dead ($reason), forcing recreate")
        started.set(false)

        synchronized(lock) {
            if (track === t) {
                track = null
                resetLatencyEstimatorLocked()
            }
        }

        try { t.pause() } catch (_: Exception) {}
        try { t.flush() } catch (_: Exception) {}
        try { t.stop() } catch (_: Exception) {}
        try { t.release() } catch (_: Exception) {}
    }

    fun setPlaybackSpeed(speed: Float) {
        if (!started.get()) return
        synchronized(lock) {
            val t = track ?: return
            if (!started.get()) return
            if (t.state != AudioTrack.STATE_INITIALIZED) return
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) return

            try {
                // Clamp speed to valid range (0.5x to 2.0x typical for AudioTrack)
                val clampedSpeed = speed.coerceIn(0.5f, 2.0f)
                // PlaybackParams requires API 23+
                val params = PlaybackParams().setSpeed(clampedSpeed)
                t.playbackParams = params
                currentPlaybackSpeed = clampedSpeed
                Log.d(tag, "Playback speed adjusted to ${String.format("%.3f", clampedSpeed)}x")
            } catch (e: Exception) {
                Log.w(tag, "Failed to set playback speed", e)
            }
        }
    }

    /**
     * Get the current playback speed (1.0 = normal speed).
     */
    fun getCurrentPlaybackSpeed(): Float = currentPlaybackSpeed

    /**
     * Get the smoothed latency in milliseconds.
     */
    fun getSmoothedLatencyMs(): Double = smoothedLatencyUs / 1000.0

    /**
     * Estimated pipeline latency from AudioTrack write to speaker output (microseconds).
     * Uses dynamic queue depth (written frames - played frames) with smoothing,
     * and never goes below HAL minimum-buffer latency floor.
     */
    fun getEstimatedPipelineLatencyUs(): Long {
        val bytesPerFrame = currentChannels * (currentBitDepth / 8)
        val floorUs = if (lastMinBufBytes > 0 && currentSampleRate > 0 && bytesPerFrame > 0) {
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

            val raw = trackRef.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
            if (raw < playbackHeadRaw) {
                playbackHeadWraps++
            }
            playbackHeadRaw = raw

            val playedFrames = raw + (playbackHeadWraps shl 32)
            val writtenFrames = totalFramesWritten.get()
            val queuedFrames = (writtenFrames - playedFrames).coerceAtLeast(0L)

            val dynamicUs = (queuedFrames * 1_000_000L) / currentSampleRate.toLong()
            val combinedUs = max(dynamicUs, floorUs)

            smoothedLatencyUs = if (smoothedLatencyUs == 0L) {
                combinedUs
            } else {
                // 70/30 IIR smoothing to avoid jittery control decisions
                ((smoothedLatencyUs * 7L) + (combinedUs * 3L)) / 10L
            }

            return smoothedLatencyUs.coerceIn(20_000L, 250_000L)
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
                    try { t.pause() } catch (_: Exception) {}
                    try { t.flush() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(tag, "Error accessing AudioTrack state during stop", e)
            }
            
            // Wait for active writers to exit to prevent SIGABRT on release
            var attempts = 50 // 500ms max wait
            while (writingCount.get() > 0 && attempts > 0) {
                try { Thread.sleep(10) } catch (_: Exception) {}
                attempts--
            }
            
            if (writingCount.get() > 0) {
                Log.e(tag, "WARNING: Releasing AudioTrack with ${writingCount.get()} active writers. Crash likely.")
            }

            try {
                t.stop()
            } catch (_: Exception) {}
            
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
        val deviceOptimalBuffer = optimalRate / optimalFrames
        Log.i(tag, "Audio capabilities: lowLatency=$hasLowLatency pro=$hasPro optimalFrames=$optimalFrames optimalRate=$optimalRate optimalBuffer=$deviceOptimalBuffer")
        // For now we won't do anything with this, but we can explore adjusting the 250ms HAL buffer based on the deviceOptimalBuffer
        // This could look like deviceOptimalBuffer*bufferSizing
        // Buffer sizing might need to be dynamic depending on the spec of the device
        // Will put us more at risk of underruns, chunk drops and recovery events via audibleSyncs
        // The app will also be more sensitive to jitter, which can happen at anypoint on the audio pipeline
        // Benefits to this would be a lower base output latency which will be good for responsiveness
    }
}
