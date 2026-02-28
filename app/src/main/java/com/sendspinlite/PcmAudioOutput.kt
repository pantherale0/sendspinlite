package com.sendspinlite

import android.media.*
import android.util.Log
import kotlin.math.max
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
            val bufferBytes = max(minBuf, 4 * minBuf)

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

                audioTrack.play()
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
        synchronized(lock) {
            val t = track
            if (t != null && t.state == AudioTrack.STATE_INITIALIZED) {
                try {
                    t.pause()
                    t.flush()
                    Log.i(tag, "AudioTrack paused and flushed")
                } catch (e: Exception) {
                    Log.w(tag, "Error pausing/flushing AudioTrack", e)
                    stopInternal(t)
                }
            }
        }
    }

    fun writePcm(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        if (!started.get()) return
        
        // Signal we are using the track
        writingCount.incrementAndGet()
        try {
            // Double check state after increment
            if (!started.get()) return
            
            val t = track ?: return
            
            if (t.state == AudioTrack.STATE_UNINITIALIZED) return 
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) return
            
            var off = 0
            while (off < pcm.size) {
                if (!started.get()) break
                
                try {
                    val n = t.write(pcm, off, pcm.size - off)
                    if (n < 0) {
                        Log.w(tag, "AudioTrack.write() returned error: $n")
                        break
                    }
                    if (n == 0) break
                    off += n
                } catch (e: Exception) {
                    Log.e(tag, "Error writing to AudioTrack", e)
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error in writePcm", e)
        } finally {
            writingCount.decrementAndGet()
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
}
