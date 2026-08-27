package com.dafei.voicebook.ui.player

import android.app.Application
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dafei.voicebook.R
import com.dafei.voicebook.VoicebookApp
import com.dafei.voicebook.data.db.BookEntity
import com.dafei.voicebook.data.db.SentenceIndexEntity
import com.dafei.voicebook.data.model.BookFormat
import com.dafei.voicebook.data.UserSettings
import com.dafei.voicebook.domain.import.ImportErrorMessages
import com.dafei.voicebook.domain.playback.PlaybackController
import com.dafei.voicebook.domain.playback.PlaybackState
import com.dafei.voicebook.domain.playback.PlaybackUiState
import com.dafei.voicebook.domain.playback.TtsLoadingStage
import com.dafei.voicebook.service.TtsPlaybackService
import com.dafei.voicebook.ui.AppToast
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BookDisplayInfo(
    val title: String = "",
    val author: String = "",
    val coverPath: String? = null,
    val summary: String = "",
    val format: BookFormat = BookFormat.UNKNOWN,
    val sentenceCount: Int = 0,
    val uploadTime: Long = 0L,
    val lastPlayedTime: Long? = null,
    val sourceUrl: String? = null,
    val publisher: String = "",
    val publishedDate: String = "",
    val language: String = "",
    val isbn: String = "",
    val subjects: String = "",
)

data class PlayerUiState(
    val bookTitle: String = "",
    val bookAuthor: String = "",
    val coverPath: String? = null,
    val bookInfo: BookDisplayInfo = BookDisplayInfo(),
    val sentences: List<SentenceIndexEntity> = emptyList(),
    val currentSentenceIndex: Int = 0,
    val currentSentenceText: String = "",
    val totalSentences: Int = 0,
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val loadingStage: TtsLoadingStage = TtsLoadingStage.NONE,
    val lastError: String? = null,
    val speed: Float = 1.0f,
    val sleepTimerRemainingMs: Long = 0,
    val sleepUntilEndOfBook: Boolean = false,
    val sleepTimerMinutes: Int = 0,
    val keepScreenOn: Boolean = true,
    /** When true, show Pause icon; when false, show Play icon. */
    val isPlayingForUi: Boolean = false,
    val isRefreshingMetadata: Boolean = false,
    /** Target sentence index while a debounced seek is pending. */
    val pendingSeekIndex: Int? = null,
    val skipTransportLocked: Boolean = false,
    val canRewind: Boolean = true,
    val canForward: Boolean = true,
)

class PlayerViewModel(
    application: Application,
    private val bookId: String,
) : AndroidViewModel(application) {
    private val app = application as VoicebookApp
    private val _sentences = MutableStateFlow<List<SentenceIndexEntity>>(emptyList())
    private val _bookMeta = MutableStateFlow(BookDisplayInfo())
    private val _isRefreshingMetadata = MutableStateFlow(false)
    private val _userWantsPlaying = MutableStateFlow<Boolean?>(null)
    private val _pendingSeekIndex = MutableStateFlow<Int?>(null)
    private val _skipTransportLocked = MutableStateFlow(false)
    private var speedApplyJob: Job? = null
    private var seekJob: Job? = null
    private var seekConfirmJob: Job? = null
    private var seekInFlight = false
    private var pendingSeekWasPlaying: Boolean? = null
    private var activeSkipSeek = false

    val uiState: StateFlow<PlayerUiState> = combine(
        combine(
            PlaybackController.state,
            _sentences,
            _bookMeta,
            _pendingSeekIndex,
        ) { playback, sentences, meta, pendingSeekIndex ->
            SeekCombineInput(playback, sentences, meta, pendingSeekIndex)
        },
        combine(
            _isRefreshingMetadata,
            app.settingsRepository.settings,
            _userWantsPlaying,
            _skipTransportLocked,
        ) { isRefreshingMetadata, settings, userWantsPlaying, skipTransportLocked ->
            SettingsCombineInput(isRefreshingMetadata, settings, userWantsPlaying, skipTransportLocked)
        },
    ) { seekInput, settingsInput ->
        val playback = seekInput.playback
        val isPlayingForUi = when (settingsInput.userWantsPlaying) {
            true -> true
            false -> false
            null -> playback.playbackState == PlaybackState.PLAYING
        }
        val lastIndex = seekInput.sentences.lastIndex.coerceAtLeast(0)
        val effectiveIndex = seekInput.pendingSeekIndex ?: playback.currentSentenceIndex
        val skipLocked = settingsInput.skipTransportLocked
        PlayerUiState(
            bookTitle = seekInput.meta.title.ifBlank { playback.bookTitle },
            bookAuthor = seekInput.meta.author.ifBlank { playback.bookAuthor },
            coverPath = seekInput.meta.coverPath ?: playback.coverPath,
            bookInfo = seekInput.meta,
            sentences = seekInput.sentences,
            currentSentenceIndex = playback.currentSentenceIndex,
            currentSentenceText = playback.currentSentenceText,
            totalSentences = playback.totalSentences.coerceAtLeast(seekInput.sentences.size),
            playbackState = playback.playbackState,
            loadingStage = playback.loadingStage,
            lastError = playback.lastError,
            speed = settingsInput.settings.playbackSpeed,
            sleepTimerRemainingMs = playback.sleepTimerRemainingMs,
            sleepUntilEndOfBook = playback.sleepUntilEndOfBook,
            sleepTimerMinutes = settingsInput.settings.sleepTimerMinutes,
            keepScreenOn = settingsInput.settings.keepScreenOn,
            isPlayingForUi = isPlayingForUi,
            isRefreshingMetadata = settingsInput.isRefreshingMetadata,
            pendingSeekIndex = seekInput.pendingSeekIndex,
            skipTransportLocked = skipLocked,
            canRewind = !skipLocked && effectiveIndex > 0,
            canForward = !skipLocked && effectiveIndex < lastIndex,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlayerUiState())

    private data class SeekCombineInput(
        val playback: PlaybackUiState,
        val sentences: List<SentenceIndexEntity>,
        val meta: BookDisplayInfo,
        val pendingSeekIndex: Int?,
    )

    private data class SettingsCombineInput(
        val isRefreshingMetadata: Boolean,
        val settings: UserSettings,
        val userWantsPlaying: Boolean?,
        val skipTransportLocked: Boolean,
    )

    init {
        viewModelScope.launch {
            combine(PlaybackController.state, _userWantsPlaying) { playback, userWants ->
                playback.playbackState to userWants
            }.collect { (state, userWants) ->
                when {
                    userWants == false && state == PlaybackState.PAUSED -> {
                        _userWantsPlaying.value = null
                    }
                    userWants == true && state == PlaybackState.PLAYING -> {
                        _userWantsPlaying.value = null
                    }
                    state == PlaybackState.ERROR && _skipTransportLocked.value -> {
                        unlockSkipTransport()
                    }
                }
            }
        }
        viewModelScope.launch {
            val book = app.bookRepository.getBook(bookId)
            if (book != null) {
                loadBookMeta(book)
            }
            _sentences.value = app.bookRepository.getSentences(bookId)
            val playback = PlaybackController.state.value
            val alreadyActive = playback.bookId == bookId &&
                playback.playbackState != PlaybackState.IDLE &&
                playback.playbackState != PlaybackState.STOPPED
            if (!alreadyActive) {
                startPlayback()
            }
        }
    }

    fun refreshBookMetadata() {
        if (_isRefreshingMetadata.value) return
        viewModelScope.launch {
            _isRefreshingMetadata.value = true
            try {
                val book = app.bookRepository.refreshBookMetadata(bookId)
                loadBookMeta(book)
                val playback = PlaybackController.state.value
                if (playback.bookId == bookId) {
                    PlaybackController.update {
                        it.copy(
                            bookTitle = book.title,
                            bookAuthor = book.author,
                            coverPath = book.coverPath,
                        )
                    }
                }
                AppToast.show(
                    getApplication(),
                    getApplication<Application>().getString(R.string.metadata_refresh_success),
                    Toast.LENGTH_SHORT,
                )
            } catch (e: Exception) {
                val message = ImportErrorMessages.toUserMessage(e, getApplication())
                AppToast.show(
                    getApplication(),
                    getApplication<Application>().getString(R.string.metadata_refresh_failed, message),
                    Toast.LENGTH_LONG,
                )
            } finally {
                _isRefreshingMetadata.value = false
            }
        }
    }

    private fun loadBookMeta(book: BookEntity) {
        _bookMeta.value = BookDisplayInfo(
            title = book.title,
            author = book.author,
            coverPath = book.coverPath,
            summary = book.summary,
            format = book.format,
            sentenceCount = book.sentenceCount,
            uploadTime = book.uploadTime,
            lastPlayedTime = book.lastPlayedTime,
            sourceUrl = book.sourceUrl,
            publisher = book.publisher.orEmpty(),
            publishedDate = book.publishedDate.orEmpty(),
            language = book.language.orEmpty(),
            isbn = book.isbn.orEmpty(),
            subjects = book.subjects.orEmpty(),
        )
    }

    fun startPlayback() {
        val context = getApplication<Application>()
        ContextCompat.startForegroundService(context, TtsPlaybackService.playIntent(context, bookId))
    }

    fun togglePlayPause() {
        val playback = PlaybackController.state.value
        if (playback.bookId != bookId ||
            playback.playbackState == PlaybackState.IDLE ||
            playback.playbackState == PlaybackState.STOPPED ||
            playback.playbackState == PlaybackState.ERROR
        ) {
            startPlayback()
            return
        }
        if (_userWantsPlaying.value == false &&
            (playback.playbackState == PlaybackState.PLAYING ||
                playback.playbackState == PlaybackState.LOADING)
        ) {
            return
        }
        if (playback.playbackState == PlaybackState.PLAYING ||
            playback.playbackState == PlaybackState.LOADING
        ) {
            pausePlayback()
            return
        }
        if (playback.playbackState == PlaybackState.PAUSED) {
            resumePlayback()
        }
    }

    private fun pausePlayback() {
        val context = getApplication<Application>()
        _userWantsPlaying.value = false
        PlaybackController.update {
            it.copy(playbackState = PlaybackState.PAUSED, loadingStage = TtsLoadingStage.NONE)
        }
        if (TtsPlaybackService.isRunning()) {
            PlaybackController.requestImmediatePause()
        } else {
            ContextCompat.startForegroundService(
                context,
                TtsPlaybackService.controlIntent(context, TtsPlaybackService.ACTION_PAUSE),
            )
        }
    }

    private fun resumePlayback() {
        val context = getApplication<Application>()
        _userWantsPlaying.value = true
        if (TtsPlaybackService.isRunning()) {
            PlaybackController.requestImmediateResume()
        } else {
            ContextCompat.startForegroundService(
                context,
                TtsPlaybackService.controlIntent(context, TtsPlaybackService.ACTION_RESUME),
            )
        }
    }

    fun cancelPendingSeek() {
        seekJob?.cancel()
        seekJob = null
        seekConfirmJob?.cancel()
        seekConfirmJob = null
        _pendingSeekIndex.value = null
        pendingSeekWasPlaying = null
        seekInFlight = false
        unlockSkipTransport()
    }

    fun scheduleSeekToSentence(index: Int, showToast: Boolean = true, fromSkip: Boolean = false) {
        val lastIndex = _sentences.value.lastIndex.coerceAtLeast(0)
        val clampedIndex = index.coerceIn(0, lastIndex)
        val playback = PlaybackController.state.value
        if (clampedIndex == playback.currentSentenceIndex && _pendingSeekIndex.value == null) {
            return
        }

        if (fromSkip) {
            activeSkipSeek = true
        }

        seekJob?.cancel()
        _pendingSeekIndex.value = clampedIndex
        if (pendingSeekWasPlaying == null) {
            pendingSeekWasPlaying = captureWasPlaying()
        }
        applyOptimisticSeek(clampedIndex)

        if (showToast) {
            AppToast.show(
                getApplication(),
                getApplication<Application>().getString(R.string.tts_seek_scheduled),
                Toast.LENGTH_SHORT,
            )
        }

        scheduleSeekDebounced()
    }

    fun rewind() {
        if (_skipTransportLocked.value) return
        scheduleSeekBy(-SKIP_SENTENCE_COUNT)
    }

    fun forward() {
        if (_skipTransportLocked.value) return
        scheduleSeekBy(SKIP_SENTENCE_COUNT)
    }

    private fun scheduleSeekBy(offset: Int) {
        val target = accumulateSkipTarget(offset)
        val base = _pendingSeekIndex.value ?: PlaybackController.state.value.currentSentenceIndex
        if (target == base && _pendingSeekIndex.value == null) {
            return
        }
        scheduleSeekToSentence(target, showToast = false, fromSkip = true)
    }

    private fun accumulateSkipTarget(offset: Int): Int {
        val lastIndex = _sentences.value.lastIndex.coerceAtLeast(0)
        val base = _pendingSeekIndex.value ?: PlaybackController.state.value.currentSentenceIndex
        return (base + offset).coerceIn(0, lastIndex)
    }

    private fun scheduleSeekDebounced() {
        seekJob?.cancel()
        seekJob = viewModelScope.launch {
            delay(SEEK_DEBOUNCE_MS)
            val target = _pendingSeekIndex.value ?: return@launch
            flushSeekToService(target)
            seekJob = null
        }
    }

    private fun flushSeekToService(target: Int) {
        if (seekInFlight) {
            scheduleSeekDebounced()
            return
        }
        val wasPlaying = pendingSeekWasPlaying ?: captureWasPlaying()
        if (activeSkipSeek) {
            _skipTransportLocked.value = true
        }
        seekInFlight = true
        executeSeekOnService(target, wasPlaying)
        watchSeekConfirmation(target, wasPlaying)
    }

    private fun watchSeekConfirmation(target: Int, wasPlaying: Boolean) {
        seekConfirmJob?.cancel()
        val lockSkip = activeSkipSeek
        seekConfirmJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + PENDING_SEEK_TIMEOUT_MS
            try {
                while (System.currentTimeMillis() < deadline) {
                    if (_pendingSeekIndex.value != target) {
                        return@launch
                    }
                    val playback = PlaybackController.state.value
                    if (playback.playbackState == PlaybackState.ERROR) {
                        clearPendingSeek()
                        if (lockSkip) unlockSkipTransport()
                        return@launch
                    }
                    if (isSeekComplete(target, wasPlaying)) {
                        clearPendingSeek()
                        if (lockSkip) unlockSkipTransport()
                        return@launch
                    }
                    delay(50)
                }
                clearPendingSeek()
                if (lockSkip) unlockSkipTransport()
            } finally {
                seekInFlight = false
                val pending = _pendingSeekIndex.value
                if (pending != null &&
                    PlaybackController.state.value.currentSentenceIndex != pending
                ) {
                    scheduleSeekDebounced()
                }
            }
        }
    }

    private fun isSeekComplete(target: Int, wasPlaying: Boolean): Boolean {
        val playback = PlaybackController.state.value
        if (playback.currentSentenceIndex != target) return false
        return if (wasPlaying) {
            playback.playbackState == PlaybackState.PLAYING
        } else {
            true
        }
    }

    private fun unlockSkipTransport() {
        _skipTransportLocked.value = false
        activeSkipSeek = false
    }

    private fun clearPendingSeek() {
        _pendingSeekIndex.value = null
        pendingSeekWasPlaying = null
    }

    private fun captureWasPlaying(): Boolean {
        val playback = PlaybackController.state.value
        return playback.playbackState == PlaybackState.PLAYING ||
            playback.playbackState == PlaybackState.LOADING
    }

    private fun applyOptimisticSeek(index: Int) {
        val targetText = _sentences.value.getOrNull(index)?.textPreview ?: ""
        PlaybackController.update {
            it.copy(
                currentSentenceIndex = index,
                currentSentenceText = targetText,
                positionInSentenceMs = 0,
            )
        }
    }

    private fun executeSeekOnService(index: Int, wasPlaying: Boolean) {
        if (wasPlaying) {
            _userWantsPlaying.value = true
            PlaybackController.update {
                it.copy(playbackState = PlaybackState.PLAYING, loadingStage = TtsLoadingStage.NONE)
            }
        }
        if (TtsPlaybackService.isRunning()) {
            TtsPlaybackService.seekToSentenceIfRunning(index, resume = wasPlaying)
        } else {
            seekToSentence(index)
            if (wasPlaying) {
                resumePlayback()
            }
        }
    }

    fun scheduleSpeedApply(speed: Float) {
        speedApplyJob?.cancel()
        speedApplyJob = viewModelScope.launch {
            delay(1000)
            applySpeed(speed)
        }
    }

    private fun applySpeed(speed: Float) {
        viewModelScope.launch {
            app.settingsRepository.setPlaybackSpeed(speed)
            val context = getApplication<Application>()
            ContextCompat.startForegroundService(
                context,
                TtsPlaybackService.controlIntent(context, TtsPlaybackService.ACTION_SET_SPEED)
                    .putExtra(TtsPlaybackService.EXTRA_SPEED, speed),
            )
        }
    }

    fun setSpeed(speed: Float) {
        applySpeed(speed)
    }

    fun setSleepTimer(minutes: Int, untilEndOfBook: Boolean) {
        viewModelScope.launch {
            app.settingsRepository.setSleepTimer(minutes, untilEndOfBook)
            val context = getApplication<Application>()
            ContextCompat.startForegroundService(
                context,
                TtsPlaybackService.controlIntent(context, TtsPlaybackService.ACTION_SET_SLEEP)
                    .putExtra(TtsPlaybackService.EXTRA_SLEEP_MINUTES, minutes)
                    .putExtra(TtsPlaybackService.EXTRA_SLEEP_END, untilEndOfBook),
            )
        }
    }

    fun seekToSentence(index: Int) {
        val context = getApplication<Application>()
        ContextCompat.startForegroundService(
            context,
            TtsPlaybackService.controlIntent(context, TtsPlaybackService.ACTION_SEEK_SENTENCE)
                .putExtra(TtsPlaybackService.EXTRA_SENTENCE_INDEX, index),
        )
    }

    fun stopPlayback() {
        cancelPendingSeek()
        _userWantsPlaying.value = null
        PlaybackController.dismissPlayerScreen()
        PlaybackController.reset()
        val context = getApplication<Application>()
        ContextCompat.startForegroundService(
            context,
            TtsPlaybackService.controlIntent(context, TtsPlaybackService.ACTION_STOP),
        )
    }

    companion object {
        private const val SKIP_SENTENCE_COUNT = 10
        private const val SEEK_DEBOUNCE_MS = 1000L
        private const val PENDING_SEEK_TIMEOUT_MS = 3000L
    }
}
