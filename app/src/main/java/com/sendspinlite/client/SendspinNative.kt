package com.sendspinlite.client

import java.nio.ByteBuffer

/**
 * Native bridge callbacks implemented by [SendspinNativeClient]. Audio callbacks run on the
 * native sync thread. Other callbacks run on the native main-loop thread. Implementations must
 * be thread-safe and may block only within the audio write timeout.
 */
internal interface SendspinNativeCallbacks {
    /** Writes decoded PCM to the platform output, returning the number of bytes written. */
    fun onAudioWrite(
        buffer: ByteBuffer,
        length: Int,
        timeoutMs: Int,
    ): Int

    fun onStreamStart(
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
    )

    fun onStreamEnd()

    fun onVolumeChanged(volume: Int)

    fun onMuteChanged(muted: Boolean)

    fun onStaticDelayChanged(delayMs: Int)

    /** Absent fields use null for strings and -1 for numbers. */
    fun onMetadataUpdate(
        title: String?,
        artist: String?,
        album: String?,
        albumArtist: String?,
        artworkUrl: String?,
        year: Int,
        track: Int,
        progressMs: Int,
        durationMs: Int,
    )

    fun onMetadataClear()

    fun onGroupUpdate(
        playbackState: String?,
        groupId: String?,
        groupName: String?,
    )

    fun onControllerState(
        supportedCommands: Array<String>,
        volume: Int,
        muted: Boolean,
        repeatMode: String,
        shuffle: Boolean,
    )

    fun onControllerStateClear()

    fun onTimeSyncUpdated(errorUs: Float)

    fun onRequestHighPerformance()

    fun onReleaseHighPerformance()

    fun onConnectionState(
        status: String,
        connected: Boolean,
    )

    fun isNetworkReady(): Boolean
}

/**
 * JNI methods for the sendspin-cpp client. The caller owns each handle returned by
 * [nativeCreate] and must call [nativeDestroy] exactly once for that handle.
 */
internal object SendspinNative {
    init {
        System.loadLibrary("sendspin_jni")
    }

    external fun nativeCreate(
        callbacks: SendspinNativeCallbacks,
        clientId: String,
        name: String,
        productName: String,
        manufacturer: String,
        softwareVersion: String,
        fixedDelayUs: Int,
        audioBufferCapacity: Long,
        initialStaticDelayMs: Int,
    ): Long

    external fun nativeStart(handle: Long)

    external fun nativeConnect(
        handle: Long,
        url: String,
    )

    external fun nativeDisconnect(
        handle: Long,
        reason: Int,
    )

    external fun nativeDestroy(handle: Long)

    external fun nativeNotifyAudioPlayed(
        handle: Long,
        frames: Int,
        finishTimestampUs: Long,
    )

    external fun nativeUpdateVolume(
        handle: Long,
        volume: Int,
    )

    external fun nativeUpdateMuted(
        handle: Long,
        muted: Boolean,
    )

    external fun nativeUpdateStaticDelay(
        handle: Long,
        delayMs: Int,
    )

    external fun nativeSendControllerCommand(
        handle: Long,
        command: String,
    ): Boolean

    external fun nativeIsConnected(handle: Long): Boolean

    external fun nativeIsTimeSynced(handle: Long): Boolean

    external fun nativeGetVolume(handle: Long): Int

    external fun nativeGetMuted(handle: Long): Boolean

    external fun nativeGetStaticDelayMs(handle: Long): Int

    external fun nativeGetTrackProgressMs(handle: Long): Int

    external fun nativeGetTrackDurationMs(handle: Long): Int

    /** Microseconds in sendspin-cpp's steady_clock domain, matching platform_time_us. */
    external fun nativeMonotonicTimeUs(): Long

    /** Goodbye reason ordinals, matching sendspin::SendspinGoodbyeReason. */
    object GoodbyeReason {
        const val ANOTHER_SERVER = 0
        const val SHUTDOWN = 1
        const val RESTART = 2
        const val USER_REQUEST = 3
    }
}
