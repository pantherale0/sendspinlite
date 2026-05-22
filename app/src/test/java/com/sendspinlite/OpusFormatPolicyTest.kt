package com.sendspinlite

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OpusFormatPolicyTest {
    @Test
    fun isAdvertisedOpusStream_acceptsHelloAllowlist() {
        assertThat(OpusFormatPolicy.isAdvertisedOpusStream(48_000, 2, 16)).isTrue()
        assertThat(OpusFormatPolicy.isAdvertisedOpusStream(44_100, 2, 16)).isTrue()
    }

    @Test
    fun isAdvertisedOpusStream_rejectsUnsupportedParameters() {
        assertThat(OpusFormatPolicy.isAdvertisedOpusStream(48_000, 256, 16)).isFalse()
        assertThat(OpusFormatPolicy.isAdvertisedOpusStream(96_000, 2, 16)).isFalse()
        assertThat(OpusFormatPolicy.isAdvertisedOpusStream(48_000, 2, 24)).isFalse()
        assertThat(OpusFormatPolicy.isAdvertisedOpusStream(-1, 2, 16)).isFalse()
    }

    @Test
    fun isAcceptableIngressPacket_enforcesOpusMaxSize() {
        assertThat(OpusFormatPolicy.isAcceptableIngressPacket(1)).isTrue()
        assertThat(OpusFormatPolicy.isAcceptableIngressPacket(OpusFormatPolicy.MAX_PACKET_BYTES)).isTrue()
        assertThat(OpusFormatPolicy.isAcceptableIngressPacket(0)).isFalse()
        assertThat(OpusFormatPolicy.isAcceptableIngressPacket(OpusFormatPolicy.MAX_PACKET_BYTES + 1)).isFalse()
    }

    @Test
    fun maxType4FrameBytes_includesHeaderAndPayloadCap() {
        assertThat(OpusFormatPolicy.maxType4FrameBytes())
            .isEqualTo(OpusFormatPolicy.MAX_PACKET_BYTES + 9)
    }
}
