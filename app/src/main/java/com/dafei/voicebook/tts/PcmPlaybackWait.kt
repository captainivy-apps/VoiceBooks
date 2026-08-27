package com.dafei.voicebook.tts

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.delay

/**
 * Wait until PCM has had time to play out through the speaker.
 *
 * Some devices report [AudioTrack.getPlaybackHeadPosition] as caught up immediately
 * after a large batch write; a minimum duration wait avoids cutting audio short.
 *
 * @return elapsed wait time in milliseconds
 */
suspend fun awaitPcmPlaybackComplete(
    player: PcmStreamPlayer,
    framesBeforeWrite: Long,
    framesEnd: Long,
    sampleRate: Int,
    shouldContinue: () -> Boolean,
    logTag: String = TAG,
): Long {
    if (framesEnd <= framesBeforeWrite || sampleRate <= 0) return 0L

    val frameCount = framesEnd - framesBeforeWrite
    val expectedMs = frameCount * 1000L / sampleRate
    val startMs = SystemClock.elapsedRealtime()
    val deadlineMs = startMs + expectedMs + TAIL_PADDING_MS
    val timeoutMs = deadlineMs + TIMEOUT_EXTRA_MS
    val headAtStart = player.playbackHeadFrames()

    while (shouldContinue()) {
        val now = SystemClock.elapsedRealtime()
        if (now >= timeoutMs) break

        val headFrames = player.playbackHeadFrames()
        val headCaughtUp = headFrames >= framesEnd
        if (headCaughtUp && now >= deadlineMs) break

        delay(POLL_INTERVAL_MS)
    }

    val waitedMs = SystemClock.elapsedRealtime() - startMs
    val headAtEnd = player.playbackHeadFrames()
    Log.d(
        logTag,
        "playback_wait framesBefore=$framesBeforeWrite framesEnd=$framesEnd " +
            "expectedMs=$expectedMs waitedMs=$waitedMs headAtStart=$headAtStart headAtEnd=$headAtEnd",
    )
    return waitedMs
}

private const val TAG = "PcmPlaybackWait"
private const val TAIL_PADDING_MS = 175L
private const val TIMEOUT_EXTRA_MS = 500L
private const val POLL_INTERVAL_MS = 20L
