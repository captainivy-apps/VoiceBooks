package com.dafei.voicebook

import com.dafei.voicebook.tts.TtsAudioProfile
import com.dafei.voicebook.tts.normalizeChunksForPlayback
import com.dafei.voicebook.tts.normalizeForPlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class AudioNormalizationTest {
    @Test
    fun quietSamplesAreAmplifiedToTargetRms() {
        val quiet = floatArrayOf(0.01f, -0.02f, 0.015f)
        val normalized = normalizeForPlayback(quiet)

        val rms = rmsOf(normalized)
        assertEquals(TtsAudioProfile.TARGET_RMS, rms, 0.02f)
    }

    @Test
    fun loudSamplesAreAttenuatedToTargetRms() {
        val loud = floatArrayOf(0.8f, -1.0f, 0.6f)
        val normalized = normalizeForPlayback(loud)

        val rms = rmsOf(normalized)
        assertEquals(TtsAudioProfile.TARGET_RMS, rms, 0.02f)
        assertTrue(normalized.maxOf { abs(it) } <= TtsAudioProfile.MAX_PEAK + 0.01f)
    }

    @Test
    fun alreadyNormalizedSamplesAreUnchanged() {
        val balanced = floatArrayOf(0.12f, -0.12f, 0.12f, -0.12f)
        val normalized = normalizeForPlayback(balanced)

        assertTrue(balanced.contentEquals(normalized))
    }

    @Test
    fun chunkListIsNormalizedWithOneSharedGain() {
        val first = FloatArray(3000) { 0.05f }
        val second = FloatArray(3000) { 0.05f }
        val chunks = listOf(first, second)

        normalizeChunksForPlayback(chunks)

        val combined = first + second
        assertEquals(TtsAudioProfile.TARGET_RMS, rmsOf(combined), 0.02f)
        assertTrue(combined.maxOf { abs(it) } <= TtsAudioProfile.MAX_PEAK + 0.01f)
    }

    private fun rmsOf(samples: FloatArray): Float {
        var sumSquares = 0.0
        for (sample in samples) {
            sumSquares += (sample * sample).toDouble()
        }
        return sqrt(sumSquares / samples.size).toFloat()
    }
}
