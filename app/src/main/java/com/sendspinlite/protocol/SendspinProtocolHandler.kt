package com.sendspinlite.protocol

import android.util.Log
import org.json.JSONObject

interface SendspinProtocolListener {
    fun onHelloReceived(activeRoles: String, hasController: Boolean, hasMetadata: Boolean)
    fun onTimeSync(clientTx: Long, sRecv: Long, sTx: Long)
    fun onStreamStart(codec: String, sampleRate: Int, channels: Int, bitDepth: Int, playAtUs: Long)
    fun onStreamClear()
    fun onStreamEnd()
    fun onGroupUpdate(playbackState: String, groupName: String)
    fun onServerState(controllerVolume: Int?, controllerMuted: Boolean?, supportedCommands: Set<String>?, metadata: JSONObject?)
    fun onVolumeCommand(volume: Int)
    fun onMuteCommand(muted: Boolean)
    fun onStaticDelayCommand(delayMs: Long)
}

class SendspinProtocolHandler(
    private val tag: String,
    private val listener: SendspinProtocolListener
) {
    fun handleText(text: String) {
        try {
            val obj = JSONObject(text)
            val type = obj.optString("type", "")
            val payload = obj.optJSONObject("payload") ?: JSONObject()

            if (type != "server/time") {
                Log.i(
                    tag,
                    "<<<< RECV: $type | ${text.take(200)}${if (text.length > 200) "..." else ""}",
                )
            }

            when (type) {
                "server/hello" -> {
                    val activeRoles =
                        payload.optJSONArray("active_roles")?.let { arr ->
                            (0 until arr.length()).joinToString(",") { arr.getString(it) }
                        } ?: ""

                    val hasController = activeRoles.contains("controller")
                    val hasMetadata = activeRoles.contains("metadata")
                    Log.i(
                        tag,
                        "Active roles: $activeRoles, hasController: $hasController, hasMetadata: $hasMetadata",
                    )
                    listener.onHelloReceived(activeRoles, hasController, hasMetadata)
                }

                "server/time" -> {
                    val clientTx = payload.getLong("client_transmitted")
                    val sRecv = payload.getLong("server_received")
                    val sTx = payload.getLong("server_transmitted")
                    listener.onTimeSync(clientTx, sRecv, sTx)
                }

                "stream/start" -> {
                    val player = payload.optJSONObject("player")
                    if (player != null) {
                        val codec = player.optString("codec", "")
                        val sampleRate = player.optInt("sample_rate", 48000)
                        val channels = player.optInt("channels", 2)
                        val bitDepth = player.optInt("bit_depth", 16)
                        val playAtUs = if (player.has("play_at")) {
                            player.optLong("play_at", Long.MIN_VALUE)
                        } else {
                            Long.MIN_VALUE
                        }
                        listener.onStreamStart(codec, sampleRate, channels, bitDepth, playAtUs)
                    }
                }

                "stream/clear" -> {
                    listener.onStreamClear()
                }

                "stream/end" -> {
                    listener.onStreamEnd()
                }

                "group/update" -> {
                    val playbackState = payload.optString("playback_state", "")
                    val groupName = payload.optString("group_name", "")
                    listener.onGroupUpdate(playbackState, groupName)
                }

                "server/state" -> {
                    var controllerVolume: Int? = null
                    var controllerMuted: Boolean? = null
                    var supportedCommands: Set<String>? = null

                    val controller = payload.optJSONObject("controller")
                    if (controller != null) {
                        controllerVolume = controller.optInt("volume", 100)
                        controllerMuted = controller.optBoolean("muted", false)
                        supportedCommands =
                            controller.optJSONArray("supported_commands")?.let { arr ->
                                (0 until arr.length()).map { arr.getString(it) }.toSet()
                            } ?: emptySet()
                    }

                    val metadata = payload.optJSONObject("metadata")
                    listener.onServerState(controllerVolume, controllerMuted, supportedCommands, metadata)
                }

                "server/command" -> {
                    val player = payload.optJSONObject("player")
                    if (player != null) {
                        val command = player.optString("command", "")
                        when (command) {
                            "volume" -> {
                                val volume = player.optInt("volume", 100)
                                listener.onVolumeCommand(volume)
                            }

                            "mute" -> {
                                val muted = player.optBoolean("mute", false)
                                listener.onMuteCommand(muted)
                            }

                            "set_static_delay" -> {
                                val newDelayMs = player.optLong("static_delay_ms", 0L).coerceIn(0L, 5000L)
                                listener.onStaticDelayCommand(newDelayMs)
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(tag, "Bad JSON: ${t.message}", t)
        }
    }
}
