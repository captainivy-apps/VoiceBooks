package com.dafei.voicebook

import com.dafei.voicebook.tts.StreamingGain
import com.dafei.voicebook.tts.TtsAudioProfile
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class StreamingGainTest {
    @Test
    fun locksGainAfterWarmUpWindow() {
        val gain = StreamingGain()
        val chunk1 = FloatArray(5000) { 0.05f }
        val chunk2 = FloatArray(5000) { 0.05f }

        gain.applyInPlace(chunk1)
        val rmsAfterFirst = rms(chunk1)
        gain.applyInPlace(chunk2)
        val rmsAfterSecond = rms(chunk2)

        assertTrue(rmsAfterFirst in 0.08f..0.16f)
        assertTrue(abs(rmsAfterFirst - rmsAfterSecond) < 0.02f)
        assertTrue(chunk1.maxOf { abs(it) } <= TtsAudioProfile.MAX_PEAK + 0.01f)
    }

    @Test
    fun ignoresSilentLeadIn() {
        val gain = StreamingGain()
        val silent = FloatArray(5000) { 0f }
        val speech = FloatArray(5000) { 0.03f }

        gain.applyInPlace(silent)
        assertTrue(silent.all { it == 0f })

        gain.applyInPlace(speech)
        val speechRms = rms(speech)
        assertTrue(speechRms in 0.08f..0.16f)
    }

    @Test
    fun resetAllowsNewEstimate() {
        val gain = StreamingGain()
        val quiet = FloatArray(5000) { 0.03f }
        val loud = FloatArray(5000) { 0.2f }

        gain.applyInPlace(quiet)

        gain.reset()
        gain.applyInPlace(loud)
        val loudRms = rms(loud)

        assertTrue(loudRms in 0.08f..0.16f)
        assertTrue(loud.maxOf { abs(it) } <= TtsAudioProfile.MAX_PEAK + 0.01f)
    }

    private fun rms(samples: FloatArray): Float {
        var sum = 0.0
        for (sample in samples) sum += sample * sample
        return sqrt(sum / samples.size).toFloat()
    }
}
