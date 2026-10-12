package com.dafei.voicebook.tts

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Sentence-level streaming gain with a short look-ahead window.
 *
 * The gain is locked from a warm-up window of real speech instead of the very first callback
 * chunk. Locking on the first chunk made loudness depend on that chunk's content: a silent or
 * quiet onset produced an oversized gain that clipped the rest of the sentence, so consecutive
 * sentences alternated loud/soft.
 *
 * Instead of passing the warm-up through untouched (which left the first few characters of every
 * sentence at the raw, quiet TTS level), the warm-up samples are held back until the gain locks
 * and are then written with the same gain as the rest of the sentence. This keeps loudness even
 * from the very first sample at the cost of a short start-up holdback (one warm-up window).
 */
class StreamingGain {
    private var lockedGain: Float? = null
    private var sumSquares = 0.0
    private var speechSampleCount = 0
    private var bufferedCount = 0
    private val pending = ArrayList<FloatArray>()

    fun reset() {
        lockedGain = null
        sumSquares = 0.0
        speechSampleCount = 0
        bufferedCount = 0
        pending.clear()
    }

    /**
     * Process [samples] in place and return the audio ready to write.
     *
     * While the warm-up window is still filling, this returns [EMPTY] (the samples are buffered,
     * not written). Once the gain locks, the buffered onset is merged with [samples] and returned
     * with the gain applied. Later calls return [samples] gain-applied.
     */
    fun process(samples: FloatArray): FloatArray {
        if (samples.isEmpty()) return samples

        val gain = lockedGain
        if (gain != null) {
            applyGain(samples, gain)
            return samples
        }

        buffer(samples)
        if (speechSampleCount < MIN_LOCK_SAMPLES) return EMPTY

        val locked = estimateGain()
        lockedGain = locked
        val merged = mergePending()
        applyGain(merged, locked)
        return merged
    }

    /**
     * Sentence end: apply the best available gain to anything still buffered and return it.
     * Short sentences that never reached the lock threshold are normalized from what they have.
     */
    fun flush(): FloatArray {
        if (pending.isEmpty()) return EMPTY
        val gain = lockedGain ?: estimateGain().also { lockedGain = it }
        val merged = mergePending()
        applyGain(merged, gain)
        return merged
    }

    private fun buffer(samples: FloatArray) {
        pending.add(samples.copyOf())
        bufferedCount += samples.size
        if (rmsOf(samples) >= SILENCE_RMS) {
            for (sample in samples) {
                sumSquares += (sample * sample).toDouble()
            }
            speechSampleCount += samples.size
        }
    }

    private fun mergePending(): FloatArray {
        val merged = FloatArray(bufferedCount)
        var offset = 0
        for (chunk in pending) {
            chunk.copyInto(merged, destinationOffset = offset)
            offset += chunk.size
        }
        pending.clear()
        bufferedCount = 0
        return merged
    }

    private fun estimateGain(): Float {
        if (speechSampleCount == 0) return 1f
        val rms = sqrt(sumSquares / speechSampleCount).toFloat()
        if (rms < 1e-6f) return 1f

        var gain = TtsAudioProfile.TARGET_RMS / rms
        var peak = 0f
        for (chunk in pending) {
            for (sample in chunk) {
                peak = maxOf(peak, abs(sample * gain))
            }
        }
        if (peak > TtsAudioProfile.MAX_PEAK) {
            gain *= TtsAudioProfile.MAX_PEAK / peak
        }
        return gain.coerceIn(TtsAudioProfile.MIN_GAIN, TtsAudioProfile.MAX_GAIN)
    }

    private fun applyGain(samples: FloatArray, gain: Float) {
        if (abs(gain - 1f) < 0.02f) return
        for (index in samples.indices) {
            samples[index] = (samples[index] * gain).coerceIn(-1f, 1f)
        }
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

        private val EMPTY = FloatArray(0)
    }
}
