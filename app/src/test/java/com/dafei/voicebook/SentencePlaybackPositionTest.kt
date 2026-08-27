package com.dafei.voicebook

import com.dafei.voicebook.domain.playback.SentencePlaybackPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentencePlaybackPositionTest {
    @Test
    fun isSentencePositionAtEnd_falseWhenPositionZero() {
        assertFalse(SentencePlaybackPosition.isSentencePositionAtEnd(0, 5000))
    }

    @Test
    fun isSentencePositionAtEnd_falseWhenDurationZero() {
        assertFalse(SentencePlaybackPosition.isSentencePositionAtEnd(1000, 0))
    }

    @Test
    fun isSentencePositionAtEnd_falseWhenMidSentence() {
        assertFalse(SentencePlaybackPosition.isSentencePositionAtEnd(2500, 5000))
    }

    @Test
    fun isSentencePositionAtEnd_trueAtExactEnd() {
        assertTrue(SentencePlaybackPosition.isSentencePositionAtEnd(5000, 5000))
    }

    @Test
    fun isSentencePositionAtEnd_trueWithinTolerance() {
        assertTrue(SentencePlaybackPosition.isSentencePositionAtEnd(4950, 5000))
    }

    @Test
    fun isSentencePositionAtEnd_falseJustOutsideTolerance() {
        assertFalse(
            SentencePlaybackPosition.isSentencePositionAtEnd(
                4849,
                5000,
                toleranceMs = 100,
            ),
        )
    }

    @Test
    fun isSentencePositionAtEnd_trueWhenPositionPastDuration() {
        assertTrue(SentencePlaybackPosition.isSentencePositionAtEnd(5200, 5000))
    }

    @Test
    fun sanitizePositionForSentence_keepsMidSentencePosition() {
        assertEquals(
            2300L,
            SentencePlaybackPosition.sanitizePositionForSentence(
                positionMs = 2300,
                durationMs = 5000,
            ),
        )
    }

    @Test
    fun sanitizePositionForSentence_keepsNearEndPosition() {
        assertEquals(
            4950L,
            SentencePlaybackPosition.sanitizePositionForSentence(
                positionMs = 4950,
                durationMs = 5000,
            ),
        )
    }

    @Test
    fun sanitizePositionForSentence_resetsCrossSentenceCorruption() {
        assertEquals(
            0L,
            SentencePlaybackPosition.sanitizePositionForSentence(
                positionMs = 6200,
                durationMs = 5000,
                toleranceMs = 100,
            ),
        )
    }

    @Test
    fun sanitizePositionForSentence_keepsSlightlyOverDurationWithinTolerance() {
        assertEquals(
            5050L,
            SentencePlaybackPosition.sanitizePositionForSentence(
                positionMs = 5050,
                durationMs = 5000,
                toleranceMs = 100,
            ),
        )
    }

    @Test
    fun sanitizePositionForSentence_keepsUnknownDuration() {
        assertEquals(
            1800L,
            SentencePlaybackPosition.sanitizePositionForSentence(
                positionMs = 1800,
                durationMs = null,
            ),
        )
    }
}
