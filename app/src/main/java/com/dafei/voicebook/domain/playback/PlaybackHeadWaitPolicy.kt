package com.dafei.voicebook.domain.playback

object PlaybackHeadWaitPolicy {
    const val MAX_TIMEOUT_MS = 60_000L
    const val MIN_TIMEOUT_MS = 500L
    const val SLACK_MS = 500L
    private const val RATIO = 1.5

    fun expectedTimeoutMs(framesToPlay: Long, sampleRate: Int): Long {
        if (framesToPlay <= 0 || sampleRate <= 0) return MIN_TIMEOUT_MS
        val audioMs = (framesToPlay * 1000) / sampleRate
        val scaled = (audioMs * RATIO).toLong() + SLACK_MS
        return scaled.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    }
}
