package com.sendspinlite.protocol

import org.json.JSONArray
import org.json.JSONObject

object SendspinPayloadFactory {
    private fun isBitDepthSupported(sampleRate: Int, bitDepth: Int): Boolean {
        return try {
            val encoding = when (bitDepth) {
                16 -> android.media.AudioFormat.ENCODING_PCM_16BIT
                24 -> android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED
                32 -> android.media.AudioFormat.ENCODING_PCM_32BIT
                else -> return false
            }
            val channelMask = android.media.AudioFormat.CHANNEL_OUT_STEREO
            val minBuf = android.media.AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
            minBuf > 0
        } catch (_: Throwable) {
            false
        }
    }

    fun buildPlayerSupportObject(): JSONObject {
        val supportedFormats = JSONArray()
        for (sampleRate in listOf(48000, 44100)) {
            val supportedDepths = listOf(16, 24, 32).filter { bitDepth ->
                isBitDepthSupported(sampleRate, bitDepth)
            }
            val finalDepths = if (supportedDepths.isEmpty()) listOf(16) else supportedDepths

            for (bitDepth in finalDepths) {
                supportedFormats.put(
                    JSONObject().put("codec", "pcm").put("channels", 2)
                        .put("sample_rate", sampleRate).put("bit_depth", bitDepth)
                )
            }
        }
        val supportedCommands = JSONArray().put("volume").put("mute")
        return JSONObject()
            .put("supported_formats", supportedFormats)
            .put("buffer_capacity", 2_000_000)
            .put("supported_commands", supportedCommands)
    }

    fun buildHello(clientId: String, clientName: String, model: String, manufacturer: String, versionName: String?): JSONObject {
        val hello = JSONObject()
            .put("client_id", clientId)
            .put("name", clientName)
            .put("version", 1)
            .put(
                "device_info",
                JSONObject()
                    .put("product_name", model)
                    .put("manufacturer", manufacturer)
                    .put("software_version", versionName ?: "")
            )
            .put("supported_roles", JSONArray().put("player@v1"))

        val playerSupport = buildPlayerSupportObject()
        hello.put("player@v1_support", playerSupport)
        return hello
    }

    fun buildStatePlayer(volume: Int?, muted: Boolean?, staticDelayMs: Long?): JSONObject {
        val player = JSONObject()
        volume?.let { player.put("volume", it) }
        muted?.let { player.put("muted", it) }
        staticDelayMs?.let { player.put("static_delay_ms", it) }
        return JSONObject().put("player", player)
    }

    fun buildStateSynchronized(volume: Int, muted: Boolean, staticDelayMs: Long): JSONObject {
        val player = JSONObject()
            .put("volume", volume)
            .put("muted", muted)
            .put("static_delay_ms", staticDelayMs)
            .put("supported_commands", JSONArray().put("set_static_delay"))
        return JSONObject()
            .put("state", "synchronized")
            .put("player", player)
    }

    fun buildStateError(volume: Int, muted: Boolean, staticDelayMs: Long): JSONObject {
        val player = JSONObject()
            .put("volume", volume)
            .put("muted", muted)
            .put("static_delay_ms", staticDelayMs)
        return JSONObject()
            .put("state", "error")
            .put("player", player)
    }

    fun buildGoodbye(reason: String): JSONObject {
        return JSONObject().put("reason", reason)
    }
}
