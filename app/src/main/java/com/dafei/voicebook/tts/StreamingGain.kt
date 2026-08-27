package com.dafei.voicebook.tts

import kotlin.math.abs
import kotlin.math.sqrt

/** Sentence-level streaming gain: lock RMS estimate from the first callback chunk. */
class StreamingGain {
    private var lockedGain: Float? = null

    fun reset() {
        lockedGain = null
    }

    fun applyInPlace(samples: FloatArray) {
        if (samples.isEmpty()) return
        val gain = lockedGain ?: estimateGain(samples).also { lockedGain = it }
        if (abs(gain - 1f) < 0.02f) return
        for (index in samples.indices) {
            samples[index] = (samples[index] * gain).coerceIn(-1f, 1f)
        }
    }

    private fun estimateGain(samples: FloatArray): Float {
        var sumSquares = 0.0
        for (sample in samples) {
            sumSquares += (sample * sample).toDouble()
        }
        val rms = sqrt(sumSquares / samples.size).toFloat()
        if (rms < 1e-6f) return 1f

        var gain = TtsAudioProfile.TARGET_RMS / rms
        var peak = 0f
        for (sample in samples) {
            peak = maxOf(peak, abs(sample * gain))
        }
        if (peak > TtsAudioProfile.MAX_PEAK) {
            gain *= TtsAudioProfile.MAX_PEAK / peak
        }
        return gain.coerceIn(TtsAudioProfile.MIN_GAIN, TtsAudioProfile.MAX_GAIN)
    }
}
