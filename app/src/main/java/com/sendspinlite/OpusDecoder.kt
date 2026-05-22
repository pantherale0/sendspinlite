package com.sendspinlite

import android.util.Log
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import io.github.jaredmdobson.concentus.OpusDecoder as ConcentusDecoder

/**
 * Opus decoder wrapper using Concentus (pure Java Opus implementation).
 * Decodes Opus frames to 16-bit PCM.
 *
 * Mirrors Music Assistant's Android decoder: reused PCM buffer, synchronized access,
 * resetState() for stream transitions instead of always allocating a new instance.
 */
class OpusDecoder(
    val sampleRate: Int,
    val channels: Int,
) {
    private val tag = "OpusDecoder"
    private val decoderLock = Any()

    init {
        require(
            OpusFormatPolicy.isAdvertisedOpusStream(
                sampleRate,
                channels,
                OpusFormatPolicy.REQUIRED_BIT_DEPTH,
            ),
        ) {
            "Unsupported Opus decoder config: sampleRate=$sampleRate channels=$channels"
        }
    }

    companion object {
        private const val MAX_FRAME_SIZE = 5760
        private const val OPUS_FRAMES_PER_SECOND = 50

        /** Valid Opus frame for JIT warmup — generated locally, no network needed. */
        fun createWarmupPacket(
            sampleRate: Int,
            channels: Int,
        ): ByteArray {
            val encoder = OpusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_AUDIO)
            val frameSamples = sampleRate / OPUS_FRAMES_PER_SECOND
            val pcm = ShortArray(frameSamples * channels)
            val out = ByteArray(OpusFormatPolicy.MAX_PACKET_BYTES)
            val len = encoder.encode(pcm, 0, frameSamples, out, 0, out.size)
            check(len > 0) { "Opus encoder produced no output for warmup packet" }
            return out.copyOf(len)
        }
    }

    private val decoder: ConcentusDecoder =
        try {
            ConcentusDecoder(sampleRate, channels)
        } catch (e: Exception) {
            Log.e(tag, "Failed to create Opus decoder", e)
            throw e
        }

    // Reused decode buffers (same layout as Music Assistant mobile app).
    private val pcmBuffer = ShortArray(MAX_FRAME_SIZE * channels)
    private var outputBuffer = ByteArray(MAX_FRAME_SIZE * channels * 2)

    /**
     * Decode an Opus frame to PCM samples.
     * @param opusData Encoded Opus frame
     * @return ByteArray of 16-bit little-endian PCM samples
     */
    fun decode(opusData: ByteArray): ByteArray {
        if (opusData.isEmpty()) return ByteArray(0)

        return synchronized(decoderLock) {
            try {
                val samplesDecoded =
                    decoder.decode(
                        opusData,
                        0,
                        opusData.size,
                        pcmBuffer,
                        0,
                        MAX_FRAME_SIZE,
                        false,
                    )

                if (samplesDecoded <= 0) {
                    Log.w(tag, "Decode error: $samplesDecoded (${opusData.size} bytes)")
                    return@synchronized ByteArray(0)
                }

                val totalSamples = samplesDecoded * channels
                val pcmBytes = totalSamples * 2
                if (outputBuffer.size < pcmBytes) {
                    outputBuffer = ByteArray(pcmBytes)
                }

                val buffer = ByteBuffer.wrap(outputBuffer, 0, pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until totalSamples) {
                    buffer.putShort(pcmBuffer[i])
                }
                outputBuffer.copyOf(pcmBytes)
            } catch (e: Exception) {
                Log.e(tag, "Decode exception (${opusData.size} bytes)", e)
                ByteArray(0)
            }
        }
    }

    fun reset() {
        synchronized(decoderLock) {
            try {
                decoder.resetState()
            } catch (e: Exception) {
                Log.w(tag, "Reset failed", e)
            }
        }
    }
}
