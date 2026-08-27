package com.dafei.voicebook.service

import android.content.Context
import android.os.PowerManager
import android.util.Log

/** Keeps the CPU awake during TTS synthesis and PCM playback (e.g. while the screen is off). */
class PlaybackWakeLock(context: Context) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    fun acquire() {
        if (wakeLock?.isHeld == true) return
        try {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG,
            ).apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_LOCK_MS)
            }
            Log.d(TAG, "acquired")
        } catch (e: SecurityException) {
            Log.w(TAG, "Failed to acquire wake lock", e)
        }
    }

    fun release() {
        val lock = wakeLock ?: return
        try {
            if (lock.isHeld) {
                lock.release()
                Log.d(TAG, "released")
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Failed to release wake lock", e)
        } finally {
            wakeLock = null
        }
    }

    companion object {
        private const val TAG = "PlaybackWakeLock"
        private const val WAKE_LOCK_TAG = "voicebook:tts_playback"
        /** Safety cap; playback releases the lock on pause/stop. */
        private const val MAX_WAKE_LOCK_MS = 12 * 60 * 60 * 1000L
    }
}
