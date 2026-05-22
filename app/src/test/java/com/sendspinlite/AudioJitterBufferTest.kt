package com.sendspinlite

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class AudioJitterBufferTest {
    private lateinit var buffer: AudioJitterBuffer

    @Before
    fun setUp() {
        buffer = AudioJitterBuffer(ClockSync.referenceFilter())
    }

    @Test
    fun offer_enforcesHardCapAndDropsFarthestFutureChunks() {
        val cap = AudioJitterBuffer.DEFAULT_MAX_BUFFER_CHUNKS
        repeat(cap + 100) { index ->
            buffer.offer(serverTsUs = index.toLong(), pcm = byteArrayOf(index.toByte()))
        }

        val snapshot = buffer.snapshot()

        assertThat(snapshot.queuedChunks).isEqualTo(cap)
        assertThat(snapshot.lateDrops).isEqualTo(100)
    }

    @Test
    fun dropUntilHeadAheadAtLeast_dropsUntilHeadReachesTargetAhead() {
        // Timestamps are microseconds; values must be large enough that (delta / 1000) is in ms.
        buffer.offer(serverTsUs = 1_000_000, pcm = byteArrayOf(1))
        buffer.offer(serverTsUs = 2_000_000, pcm = byteArrayOf(2))
        buffer.offer(serverTsUs = 3_000_000, pcm = byteArrayOf(3))
        buffer.offer(serverTsUs = 5_000_000, pcm = byteArrayOf(4))

        val dropped = buffer.dropUntilHeadAheadAtLeast(nowLocalUs = 5_200_000, minAheadMs = -250L)

        assertThat(dropped).isEqualTo(3)
        assertThat(buffer.snapshot().queuedChunks).isEqualTo(1)
        assertThat(buffer.snapshot().headServerUs).isEqualTo(5_000_000L)
    }

    @Test
    fun pollPlayable_dropsLateChunksBeforeReturningPlayableChunk() {
        buffer.offer(serverTsUs = 1_000, pcm = byteArrayOf(1))
        buffer.offer(serverTsUs = 2_000, pcm = byteArrayOf(2))
        buffer.offer(serverTsUs = 5_000, pcm = byteArrayOf(3))

        val playable = buffer.pollPlayable(nowLocalUs = 5_200, lateDropUs = 1_000)

        assertThat(playable).isNotNull()
        assertThat(playable!!.serverTimestampUs).isEqualTo(5_000)
        assertThat(buffer.snapshot().lateDrops).isEqualTo(2)
        assertThat(buffer.size()).isEqualTo(0)
    }
}
