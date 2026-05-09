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
    fun offer_enforcesHardCapAndDropsOldestChunks() {
        repeat(600) { index ->
            buffer.offer(serverTsUs = index.toLong(), pcm = byteArrayOf(index.toByte()))
        }

        val snapshot = buffer.snapshot()

        assertThat(snapshot.queuedChunks).isEqualTo(500)
        assertThat(snapshot.lateDrops).isEqualTo(100)
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
