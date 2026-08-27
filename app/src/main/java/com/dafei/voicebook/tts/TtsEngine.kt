package com.dafei.voicebook.tts

import kotlin.math.abs
import kotlin.math.sqrt

/** Shared playback loudness profile for Sherpa PCM output. */
object TtsAudioProfile {
    const val TARGET_RMS = 0.12f
    const val MAX_PEAK = 0.95f
    const val MIN_GAIN = 0.25f
    const val MAX_GAIN = 4f
}

/**
 * Normalize synthesized speech to a consistent perceived level.
 * Uses RMS targeting with peak limiting so consecutive sentences stay even.
 */
fun normalizeForPlayback(
    samples: FloatArray,
    targetRms: Float = TtsAudioProfile.TARGET_RMS,
    maxPeak: Float = TtsAudioProfile.MAX_PEAK,
): FloatArray {
    if (samples.isEmpty()) return samples

    var sumSquares = 0.0
    for (sample in samples) {
        sumSquares += (sample * sample).toDouble()
    }
    val rms = sqrt(sumSquares / samples.size).toFloat()
    if (rms < 1e-6f) return samples

    var gain = targetRms / rms
    var peak = 0f
    for (sample in samples) {
        peak = maxOf(peak, abs(sample * gain))
    }
    if (peak > maxPeak) {
        gain *= maxPeak / peak
    }
    if (abs(gain - 1f) < 0.02f) return samples

    gain = gain.coerceIn(TtsAudioProfile.MIN_GAIN, TtsAudioProfile.MAX_GAIN)
    for (index in samples.indices) {
        samples[index] = (samples[index] * gain).coerceIn(-1f, 1f)
    }
    return samples
}

/** @deprecated Use [normalizeForPlayback] for sentence playback. */
fun normalizePeakAmplitude(samples: FloatArray, targetPeak: Float = TtsAudioProfile.MAX_PEAK): FloatArray {
    return normalizeForPlayback(samples, targetRms = TtsAudioProfile.TARGET_RMS, maxPeak = targetPeak)
}

data class StreamingSynthStats(
    val sampleRate: Int,
    val totalSamples: Int,
    val callbackInvocations: Int,
    val stoppedEarly: Boolean,
)

data class SynthesizedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
) {
    fun isPlayable(): Boolean = sampleRate > 0 && samples.isNotEmpty()
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SynthesizedAudio
        if (sampleRate != other.sampleRate) return false
        if (!samples.contentEquals(other.samples)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate
        return result
    }
}

data class TtsVoice(
    val id: String,
    val name: String,
    val language: String,
)

interface TtsEngine {
    val engineId: String
    val engineName: String
    fun isReady(): Boolean
    fun initialize(modelPath: String, modelType: String, speakerId: Int): Boolean
    fun getVoices(): List<TtsVoice>
    fun sampleRate(): Int
    fun resolveSpeakerId(speakerId: Int): Int
    fun synthesize(text: String, speed: Float = 1.0f, speakerId: Int = 0): SynthesizedAudio?
    fun synthesizeStreaming(
        text: String,
        speed: Float = 1.0f,
        speakerId: Int = 0,
        shouldContinue: () -> Boolean = { true },
        onChunk: (samples: FloatArray) -> Int,
    ): StreamingSynthStats?
    fun release()
}
