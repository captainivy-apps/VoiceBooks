package com.dafei.voicebook

import com.dafei.voicebook.tts.StreamingGain
import com.dafei.voicebook.tts.TtsAudioProfile
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class StreamingGainTest {
    @Test
    fun locksGainFromFirstChunk() {
        val gain = StreamingGain()
        val chunk1 = FloatArray(100) { 0.01f }
        val chunk2 = FloatArray(100) { 0.01f }

        gain.applyInPlace(chunk1)
        val rmsAfterFirst = rms(chunk1)
        gain.applyInPlace(chunk2)
        val rmsAfterSecond = rms(chunk2)

        assertTrue(rmsAfterFirst in 0.08f..0.16f)
        assertTrue(abs(rmsAfterFirst - rmsAfterSecond) < 0.02f)
        assertTrue(chunk1.maxOf { abs(it) } <= TtsAudioProfile.MAX_PEAK + 0.01f)
    }

    @Test
    fun resetAllowsNewEstimate() {
        val gain = StreamingGain()
        val quiet = FloatArray(50) { 0.005f }
        val loud = FloatArray(50) { 0.2f }

        gain.applyInPlace(quiet)
        val quietRms = rms(quiet)

        gain.reset()
        gain.applyInPlace(loud)
        val loudRms = rms(loud)

        assertTrue(loudRms > quietRms)
        assertTrue(loudRms in 0.08f..0.16f)
    }

    private fun rms(samples: FloatArray): Float {
        var sum = 0.0
        for (sample in samples) sum += sample * sample
        return sqrt(sum / samples.size).toFloat()
    }
}
