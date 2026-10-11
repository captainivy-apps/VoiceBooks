package com.dafei.voicebook.tts

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Sentence-level streaming gain.
 *
 * The gain is locked from a short warm-up window of real speech instead of the very first
 * callback chunk. Locking on the first chunk made loudness depend on that chunk's content:
 * a silent or quiet onset (common at the start of MeloTTS utterances) produced an oversized
 * gain that clipped the rest of the sentence, so consecutive sentences alternated loud/soft.
 * Silent chunks are skipped and samples seen during warm-up pass through untouched.
 */
class StreamingGain {
    private var lockedGain: Float? = null
    private var sumSquares = 0.0
    private var sampleCount = 0

    fun reset() {
        lockedGain = null
        sumSquares = 0.0
        sampleCount = 0
    }

    fun applyInPlace(samples: FloatArray) {
        if (samples.isEmpty()) return
        val gain = lockedGain ?: warmUp(samples) ?: return
        if (abs(gain - 1f) < 0.02f) return
        for (index in samples.indices) {
            samples[index] = (samples[index] * gain).coerceIn(-1f, 1f)
        }
    }

    /** Accumulate real speech until the window is representative; null while still warming up. */
    private fun warmUp(samples: FloatArray): Float? {
        if (rmsOf(samples) < SILENCE_RMS) return null
        for (sample in samples) {
            sumSquares += (sample * sample).toDouble()
        }
        sampleCount += samples.size
        if (sampleCount < MIN_LOCK_SAMPLES) return null
        val rms = sqrt(sumSquares / sampleCount).toFloat()
        return estimateGain(rms, samples).also { lockedGain = it }
    }

    private fun estimateGain(rms: Float, samples: FloatArray): Float {
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

    private fun rmsOf(samples: FloatArray): Float {
        var sum = 0.0
        for (sample in samples) {
            sum += (sample * sample).toDouble()
        }
        return sqrt(sum / samples.size).toFloat()
    }

    companion object {
        /** About 0.09 s at 44.1 kHz / 0.18 s at 22.05 kHz: enough to average out a soft onset. */
        private const val MIN_LOCK_SAMPLES = 4096

        /** Ignore near-silent lead-in (about -50 dBFS). */
        private const val SILENCE_RMS = 0.003f
    }
}
