package com.dafei.voicebook

import com.dafei.voicebook.domain.playback.PlaybackHeadWaitPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackHeadWaitPolicyTest {
    @Test
    fun expectedTimeoutMs_scalesWithDuration() {
        val timeout = PlaybackHeadWaitPolicy.expectedTimeoutMs(
            framesToPlay = 22_050,
            sampleRate = 22_050,
        )
        assertEquals(2_000L, timeout)
    }

    @Test
    fun expectedTimeoutMs_returnsMinimumForZeroFrames() {
        assertEquals(
            PlaybackHeadWaitPolicy.MIN_TIMEOUT_MS,
            PlaybackHeadWaitPolicy.expectedTimeoutMs(framesToPlay = 0, sampleRate = 22_050),
        )
    }

    @Test
    fun expectedTimeoutMs_capsAtMaximum() {
        val timeout = PlaybackHeadWaitPolicy.expectedTimeoutMs(
            framesToPlay = 10_000_000,
            sampleRate = 22_050,
        )
        assertEquals(PlaybackHeadWaitPolicy.MAX_TIMEOUT_MS, timeout)
    }

    @Test
    fun expectedTimeoutMs_longSentenceWithinCap() {
        val timeout = PlaybackHeadWaitPolicy.expectedTimeoutMs(
            framesToPlay = 154_350,
            sampleRate = 22_050,
        )
        assertTrue(timeout > 10_000L)
        assertTrue(timeout <= PlaybackHeadWaitPolicy.MAX_TIMEOUT_MS)
    }
}
