package com.dafei.voicebook

import com.dafei.voicebook.domain.playback.PlaybackTransitionGuards
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTransitionGuardsTest {
    @Test
    fun shouldSkipPauseFinalize_whenResumed() {
        assertTrue(
            PlaybackTransitionGuards.shouldSkipPauseFinalize(
                isPlaying = true,
                pauseSessionAtStart = 5,
                currentSession = 5,
            ),
        )
    }

    @Test
    fun shouldSkipPauseFinalize_whenSessionAdvanced() {
        assertTrue(
            PlaybackTransitionGuards.shouldSkipPauseFinalize(
                isPlaying = false,
                pauseSessionAtStart = 5,
                currentSession = 7,
            ),
        )
    }

    @Test
    fun shouldNotSkipPauseFinalize_whenStillPausedAtSameSession() {
        assertFalse(
            PlaybackTransitionGuards.shouldSkipPauseFinalize(
                isPlaying = false,
                pauseSessionAtStart = 5,
                currentSession = 5,
            ),
        )
    }
}
