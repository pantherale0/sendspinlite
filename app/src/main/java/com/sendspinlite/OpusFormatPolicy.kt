package com.sendspinlite

/**
 * Opus formats advertised in client/hello and enforced on stream/start and ingress.
 * Rejects server-proposed parameters outside this set before decoder allocation.
 */
object OpusFormatPolicy {
    /** RFC 6716 maximum Opus packet size for a single frame. */
    const val MAX_PACKET_BYTES = 1275

    const val REQUIRED_BIT_DEPTH = 16

    private const val TYPE4_HEADER_BYTES = 9
    private const val SAMPLE_RATE_44_1_KHZ = 44_100
    private const val SAMPLE_RATE_48_KHZ = 48_000
    private val ALLOWED_SAMPLE_RATES = setOf(SAMPLE_RATE_44_1_KHZ, SAMPLE_RATE_48_KHZ)
    private const val ALLOWED_CHANNELS = 2

    /** Matches [SendspinPcmClient.buildPlayerSupportObject] Opus entries. */
    fun isAdvertisedOpusStream(
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
    ): Boolean =
        sampleRate in ALLOWED_SAMPLE_RATES &&
            channels == ALLOWED_CHANNELS &&
            bitDepth == REQUIRED_BIT_DEPTH

    fun isAcceptableIngressPacket(encodedBytes: Int): Boolean = encodedBytes in 1..MAX_PACKET_BYTES

    fun maxType4FrameBytes(): Int = TYPE4_HEADER_BYTES + MAX_PACKET_BYTES
}
