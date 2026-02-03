package com.sendspinlite

import android.util.Log
//import org.concentus.OpusDecoder as ConcentusDecoder
import io.github.jaredmdobson.concentus.OpusDecoder as ConcentusDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opus decoder wrapper using Concentus (pure Java Opus implementation).
 * Decodes Opus frames to 16-bit PCM.
 */
class OpusDecoder(
    private val sampleRate: Int,
    private val channels: Int
) {
    private val tag = "OpusDecoder"

    // Concentus decoder instance
    private val decoder = try {
        ConcentusDecoder(sampleRate, channels)
    } catch (e: Exception) {
        Log.e(tag, "Failed to create Opus decoder", e)
        throw e
    }

    /**
     * Decode an Opus frame to PCM samples.
     * @param opusData Encoded Opus frame
     * @return ByteArray of 16-bit little-endian PCM samples
     */
    fun decode(opusData: ByteArray): ByteArray {
        try {
            // Maximum frame size for Opus at 48kHz is 5760 samples per channel (120ms)
            val maxFrameSize = 5760
            val pcmSamples = ShortArray(maxFrameSize * channels)

            // Decode Opus frame to PCM samples
            val samplesDecoded = decoder.decode(
                opusData,
                0,
                opusData.size,
                pcmSamples,
                0,
                maxFrameSize,
                false // No FEC for now
            )

            if (samplesDecoded < 0) {
                Log.e(tag, "Decode error: $samplesDecoded")
                return ByteArray(0)
            }

            // Convert short samples to little-endian byte array
            val pcmBytes = ByteArray(samplesDecoded * channels * 2)
            val buffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)

            for (i in 0 until (samplesDecoded * channels)) {
                buffer.putShort(pcmSamples[i])
            }

            return pcmBytes
        } catch (e: Exception) {
            Log.e(tag, "Decode exception", e)
            return ByteArray(0)
        }
    }

    fun reset() {
        try {
            decoder.resetState()
        } catch (e: Exception) {
            Log.w(tag, "Reset failed", e)
        }
    }
}