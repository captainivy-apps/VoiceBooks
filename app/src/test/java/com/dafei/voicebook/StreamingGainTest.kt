package com.dafei.voicebook

import com.dafei.voicebook.tts.StreamingGain
import com.dafei.voicebook.tts.TtsAudioProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class StreamingGainTest {
    @Test
    fun onsetIsBufferedUntilGainLocks() {
        val gain = StreamingGain()
        val chunk1 = FloatArray(2000) { 0.05f }
        val chunk2 = FloatArray(2000) { 0.05f }
        val chunk3 = FloatArray(2000) { 0.05f }

        assertTrue(gain.process(chunk1).isEmpty())
        assertTrue(gain.process(chunk2).isEmpty())
        assertTrue(chunk1.all { it == 0.05f })

        val out = gain.process(chunk3)
        assertEquals(6000, out.size)
        assertEquals(TtsAudioProfile.TARGET_RMS, rms(out), 0.02f)
        assertTrue(out.maxOf { abs(it) } <= TtsAudioProfile.MAX_PEAK + 0.01f)
        assertTrue(gain.flush().isEmpty())
    }

    @Test
    fun locksAndAmplifiesWithinOneChunk() {
        val gain = StreamingGain()
        val chunk = FloatArray(5000) { 0.05f }

        val out = gain.process(chunk)
        assertEquals(5000, out.size)
        assertEquals(TtsAudioProfile.TARGET_RMS, rms(out), 0.02f)
        assertTrue(out.maxOf { abs(it) } <= TtsAudioProfile.MAX_PEAK + 0.01f)
    }

    @Test
    fun flushNormalizesShortSentence() {
        val gain = StreamingGain()
        val chunk = FloatArray(1000) { 0.03f }

        assertTrue(gain.process(chunk).isEmpty())
        val out = gain.flush()
        assertEquals(1000, out.size)
        assertEquals(TtsAudioProfile.TARGET_RMS, rms(out), 0.02f)
    }

    @Test
    fun flushIsEmptyWhenNothingBuffered() {
        assertTrue(StreamingGain().flush().isEmpty())
    }

    @Test
    fun silentLeadInIsBufferedThenAmplifiedWithSpeech() {
        val gain = StreamingGain()
        val silent = FloatArray(5000) { 0f }
        val speech = FloatArray(5000) { 0.03f }

        assertTrue(gain.process(silent).isEmpty())

        val out = gain.process(speech)
        assertEquals(10000, out.size)
        assertTrue(out.copyOfRange(0, 5000).all { it == 0f })
        assertEquals(TtsAudioProfile.TARGET_RMS, rms(out.copyOfRange(5000, 10000)), 0.02f)
    }

    @Test
    fun resetAllowsNewEstimate() {
        val gain = StreamingGain()
        gain.process(FloatArray(5000) { 0.03f })

        gain.reset()
        val out = gain.process(FloatArray(5000) { 0.2f })

        assertEquals(TtsAudioProfile.TARGET_RMS, rms(out), 0.02f)
        assertTrue(out.maxOf { abs(it) } <= TtsAudioProfile.MAX_PEAK + 0.01f)
    }

    private fun rms(samples: FloatArray): Float {
        var sum = 0.0
        for (sample in samples) sum += sample * sample
        return sqrt(sum / samples.size).toFloat()
    }
}
