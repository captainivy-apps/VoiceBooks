package com.dafei.voicebook.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/** MODE_STREAM AudioTrack helper. All public methods are synchronized for cross-thread use. */
class PcmStreamPlayer {
    private val lock = Any()
    private var track: AudioTrack? = null
    private var trackSampleRate: Int = 0
    private var framesWrittenInternal: Long = 0L

    @Volatile
    private var stopRequested = false

    val framesWritten: Long
        get() = synchronized(lock) { framesWrittenInternal }

    val isInitialized: Boolean
        get() = synchronized(lock) { track?.state == AudioTrack.STATE_INITIALIZED }

    val sampleRate: Int
        get() = synchronized(lock) { trackSampleRate }

    fun canAppendContinuous(sampleRate: Int): Boolean = synchronized(lock) {
        val current = track ?: return false
        current.state == AudioTrack.STATE_INITIALIZED &&
            trackSampleRate == sampleRate &&
            current.playState == AudioTrack.PLAYSTATE_PLAYING
    }

    fun prepare(sampleRate: Int, appendContinuous: Boolean = false): Boolean = synchronized(lock) {
        if (sampleRate <= 0) return false
        stopRequested = false
        if (appendContinuous && canAppendContinuousLocked(sampleRate)) {
            return true
        }

        val existing = track
        if (existing != null && trackSampleRate == sampleRate && prepareTrackForNewStream(existing)) {
            return true
        }

        existing?.release()
        trackSampleRate = sampleRate
        framesWrittenInternal = 0L

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.e(TAG, "Unsupported sample rate: $sampleRate")
            return false
        }

        val built = buildStreamTrack(sampleRate, minBuffer * 4)
            ?: buildStreamTrack(sampleRate, minBuffer)
        if (built == null) {
            Log.e(TAG, "AudioTrack init failed sampleRate=$sampleRate")
            return false
        }
        track = built
        true
    }

    fun ensurePlaying() {
        val current = synchronized(lock) { track } ?: return
        try {
            current.setVolume(1.0f)
            if (current.playState != AudioTrack.PLAYSTATE_PLAYING) {
                current.play()
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "ensurePlaying skipped", e)
        }
    }

    /**
     * Write float PCM samples. Returns frames written, or negative on error.
     * Blocks until all samples are accepted by AudioTrack or [shouldContinue] returns false.
     */
    fun writeSamples(samples: FloatArray, shouldContinue: () -> Boolean = { true }): Int {
        if (samples.isEmpty()) return 0

        val pcm = floatToPcm16(samples)
        var offset = 0
        var zeroWriteStreak = 0
        while (offset < pcm.size) {
            if (stopRequested || !shouldContinue()) return offset

            val current = synchronized(lock) {
                if (stopRequested) return offset
                track
            } ?: return -1

            if (current.playState == AudioTrack.PLAYSTATE_PAUSED) {
                ensurePlaying()
            }

            val written = try {
                current.write(pcm, offset, pcm.size - offset)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "AudioTrack.write exception", e)
                return -1
            }
            when {
                written < 0 -> {
                    Log.e(TAG, "AudioTrack.write failed: $written")
                    return written
                }
                written == 0 -> {
                    zeroWriteStreak++
                    if (zeroWriteStreak >= MAX_ZERO_WRITE_STREAK) {
                        Log.e(
                            GAP_DEBUG_TAG,
                            "audiotrack_write_stall zeroWrites=$zeroWriteStreak offset=$offset pcmSize=${pcm.size}",
                        )
                        return -1
                    }
                    Thread.sleep(10)
                }
                else -> {
                    zeroWriteStreak = 0
                    offset += written
                }
            }
        }
        synchronized(lock) { framesWrittenInternal += samples.size }
        return samples.size
    }

    fun writeSamplesFromOffset(
        samples: FloatArray,
        startSample: Int,
        shouldContinue: () -> Boolean = { true },
    ): Int {
        if (startSample <= 0) return writeSamples(samples, shouldContinue)
        if (startSample >= samples.size) return 0
        return writeSamples(samples.copyOfRange(startSample, samples.size), shouldContinue)
    }

    fun playbackHeadFrames(): Long = synchronized(lock) {
        track?.playbackHeadPosition?.toLong() ?: 0L
    }

    /** Hardware mute without flush; safe to call while another thread is writing. */
    fun pauseImmediately() {
        val current = synchronized(lock) { track } ?: return
        if (current.state == AudioTrack.STATE_UNINITIALIZED) return
        try {
            current.setVolume(0f)
            if (current.playState == AudioTrack.PLAYSTATE_PLAYING) {
                current.pause()
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "pauseImmediately skipped", e)
        }
    }

    fun pause() = pauseImmediately()

    fun signalStopRequested() {
        stopRequested = true
    }

    /** Signal writers to exit, then stop and flush on the calling thread (audio control thread). */
    fun stopForPauseBlocking() {
        stopRequested = true
        synchronized(lock) {
            stopAndFlushLocked()
            stopRequested = false
        }
    }

    fun stopForPause() = stopForPauseBlocking()

    fun stopAndFlush() {
        stopRequested = true
        synchronized(lock) {
            stopAndFlushLocked()
            stopRequested = false
        }
    }

    fun release() {
        stopRequested = true
        synchronized(lock) {
            val current = track ?: return@synchronized
            track = null
            trackSampleRate = 0
            framesWrittenInternal = 0L
            try {
                if (current.state != AudioTrack.STATE_UNINITIALIZED &&
                    current.playState == AudioTrack.PLAYSTATE_PLAYING
                ) {
                    current.stop()
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "stop before release skipped", e)
            }
            try {
                current.release()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "release skipped", e)
            }
            stopRequested = false
        }
    }

    private fun stopAndFlushLocked() {
        val current = track ?: return
        if (current.state == AudioTrack.STATE_UNINITIALIZED) return
        try {
            when (current.playState) {
                AudioTrack.PLAYSTATE_PLAYING, AudioTrack.PLAYSTATE_PAUSED -> {
                    current.stop()
                    current.flush()
                }
            }
            framesWrittenInternal = 0L
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stopAndFlush skipped", e)
        }
    }

    private fun canAppendContinuousLocked(sampleRate: Int): Boolean {
        val current = track ?: return false
        return current.state == AudioTrack.STATE_INITIALIZED &&
            trackSampleRate == sampleRate &&
            current.playState == AudioTrack.PLAYSTATE_PLAYING
    }

    private fun prepareTrackForNewStream(current: AudioTrack): Boolean {
        if (current.state == AudioTrack.STATE_UNINITIALIZED) return false
        return try {
            when (current.playState) {
                AudioTrack.PLAYSTATE_PLAYING, AudioTrack.PLAYSTATE_PAUSED -> {
                    current.stop()
                    current.flush()
                }
            }
            framesWrittenInternal = 0L
            current.state == AudioTrack.STATE_INITIALIZED
        } catch (e: IllegalStateException) {
            Log.w(TAG, "prepareTrackForNewStream failed", e)
            false
        }
    }

    private fun buildStreamTrack(sampleRate: Int, bufferSize: Int): AudioTrack? {
        val built = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (built.state != AudioTrack.STATE_INITIALIZED) {
            built.release()
            return null
        }
        return built
    }

    private fun floatToPcm16(samples: FloatArray): ShortArray {
        return ShortArray(samples.size) { index ->
            val scaled = (samples[index].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
            scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    companion object {
        private const val TAG = "PcmStreamPlayer"
        private const val GAP_DEBUG_TAG = "TtsGapDebug"
        private const val MAX_ZERO_WRITE_STREAK = 100
    }
}
