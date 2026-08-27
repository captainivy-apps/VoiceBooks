package com.dafei.voicebook.domain.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PlaybackState {
    IDLE, LOADING, PLAYING, PAUSED, STOPPED, ERROR
}

enum class TtsLoadingStage {
    NONE,
    LOADING_BOOK,
    INIT_ENGINE,
    INIT_SHERPA,
    READING_TEXT,
    SYNTHESIZING,
    OUTPUTTING_AUDIO,
}

data class PlaybackUiState(
    val bookId: String = "",
    val bookTitle: String = "",
    val bookAuthor: String = "",
    val coverPath: String? = null,
    val currentSentenceIndex: Int = 0,
    val totalSentences: Int = 0,
    val currentSentenceText: String = "",
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val loadingStage: TtsLoadingStage = TtsLoadingStage.NONE,
    val lastError: String? = null,
    val speed: Float = 1.0f,
    val positionInSentenceMs: Long = 0,
    val sleepTimerRemainingMs: Long = 0,
    val sleepUntilEndOfBook: Boolean = false,
)

object PlaybackController {
    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    /** When true, NavGraph will not auto-open the player (user explicitly left the player screen). */
    private var playerScreenDismissed = false

    @Volatile
    private var immediatePauseHandler: (() -> Unit)? = null

    @Volatile
    private var immediateResumeHandler: (() -> Unit)? = null

    fun setImmediatePauseHandler(handler: (() -> Unit)?) {
        immediatePauseHandler = handler
    }

    fun setImmediateResumeHandler(handler: (() -> Unit)?) {
        immediateResumeHandler = handler
    }

    /** Stop audio immediately in the running playback service (no Intent round-trip). */
    fun requestImmediatePause() {
        immediatePauseHandler?.invoke()
    }

    /** Start playback immediately in the running playback service (no Intent round-trip). */
    fun requestImmediateResume() {
        immediateResumeHandler?.invoke()
    }

    fun update(transform: (PlaybackUiState) -> PlaybackUiState) {
        _state.value = transform(_state.value)
    }

    fun reset() {
        _state.value = PlaybackUiState()
    }

    fun dismissPlayerScreen() {
        playerScreenDismissed = true
    }

    fun clearPlayerScreenDismissed() {
        playerScreenDismissed = false
    }

    fun isPlayerScreenDismissed(): Boolean = playerScreenDismissed
}
