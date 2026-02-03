package com.sendspinlite

import android.media.*
import android.util.Log
import kotlin.math.max
import java.util.concurrent.atomic.AtomicBoolean

class PcmAudioOutput {
    private val tag = "PcmAudioOutput"

    private var track: AudioTrack? = null
    private val started = AtomicBoolean(false)

    private var currentSampleRate = 48000
    private var currentChannels = 2
    private var currentBitDepth = 16

    fun isStarted(): Boolean = started.get()

    fun start(sampleRate: Int, channels: Int, bitDepth: Int) {
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

        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
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

            currentSampleRate = sampleRate
            currentChannels = channels
            currentBitDepth = bitDepth

            Log.i(tag, "AudioTrack started sr=$sampleRate ch=$channels bd=$bitDepth minBuf=$minBuf bufferBytes=$bufferBytes")
        } catch (e: Exception) {
            Log.e(tag, "Failed to create/start AudioTrack", e)
            started.set(false)
            track = null
        }
    }

    fun writePcm(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        if (!started.get()) return
        
        val t = track ?: return
        
        try {
            // Check track state before writing
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                Log.w(tag, "AudioTrack not playing, state=${t.playState}")
                return
            }
            
            var off = 0
            while (off < pcm.size) {
                if (!started.get()) break  // Stop writing if track was stopped
                
                try {
                    val n = t.write(pcm, off, pcm.size - off)
                    if (n < 0) {
                        Log.w(tag, "AudioTrack.write() returned error: $n")
                        break
                    }
                    if (n == 0) break  // Buffer full, stop trying
                    off += n
                } catch (e: Exception) {
                    Log.e(tag, "Error writing to AudioTrack", e)
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error in writePcm", e)
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
        try {
            track?.pause()
        } catch (e: Exception) {
            Log.w(tag, "Error pausing AudioTrack", e)
        }
        try {
            // Flush any remaining data in the buffer before stopping (only if track still exists)
            if (track?.state == android.media.AudioTrack.STATE_INITIALIZED) {
                track?.flush()
            }
        } catch (e: Exception) {
            Log.w(tag, "Error flushing AudioTrack (Fix with AI)", e)
        }
        try {
            // Stop the track immediately to prevent buffered audio from playing
            track?.stop()
        } catch (e: Exception) {
            Log.w(tag, "Error stopping AudioTrack", e)
        }
        try {
            // Release the audio track to free system resources immediately
            track?.release()
        } catch (e: Exception) {
            Log.w(tag, "Error releasing AudioTrack", e)
        }
        track = null
    }
}
