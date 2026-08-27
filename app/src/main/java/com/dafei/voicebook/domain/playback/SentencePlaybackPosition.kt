package com.dafei.voicebook.domain.playback

object SentencePlaybackPosition {
    const val END_TOLERANCE_MS = 100L

    fun isSentencePositionAtEnd(
        positionMs: Long,
        durationMs: Long,
        toleranceMs: Long = END_TOLERANCE_MS,
    ): Boolean {
        if (positionMs <= 0 || durationMs <= 0) return false
        return positionMs >= durationMs - toleranceMs
    }

    fun sanitizePositionForSentence(
        positionMs: Long,
        durationMs: Long?,
        toleranceMs: Long = END_TOLERANCE_MS,
    ): Long {
        if (positionMs <= 0) return 0L
        val duration = durationMs ?: return positionMs
        if (duration <= 0) return 0L
        if (positionMs > duration + toleranceMs) return 0L
        return positionMs
    }
}
