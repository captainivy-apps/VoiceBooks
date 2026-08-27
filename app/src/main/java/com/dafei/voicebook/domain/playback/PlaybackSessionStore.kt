package com.dafei.voicebook.domain.playback

import android.content.Context

/** Persists the active playback session so [TtsPlaybackService] can resume after a process restart. */
class PlaybackSessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Session(
        val bookId: String,
        val shouldResumeOnRestart: Boolean,
    )

    fun save(bookId: String, shouldResumeOnRestart: Boolean) {
        if (bookId.isBlank()) return
        prefs.edit()
            .putString(KEY_BOOK_ID, bookId)
            .putBoolean(KEY_SHOULD_RESUME, shouldResumeOnRestart)
            .apply()
    }

    fun load(): Session? {
        val bookId = prefs.getString(KEY_BOOK_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        return Session(
            bookId = bookId,
            shouldResumeOnRestart = prefs.getBoolean(KEY_SHOULD_RESUME, false),
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "playback_session"
        private const val KEY_BOOK_ID = "book_id"
        private const val KEY_SHOULD_RESUME = "should_resume"
    }
}
