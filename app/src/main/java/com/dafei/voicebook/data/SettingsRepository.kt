package com.dafei.voicebook.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dafei.voicebook.data.model.SortBy
import com.dafei.voicebook.data.model.SortOrder
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

data class UserSettings(
    val keepScreenOn: Boolean = true,
    val defaultTtsEngineId: String = "",
    val defaultVoiceId: String = "",
    val sortBy: SortBy = SortBy.LAST_PLAYED,
    val sortOrder: SortOrder = SortOrder.DESC,
    val playbackSpeed: Float = 1.0f,
    val sleepTimerMinutes: Int = 0,
    val sleepUntilEndOfBook: Boolean = false,
    val ttsPreviewText: String = "",
    val mirrorBaseUrl: String = "",
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val DEFAULT_TTS_ENGINE = stringPreferencesKey("default_tts_engine")
        val DEFAULT_VOICE = stringPreferencesKey("default_voice")
        val SORT_BY = stringPreferencesKey("sort_by")
        val SORT_ORDER = stringPreferencesKey("sort_order")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val SLEEP_TIMER_MINUTES = intPreferencesKey("sleep_timer_minutes")
        val SLEEP_UNTIL_END = booleanPreferencesKey("sleep_until_end")
        val TTS_PREVIEW_TEXT = stringPreferencesKey("tts_preview_text")
        val MIRROR_BASE_URL = stringPreferencesKey("mirror_base_url")
    }

    val settings: Flow<UserSettings> = context.dataStore.data
        .catch { throwable ->
            // DataStore file may be temporarily unreadable/corrupted; fall back to defaults.
            if (throwable is IOException) {
                emit(androidx.datastore.preferences.core.emptyPreferences())
            } else {
                throw throwable
            }
        }
        .map { prefs ->
            UserSettings(
                keepScreenOn = prefs[Keys.KEEP_SCREEN_ON] ?: true,
                defaultTtsEngineId = prefs[Keys.DEFAULT_TTS_ENGINE] ?: "",
                defaultVoiceId = prefs[Keys.DEFAULT_VOICE] ?: "",
                sortBy = parseSortBy(prefs[Keys.SORT_BY]),
                sortOrder = parseSortOrder(prefs[Keys.SORT_ORDER]),
                playbackSpeed = prefs[Keys.PLAYBACK_SPEED] ?: 1.0f,
                sleepTimerMinutes = prefs[Keys.SLEEP_TIMER_MINUTES] ?: 0,
                sleepUntilEndOfBook = prefs[Keys.SLEEP_UNTIL_END] ?: false,
                ttsPreviewText = prefs[Keys.TTS_PREVIEW_TEXT] ?: "",
                mirrorBaseUrl = prefs[Keys.MIRROR_BASE_URL] ?: "",
            )
        }

    private fun parseSortBy(raw: String?): SortBy {
        return SortBy.entries.firstOrNull { it.name == raw } ?: SortBy.LAST_PLAYED
    }

    private fun parseSortOrder(raw: String?): SortOrder {
        return SortOrder.entries.firstOrNull { it.name == raw } ?: SortOrder.DESC
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        context.dataStore.edit { it[Keys.KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setDefaultTtsEngine(id: String) {
        context.dataStore.edit { it[Keys.DEFAULT_TTS_ENGINE] = id }
    }

    suspend fun setDefaultVoice(id: String) {
        context.dataStore.edit { it[Keys.DEFAULT_VOICE] = id }
    }

    suspend fun setSort(sortBy: SortBy, sortOrder: SortOrder) {
        context.dataStore.edit {
            it[Keys.SORT_BY] = sortBy.name
            it[Keys.SORT_ORDER] = sortOrder.name
        }
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        context.dataStore.edit { it[Keys.PLAYBACK_SPEED] = speed }
    }

    suspend fun getPlaybackSpeed(): Float = settings.first().playbackSpeed

    suspend fun setSleepTimer(minutes: Int, untilEndOfBook: Boolean) {
        context.dataStore.edit {
            it[Keys.SLEEP_TIMER_MINUTES] = minutes
            it[Keys.SLEEP_UNTIL_END] = untilEndOfBook
        }
    }

    suspend fun clearSleepTimer() {
        setSleepTimer(0, false)
    }

    suspend fun setTtsPreviewText(text: String) {
        context.dataStore.edit { it[Keys.TTS_PREVIEW_TEXT] = text }
    }

    suspend fun setMirrorBaseUrl(url: String) {
        context.dataStore.edit { it[Keys.MIRROR_BASE_URL] = url.trim() }
    }
}
