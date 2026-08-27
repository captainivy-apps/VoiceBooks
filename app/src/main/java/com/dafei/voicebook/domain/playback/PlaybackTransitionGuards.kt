package com.dafei.voicebook.domain.playback

object PlaybackTransitionGuards {
    fun shouldSkipPauseFinalize(
        isPlaying: Boolean,
        pauseSessionAtStart: Int,
        currentSession: Int,
    ): Boolean {
        if (isPlaying) return true
        if (currentSession != pauseSessionAtStart) return true
        return false
    }
}
