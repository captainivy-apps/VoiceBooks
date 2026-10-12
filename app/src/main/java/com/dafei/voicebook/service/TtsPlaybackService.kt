package com.dafei.voicebook.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.dafei.voicebook.ui.AppToast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.annotation.StringRes
import androidx.lifecycle.LifecycleService
import com.dafei.voicebook.MainActivity
import com.dafei.voicebook.R
import com.dafei.voicebook.VoicebookApp
import com.dafei.voicebook.data.db.SentenceIndexEntity
import com.dafei.voicebook.data.db.TtsModelEntity
import com.dafei.voicebook.data.model.TtsDownloadState
import com.dafei.voicebook.domain.playback.PlaybackController
import com.dafei.voicebook.domain.playback.PlaybackHeadWaitPolicy
import com.dafei.voicebook.domain.playback.PlaybackSessionStore
import com.dafei.voicebook.domain.playback.PlaybackState
import com.dafei.voicebook.domain.playback.PlaybackTransitionGuards
import com.dafei.voicebook.domain.playback.SentencePlaybackPosition
import com.dafei.voicebook.domain.playback.TtsLoadingStage
import com.dafei.voicebook.tts.PcmStreamPlayer
import com.dafei.voicebook.tts.SherpaInitCoordinator
import com.dafei.voicebook.tts.SherpaInitResult
import com.dafei.voicebook.tts.StreamingGain
import com.dafei.voicebook.tts.StreamingSynthStats
import com.dafei.voicebook.tts.SynthesizedAudio
import com.dafei.voicebook.tts.TtsTextAdapter
import com.dafei.voicebook.tts.TtsTextChunker
import com.dafei.voicebook.tts.normalizeChunksForPlayback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.system.measureTimeMillis

class TtsPlaybackService : LifecycleService() {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled playback coroutine error", throwable)
        handlePlaybackFailure(throwable)
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + exceptionHandler)
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    private val app get() = application as VoicebookApp
    private val sherpaEngine get() = app.sherpaEngine
    private val sherpaInitCoordinator get() = app.sherpaInitCoordinator
    private val pcmPlayer = PcmStreamPlayer()
    private lateinit var wakeLock: PlaybackWakeLock
    private lateinit var playbackSessionStore: PlaybackSessionStore
    private var toastGeneration = 0
    private var playbackSession = 0
    private var seekGeneration = 0

    private var sentences: List<SentenceIndexEntity> = emptyList()
    private var currentIndex = 0
    private var positionInSentenceMs = 0L
    private var sentenceFramesBase = 0L
    private var sentenceSampleRate = 0
    @Volatile
    private var audioTrackNeedsReset = false
    @Volatile
    private var audioStopLatch = CountDownLatch(0)
    private var playbackSpeed = 1.0f
    private var bookId: String = ""
    private var bookTitle: String = ""
    private var isPlaying = AtomicBoolean(false)
    private var playbackJob: Job? = null
    private var playFromCurrentJob: Job? = null
    /** Cancelled on pause; drained before the next resume/restart loop. */
    private var drainedPlaybackJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var sleepUntilEndOfBook = false
    private var speakerId = 0
    private var activeTtsKey: String? = null
    private val preloadedAudio = mutableMapOf<Int, PreloadedSentence>()
    private val prefetchedText = mutableMapOf<Int, String>()
    private val prefetchTextJobs = mutableMapOf<Int, Job>()
    private var isForegroundStarted = false
    private val preloadJobs = mutableMapOf<Int, Job>()
    private var startupToastPending = false
    private var preloadsEnabled = false
    private var startPlaybackJob: Job? = null
    private var pauseFinalizeJob: Job? = null
    private var lastSentenceEndMs = 0L
    private var nextPreloadIndex = -1
    private val preloadStateLock = Any()
    private val pauseInFlight = AtomicBoolean(false)
    private val resumeInFlight = AtomicBoolean(false)
    private val seekInFlight = AtomicBoolean(false)
    @Volatile
    private var resumeStartSignal: CompletableDeferred<Unit>? = null
    private val sherpaDispatcher get() = sherpaInitCoordinator.sherpaDispatcher
    private val audioControlExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "audio-control").apply {
            priority = Thread.MAX_PRIORITY - 1
        }
    }

    private val sherpaSpeed: Float
        get() = playbackSpeed.coerceIn(MIN_SHERPA_SPEED, MAX_SHERPA_SPEED)

    override fun onCreate() {
        super.onCreate()
        wakeLock = PlaybackWakeLock(this)
        playbackSessionStore = PlaybackSessionStore(this)
        runningInstance = this
        PlaybackController.setImmediatePauseHandler {
            playbackScope.launch { pausePlayback() }
        }
        PlaybackController.setImmediateResumeHandler {
            playbackScope.launch { resumePlayback() }
        }
        createNotificationChannel()
        serviceScope.launch {
            playbackSpeed = (application as VoicebookApp).settingsRepository.getPlaybackSpeed()
            PlaybackController.update { it.copy(speed = playbackSpeed) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!ensureForeground(isPlaying.get())) {
            Log.w(TAG, "Unable to enter foreground, skip sticky restart")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == null) {
            serviceScope.launch {
                (application as VoicebookApp).settingsRepository.clearSleepTimer()
                clearSleepTimer(persist = false)
            }
            tryRecoverPlaybackIfNeeded()
            return START_STICKY
        }
        when (intent.action) {
            ACTION_PLAY -> {
                val id = intent.getStringExtra(EXTRA_BOOK_ID) ?: return START_NOT_STICKY
                launchStartPlayback(id)
            }
            ACTION_PAUSE -> pausePlayback()
            ACTION_RESUME -> resumePlayback()
            ACTION_TOGGLE -> {
                if (isPlaying.get()) {
                    pausePlayback()
                } else if (sentences.isEmpty()) {
                    intent.getStringExtra(EXTRA_BOOK_ID)?.let { id ->
                        launchStartPlayback(id)
                    }
                } else {
                    resumePlayback()
                }
            }
            ACTION_REWIND -> skipSentence(-1)
            ACTION_FORWARD -> skipSentence(1)
            ACTION_STOP -> stopPlayback()
            ACTION_SET_SPEED -> {
                playbackSpeed = intent.getFloatExtra(EXTRA_SPEED, 1.0f)
                PlaybackController.update { it.copy(speed = playbackSpeed) }
                applySpeedChange()
            }
            ACTION_RELOAD_TTS -> {
                serviceScope.launch { reloadTtsEngine() }
            }
            ACTION_SYNC_TTS_ENGINE -> {
                serviceScope.launch { syncTtsEngine() }
            }
            ACTION_PREPARE_TTS_PREVIEW -> prepareForTtsPreview()
            ACTION_SET_SLEEP -> {
                val minutes = intent.getIntExtra(EXTRA_SLEEP_MINUTES, 0)
                sleepUntilEndOfBook = intent.getBooleanExtra(EXTRA_SLEEP_END, false)
                scheduleSleepTimer(minutes, sleepUntilEndOfBook)
            }
            ACTION_SEEK_SENTENCE -> {
                val index = intent.getIntExtra(EXTRA_SENTENCE_INDEX, 0)
                seekToSentenceImmediate(index, resume = isPlaying.get())
            }
        }
        return START_STICKY
    }

    private fun launchStartPlayback(id: String, recover: Boolean = false) {
        if (!recover) {
            startPlaybackJob?.cancel()
            startPlaybackJob = null
        } else if (startPlaybackJob?.isActive == true) {
            return
        }
        if (startPlaybackJob?.isActive == true && bookId == id) return
        if (!recover && id == bookId &&
            (playbackJob?.isActive == true || playFromCurrentJob?.isActive == true)
        ) {
            logGap("start_playback_ignored book=$id pipeline_active")
            return
        }
        startPlaybackJob?.cancel()
        startPlaybackJob = serviceScope.launch {
            try {
                startPlayback(id, recover)
            } catch (t: Throwable) {
                Log.e(TAG, "startPlayback failed for book=$id", t)
                handlePlaybackFailure(t)
            } finally {
                startPlaybackJob = null
            }
        }
    }

    private fun tryRecoverPlaybackIfNeeded() {
        if (bookId.isNotEmpty() || startPlaybackJob?.isActive == true || isPlaying.get()) return
        val session = playbackSessionStore.load() ?: return
        if (!session.shouldResumeOnRestart) return
        Log.i(TAG, "Recovering playback after service restart book=${session.bookId}")
        launchStartPlayback(session.bookId, recover = true)
    }

    private fun persistPlaybackSession(shouldResumeOnRestart: Boolean) {
        if (bookId.isBlank()) return
        playbackSessionStore.save(bookId, shouldResumeOnRestart)
    }

    private suspend fun startPlayback(id: String, recover: Boolean = false) {
        invalidateToastSession()
        startupToastPending = !recover
        setLoadingStage(TtsLoadingStage.LOADING_BOOK)
        if (recover) {
            showTtsMessage(getString(R.string.playback_recovering))
        } else {
            showTtsToast(R.string.tts_startup_loading_book)
        }

        val app = application as VoicebookApp
        val book = app.bookRepository.getBook(id) ?: run {
            startupToastPending = false
            abortStartup()
            return
        }
        bookId = id
        bookTitle = book.title
        sentences = app.bookRepository.getSentences(id)
        if (sentences.isEmpty()) {
            startupToastPending = false
            abortStartup()
            return
        }

        updateControllerState(book.title, book.author, book.coverPath, PlaybackState.LOADING)
        updateNotification(true)

        val progress = app.bookRepository.getProgress(id)
        currentIndex = progress?.currentSentenceIndex ?: 0
        positionInSentenceMs = progress?.positionInSentenceMs ?: 0L
        sanitizeCurrentSentencePosition()

        val settings = app.settingsRepository.settings.first()
        playbackSpeed = settings.playbackSpeed
        if (sleepTimerJob?.isActive != true) {
            sleepUntilEndOfBook = settings.sleepUntilEndOfBook
            PlaybackController.update {
                it.copy(sleepUntilEndOfBook = sleepUntilEndOfBook, sleepTimerRemainingMs = 0)
            }
        }

        val defaultModel = app.ttsRepository.getDefaultModel()
        preloadsEnabled = false
        when (val initResult = awaitReinitializeTts(defaultModel)) {
            SherpaInitResult.Success -> {
                preloadsEnabled = true
                prefetchTextWindow(currentIndex)
                kickoffStartupPreloads()
                sanitizeCurrentSentencePosition()
                advanceIfSentenceAlreadyFinished()
                persistPlaybackSession(shouldResumeOnRestart = true)
                startPlaybackPipeline(forceRestart = false)
            }
            else -> failPlayback(initResult.toUserMessage())
        }
    }

    private fun setLoadingStage(stage: TtsLoadingStage) {
        PlaybackController.update { it.copy(loadingStage = stage, lastError = null) }
    }

    private fun markOutputtingAudioIfStartup() {
        if (!startupToastPending) return
        PlaybackController.update { state ->
            if (state.loadingStage == TtsLoadingStage.OUTPUTTING_AUDIO) state
            else state.copy(loadingStage = TtsLoadingStage.OUTPUTTING_AUDIO)
        }
    }

    private suspend fun kickoffStartupPreloads() {
        if (!sherpaEngine.isReady() || !preloadsEnabled) return
        preloadCurrentNow(currentIndex)
        ensureSequentialPreload(currentIndex + 1)
        if (!hasPreloadedAudio(currentIndex)) {
            setLoadingStage(TtsLoadingStage.SYNTHESIZING)
        }
        awaitPreload(currentIndex)
    }

    private fun failPlayback(message: String) {
        startupToastPending = false
        isPlaying.set(false)
        playFromCurrentJob?.cancel()
        playFromCurrentJob = null
        drainedPlaybackJob = null
        playbackJob?.cancel()
        playbackJob = null
        wakeLock.release()
        persistPlaybackSession(shouldResumeOnRestart = false)
        invalidateToastSession()
        cancelPreloads()
        releaseAudioTrack()
        Log.e(TAG, message)
        PlaybackController.update {
            it.copy(
                playbackState = PlaybackState.ERROR,
                loadingStage = TtsLoadingStage.NONE,
                lastError = message,
            )
        }
        showTtsMessage(message)
        updateNotification(false)
    }

    private fun handlePlaybackFailure(throwable: Throwable) {
        if (throwable is CancellationException) return
        val cause = throwable.cause
        if (cause is CancellationException) return
        startupToastPending = false
        isPlaying.set(false)
        playbackJob?.cancel()
        cancelPreloads()
        val message = throwable.message?.takeIf { it.isNotBlank() }
            ?: getString(R.string.tts_playback_failed_generic)
        failPlayback(message)
    }

    private fun invalidateToastSession() {
        toastGeneration++
        AppToast.cancel()
    }

    private fun showTtsToast(@StringRes resId: Int, vararg formatArgs: Any) {
        showTtsMessage(getString(resId, *formatArgs), gated = true)
    }

    private fun showTtsMessage(message: String, gated: Boolean = false) {
        val generation = toastGeneration
        serviceScope.launch {
            if (gated && generation != toastGeneration) return@launch
            AppToast.show(applicationContext, message, Toast.LENGTH_LONG)
        }
    }

    private fun finishStartupToast() {
        if (!startupToastPending) return
        startupToastPending = false
        val generation = toastGeneration
        serviceScope.launch {
            if (generation != toastGeneration) return@launch
            AppToast.show(
                applicationContext,
                getString(R.string.tts_startup_playing),
                Toast.LENGTH_SHORT,
            )
        }
    }

    private fun updateControllerState(
        title: String,
        author: String,
        coverPath: String?,
        state: PlaybackState,
    ) {
        PlaybackController.update {
            it.copy(
                bookId = bookId,
                bookTitle = title,
                bookAuthor = author,
                coverPath = coverPath,
                totalSentences = sentences.size,
                currentSentenceIndex = currentIndex,
                currentSentenceText = sentences.getOrNull(currentIndex)?.textPreview ?: "",
                playbackState = state,
                speed = playbackSpeed,
                positionInSentenceMs = positionInSentenceMs,
                sleepUntilEndOfBook = sleepUntilEndOfBook,
            )
        }
    }

    private fun submitAudioStop(snapshotPosition: Boolean) {
        audioTrackNeedsReset = true
        val latch = CountDownLatch(1)
        audioStopLatch = latch
        audioControlExecutor.execute {
            try {
                pcmPlayer.pauseImmediately()
                if (snapshotPosition) {
                    snapshotPausePositionOnExecutor()
                }
                pcmPlayer.stopForPauseBlocking()
            } finally {
                latch.countDown()
            }
        }
    }

    private suspend fun awaitAudioStop(timeoutMs: Long = 500L) {
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (coroutineContext.isActive) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) return@withContext
                val pollMs = minOf(50L, remaining)
                try {
                    if (audioStopLatch.await(pollMs, TimeUnit.MILLISECONDS)) return@withContext
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@withContext
                }
            }
        }
    }

    private fun snapshotPausePositionOnExecutor() {
        if (sentenceFramesBase <= 0L) return
        val rate = pcmPlayer.sampleRate.takeIf { it > 0 } ?: sentenceSampleRate
        if (rate <= 0) return
        val head = pcmPlayer.playbackHeadFrames()
        val playedMs = ((head - sentenceFramesBase).coerceAtLeast(0) * 1000) / rate
        val normalized = SentencePlaybackPosition.sanitizePositionForSentence(
            positionMs = playedMs,
            durationMs = peekSentenceDurationMs(currentIndex),
        )
        if (normalized > positionInSentenceMs) {
            positionInSentenceMs = normalized
            PlaybackController.update { it.copy(positionInSentenceMs = positionInSentenceMs) }
        }
    }

    private fun preparePcmPlayer(sampleRate: Int): Boolean {
        val append = !audioTrackNeedsReset && pcmPlayer.canAppendContinuous(sampleRate)
        val ok = pcmPlayer.prepare(sampleRate, append)
        if (ok) {
            logGap(
                "audiotrack_prepared sampleRate=$sampleRate append=$append " +
                    "framesWritten=${pcmPlayer.framesWritten}",
            )
            if (audioTrackNeedsReset) {
                audioTrackNeedsReset = false
            }
        }
        return ok
    }

    private fun markSentencePlaybackStart(sampleRate: Int) {
        sentenceFramesBase = pcmPlayer.framesWritten
        sentenceSampleRate = sampleRate
    }

    private fun invalidateSentenceFrameSnapshot() {
        sentenceFramesBase = 0L
        sentenceSampleRate = 0
    }

    private fun interruptPlayback(): Job? {
        playFromCurrentJob?.cancel()
        playFromCurrentJob = null
        drainedPlaybackJob = null
        playbackSession++
        pcmPlayer.signalStopRequested()
        pcmPlayer.pauseImmediately()
        val previousJob = playbackJob
        previousJob?.cancel()
        playbackJob = null
        submitAudioStop(snapshotPosition = false)
        return previousJob
    }

    private suspend fun awaitPlaybackDrain(previousJob: Job?) {
        try {
            previousJob?.join()
        } catch (_: CancellationException) {
            // Expected when interrupting in-flight playback.
        }
        withContext(sherpaDispatcher) {
            // Drain in-flight Sherpa work on the shared thread before restarting.
        }
    }

    private fun restartPlaybackFromCurrent(clearPreloads: Boolean = false) {
        if (clearPreloads) {
            cancelPreloads()
        }
        playFromCurrent(forceRestart = true)
    }

    /** Resume when a stale playback loop exits but the user still expects continuous play. */
    private fun schedulePlaybackRecoveryIfNeeded() {
        if (!isPlaying.get() || currentIndex >= sentences.size) return
        if (playbackJob?.isActive == true || playFromCurrentJob?.isActive == true) return
        playbackScope.launch {
            awaitAudioStop(200)
            if (!isActive) return@launch
            if (!isPlaying.get() || currentIndex >= sentences.size) return@launch
            if (playbackJob?.isActive == true || playFromCurrentJob?.isActive == true) return@launch
            logGap("playback_recovery index=$currentIndex session=$playbackSession")
            playFromCurrent()
        }
    }

    private fun stopAudioTrackForReuse() {
        submitAudioStop(snapshotPosition = false)
    }

    /** Stop track during synthesis gaps so an empty PLAYING buffer cannot underrun. */
    private fun stopAudioTrackForGap() {
        stopAudioTrackForReuse()
    }

    private fun playFromCurrent(forceRestart: Boolean = false) {
        playFromCurrentJob?.cancel()
        val zombieJob = drainedPlaybackJob?.also { drainedPlaybackJob = null }
        val previousJob = if (forceRestart || playbackJob?.isActive == true) {
            interruptPlayback()
        } else {
            if (pcmPlayer.isInitialized || audioTrackNeedsReset) {
                pcmPlayer.signalStopRequested()
                submitAudioStop(snapshotPosition = false)
            }
            null
        }
        playFromCurrentJob = playbackScope.launch {
            val restartSession = playbackSession
            awaitAudioStop(SEEK_AUDIO_STOP_TIMEOUT_MS)
            awaitPlaybackDrain(previousJob)
            awaitPlaybackDrain(zombieJob)
            if (!isActive) {
                signalResumeLoopStarted()
                return@launch
            }
            if (restartSession != playbackSession) {
                abortPlayFromCurrent("session_mismatch")
                return@launch
            }
            if (!isPlaying.get() &&
                PlaybackController.state.value.playbackState == PlaybackState.PAUSED
            ) {
                abortPlayFromCurrent("paused")
                return@launch
            }
            lastSentenceEndMs = 0L
            startPlaybackLoop()
        }
    }

    private fun startPlaybackLoop() {
        wakeLock.acquire()
        persistPlaybackSession(shouldResumeOnRestart = true)
        playbackJob?.cancel()
        playbackJob = playbackScope.launch {
            val loopSession = playbackSession
            if (!isPlaying.get()) {
                signalResumeLoopStarted()
                return@launch
            }
            signalResumeLoopStarted()
            updateNotification(true)
            if (sherpaEngine.isReady()) {
                preloadsEnabled = true
                prefetchTextWindow(currentIndex)
                ensureSequentialPreload(currentIndex + 1)
            }

            while (isActive && currentIndex < sentences.size && isPlaying.get()) {
                if (loopSession != playbackSession) {
                    schedulePlaybackRecoveryIfNeeded()
                    return@launch
                }
                if (advanceIfSentenceAlreadyFinished()) {
                    if (currentIndex >= sentences.size) {
                        pausePlayback()
                        PlaybackController.update { it.copy(playbackState = PlaybackState.STOPPED) }
                        return@launch
                    }
                    continue
                }
                val sentence = sentences[currentIndex]
                val sentenceStartWallMs = System.currentTimeMillis()
                val gapSinceLastMs = if (lastSentenceEndMs > 0) sentenceStartWallMs - lastSentenceEndMs else 0L

                val isFirstSentence = startupToastPending
                if (isFirstSentence) {
                    setLoadingStage(TtsLoadingStage.READING_TEXT)
                    showTtsToast(R.string.tts_startup_reading_text)
                }
                lateinit var text: String
                val textLoadMs = measureTimeMillis {
                    text = loadSentenceText(sentence, currentIndex)
                }
                val adaptedText = adaptTextForCurrentModel(text)
                if (adaptedText == null) {
                    logGap(
                        "sentence_skip index=$currentIndex reason=unsupported_language " +
                            "textPreview=${textPreview(text)}",
                    )
                    if (isFirstSentence) {
                        finishStartupToast()
                        PlaybackController.update {
                            it.copy(
                                currentSentenceIndex = currentIndex,
                                currentSentenceText = text,
                                loadingStage = TtsLoadingStage.NONE,
                            )
                        }
                    }
                    positionInSentenceMs = 0
                    currentIndex++
                    invalidateSentenceFrameSnapshot()
                    saveProgressAsync()
                    lastSentenceEndMs = System.currentTimeMillis()
                    continue
                }
                text = adaptedText

                var cached = takePreloaded(currentIndex)
                val initialCacheHit = cached != null

                if (preloadsEnabled && initialCacheHit) {
                    schedulePreloadWindow(currentIndex)
                } else if (preloadsEnabled) {
                    prefetchTextWindow(currentIndex)
                    ensureSequentialPreload(currentIndex + 1)
                }

                if (cached == null) {
                    stopAudioTrackForGap()
                    cancelCompetingPreloads(currentIndex)
                    if (preloadJobs[currentIndex]?.isActive == true) {
                        awaitPreload(currentIndex)
                        cached = takePreloaded(currentIndex)
                    }
                }
                val cacheHit = cached != null
                val preloadSynthesisMs = cached?.synthesisMs ?: 0L
                logGap(
                    "sentence_start index=$currentIndex gapSinceLastMs=$gapSinceLastMs " +
                        "initialCacheHit=$initialCacheHit cacheHit=$cacheHit textLoadMs=$textLoadMs " +
                        "preloadJobs=${synchronized(preloadStateLock) { preloadJobs.keys.sorted() }} " +
                        "cachedKeys=${synchronized(preloadStateLock) { preloadedAudio.keys.sorted() }}",
                )
                if (gapSinceLastMs > GAP_WARN_MS) {
                    logGap(
                        "gap_detected index=$currentIndex gapMs=$gapSinceLastMs " +
                            "initialCacheHit=$initialCacheHit cacheHit=$cacheHit",
                    )
                }

                if (cached == null && isFirstSentence) {
                    setLoadingStage(TtsLoadingStage.SYNTHESIZING)
                    showTtsToast(R.string.tts_startup_synthesizing)
                }

                val playStartMs = System.currentTimeMillis()
                val result = if (cached != null && cached.isPlayable()) {
                    playCachedSherpa(
                        cached,
                        positionInSentenceMs,
                        isFirstSentence,
                        currentIndex,
                        text,
                    )
                } else {
                    streamSynthAndPlay(text, positionInSentenceMs, isFirstSentence, currentIndex)
                }
                val playMs = System.currentTimeMillis() - playStartMs
                when (result) {
                    SherpaPlayResult.Completed -> {
                        logGap(
                            "sentence_end index=$currentIndex playMs=$playMs preloadSynthesisMs=$preloadSynthesisMs " +
                                "nextCached=${hasPreloadedAudio(currentIndex + 1)}",
                        )
                        logSynthesisVsPlay(currentIndex, preloadSynthesisMs, playMs, "sentence_end")
                        positionInSentenceMs = 0
                        if (!hasPreloadedAudio(currentIndex + 1) && currentIndex + 1 < sentences.size) {
                            ensureSequentialPreload(currentIndex + 1)
                            logGap("next_sentence_not_ready index=${currentIndex + 1}")
                        }
                        schedulePreloadsAfterSentence()
                        lastSentenceEndMs = System.currentTimeMillis()
                        currentIndex++
                        invalidateSentenceFrameSnapshot()
                        syncPlaybackIndexToController()
                        saveProgressAsync()
                        if (sleepUntilEndOfBook && currentIndex >= sentences.size) {
                            pausePlayback()
                            clearSleepTimer(persist = true)
                            break
                        }
                    }
                    SherpaPlayResult.Interrupted -> {
                        if (loopSession == playbackSession) {
                            isPlaying.set(false)
                        }
                        break
                    }
                    is SherpaPlayResult.Failed -> {
                        if (!isActive || !isPlaying.get() || loopSession != playbackSession) break
                        failPlayback(result.userMessage)
                        Log.e(TAG, result.logDetail)
                        return@launch
                    }
                }
            }

            if (currentIndex >= sentences.size) {
                pausePlayback()
                PlaybackController.update { it.copy(playbackState = PlaybackState.STOPPED) }
            } else if (isPlaying.get()) {
                schedulePlaybackRecoveryIfNeeded()
            } else if (
                PlaybackController.state.value.playbackState == PlaybackState.PLAYING &&
                currentIndex < sentences.size
            ) {
                sanitizeCurrentSentencePosition()
                val advanced = advanceIfSentenceAlreadyFinished()
                if (advanced && currentIndex >= sentences.size) {
                    pausePlayback()
                    PlaybackController.update { it.copy(playbackState = PlaybackState.STOPPED) }
                } else {
                    PlaybackController.update {
                        it.copy(playbackState = PlaybackState.PAUSED, loadingStage = TtsLoadingStage.NONE)
                    }
                }
            }
        }
    }

    private suspend fun loadSentenceText(sentence: SentenceIndexEntity, index: Int): String {
        prefetchedText.remove(index)?.let { return it }
        return readSentenceTextFromDisk(sentence)
    }

    private suspend fun adaptTextForCurrentModel(raw: String): String? {
        val model = app.ttsRepository.getDefaultModel() ?: return raw.trim().takeIf { it.isNotEmpty() }
        return TtsTextAdapter.adaptForModel(raw, model.language)
    }

    private suspend fun readSentenceTextFromDisk(sentence: SentenceIndexEntity): String {
        val book = app.bookRepository.getBook(bookId) ?: return sentence.textPreview
        if (book.textPath.isBlank()) return sentence.textPreview
        return withContext(Dispatchers.IO) {
            app.bookRepository.readSentenceText(
                book.textPath,
                sentence.byteOffsetStart,
                sentence.byteOffsetEnd,
            )
        }.ifBlank { sentence.textPreview }
    }

    private fun prefetchSentenceText(index: Int) {
        if (index >= sentences.size || index < 0) return
        if (prefetchedText.containsKey(index) || prefetchTextJobs[index]?.isActive == true) return
        prefetchTextJobs[index] = serviceScope.launch {
            try {
                if (!prefetchedText.containsKey(index)) {
                    val text = readSentenceTextFromDisk(sentences[index])
                    prefetchedText[index] = text
                    logGap("prefetch_text_done index=$index len=${text.length}")
                }
            } finally {
                prefetchTextJobs.remove(index)
            }
        }
    }

    private fun prefetchTextWindow(fromCurrentIndex: Int) {
        for (offset in 0..MAX_PRELOAD_AHEAD) {
            prefetchSentenceText(fromCurrentIndex + offset)
        }
    }

    private fun takePreloaded(index: Int): PreloadedSentence? = synchronized(preloadStateLock) {
        val cached = preloadedAudio.remove(index) ?: return@synchronized null
        logGap("preload_consumed index=$index chunks=${cached.pcmChunks.size}")
        cached
    }

    private fun hasPreloadedAudio(index: Int): Boolean = synchronized(preloadStateLock) {
        preloadedAudio.containsKey(index)
    }

    private suspend fun awaitPreload(index: Int) {
        if (hasPreloadedAudio(index)) return

        cancelCompetingPreloads(index)
        preloadCurrentNow(index)

        val existingJob = synchronized(preloadStateLock) { preloadJobs[index] }
        if (existingJob != null) {
            if (existingJob.isActive) {
                val waitedMs = measureTimeMillis {
                    try {
                        existingJob.join()
                    } catch (_: CancellationException) {
                        return
                    }
                }
                logGap("await_preload index=$index waitedMs=$waitedMs hit=${hasPreloadedAudio(index)}")
            }
            if (hasPreloadedAudio(index)) return
        }

        ensureSequentialPreload(index)
        preloadCurrentNow(index)

        val waitedMs = measureTimeMillis {
            repeat(PRELOAD_AWAIT_POLLS) {
                if (hasPreloadedAudio(index)) return@measureTimeMillis
                val job = synchronized(preloadStateLock) { preloadJobs[index] }
                if (job != null) {
                    if (job.isActive) {
                        try {
                            job.join()
                        } catch (_: CancellationException) {
                            return
                        }
                    }
                    return@measureTimeMillis
                }
                if (it == PRELOAD_AWAIT_POLLS / 2) {
                    ensureSequentialPreload(index)
                }
                delay(PRELOAD_AWAIT_POLL_MS)
            }
        }
        if (waitedMs > 0) {
            logGap(
                "await_preload_poll index=$index waitedMs=$waitedMs hit=${hasPreloadedAudio(index)} " +
                    "nextPreloadIndex=$nextPreloadIndex",
            )
        }
    }

    private fun logGap(message: String) {
        Log.d(GAP_DEBUG_TAG, message)
    }

    private fun playbackSnapshot(): String {
        return "session=$playbackSession index=$currentIndex posMs=$positionInSentenceMs " +
            "state=${PlaybackController.state.value.playbackState} isPlaying=${isPlaying.get()}"
    }

    private fun shouldSkipPauseFinalize(pauseSessionAtStart: Int): Boolean {
        return PlaybackTransitionGuards.shouldSkipPauseFinalize(
            isPlaying = isPlaying.get(),
            pauseSessionAtStart = pauseSessionAtStart,
            currentSession = playbackSession,
        )
    }

    private suspend fun awaitPauseTransitionComplete() {
        val job = pauseFinalizeJob ?: return
        logGap("resume_await_finalize ${playbackSnapshot()}")
        val joined = withTimeoutOrNull(PAUSE_FINALIZE_AWAIT_TIMEOUT_MS) {
            job.join()
            true
        } ?: false
        if (!joined) {
            logGap("resume_await_finalize_timeout ${playbackSnapshot()}")
        }
    }

    private fun signalResumeLoopStarted() {
        resumeStartSignal?.complete(Unit)
    }

    private fun abortPlayFromCurrent(reason: String) {
        logGap("playFromCurrent_abort reason=$reason ${playbackSnapshot()}")
        if (isPlaying.get() && currentIndex < sentences.size) {
            schedulePlaybackRecoveryIfNeeded()
        } else if (
            PlaybackController.state.value.playbackState == PlaybackState.PLAYING &&
            !isPlaying.get()
        ) {
            PlaybackController.update {
                it.copy(playbackState = PlaybackState.PAUSED, loadingStage = TtsLoadingStage.NONE)
            }
        }
        signalResumeLoopStarted()
    }

    private fun preloadDiscardReason(index: Int, preloadSession: Int): String? {
        if (!preloadsEnabled) return "preloads_disabled"
        if (preloadSession != playbackSession) return "session_mismatch"
        if (pauseInFlight.get() && !isPlaying.get()) return "pause_finalizing"
        if (index < currentIndex) return "stale_index"
        return null
    }

    private fun estimatedPlayMs(chunks: List<FloatArray>, sampleRate: Int): Long {
        if (sampleRate <= 0) return 0L
        return chunks.sumOf { chunk ->
            (chunk.size.toLong() * 1000) / sampleRate
        }
    }

    private fun peekSentenceDurationMs(index: Int): Long? = synchronized(preloadStateLock) {
        val cached = preloadedAudio[index] ?: return@synchronized null
        if (!cached.isPlayable()) return@synchronized null
        estimatedPlayMs(cached.pcmChunks, cached.sampleRate)
    }

    private fun sanitizeCurrentSentencePosition(): Boolean {
        val sanitized = SentencePlaybackPosition.sanitizePositionForSentence(
            positionMs = positionInSentenceMs,
            durationMs = peekSentenceDurationMs(currentIndex),
        )
        if (sanitized == positionInSentenceMs) return false
        logGap(
            "sentence_position_sanitized index=$currentIndex from=$positionInSentenceMs to=$sanitized",
        )
        positionInSentenceMs = sanitized
        PlaybackController.update { it.copy(positionInSentenceMs = positionInSentenceMs) }
        return true
    }

    private fun advanceIfSentenceAlreadyFinished(saveProgress: Boolean = true): Boolean {
        if (positionInSentenceMs <= 0 || currentIndex >= sentences.size) return false
        sanitizeCurrentSentencePosition()
        val durationMs = peekSentenceDurationMs(currentIndex) ?: return false
        if (!SentencePlaybackPosition.isSentencePositionAtEnd(positionInSentenceMs, durationMs)) {
            return false
        }
        logGap(
            "sentence_end_normalized index=$currentIndex positionMs=$positionInSentenceMs " +
                "durationMs=$durationMs",
        )
        positionInSentenceMs = 0
        invalidateSentenceFrameSnapshot()
        lastSentenceEndMs = System.currentTimeMillis()
        currentIndex++
        syncPlaybackIndexToController()
        if (saveProgress) {
            saveProgressAsync()
        }
        return true
    }

    private fun syncPlaybackIndexToController() {
        if (currentIndex < sentences.size) {
            PlaybackController.update {
                it.copy(
                    currentSentenceIndex = currentIndex,
                    currentSentenceText = sentences.getOrNull(currentIndex)?.textPreview ?: "",
                    positionInSentenceMs = positionInSentenceMs,
                )
            }
        } else {
            PlaybackController.update { it.copy(positionInSentenceMs = positionInSentenceMs) }
        }
    }

    private fun logSynthesisVsPlay(index: Int, synthesisMs: Long, playMs: Long, context: String) {
        if (synthesisMs <= 0 || playMs <= 0) return
        if (synthesisMs > playMs) {
            logGap(
                "synthesis_slower_than_play context=$context index=$index " +
                    "synthesisMs=$synthesisMs playMs=$playMs " +
                    "ratio=${"%.2f".format(synthesisMs.toDouble() / playMs)}",
            )
        }
    }

    private fun estimateTextChunksDurationMs(totalSamples: Int, sampleRate: Int): Long {
        if (totalSamples <= 0 || sampleRate <= 0) return 0L
        return (totalSamples.toLong() * 1000L) / sampleRate
    }

    private suspend fun playCachedSherpa(
        cached: PreloadedSentence,
        startMs: Long,
        notifyStartup: Boolean,
        sentenceIndex: Int,
        subtitleText: String,
    ): SherpaPlayResult {
        if (!cached.isPlayable()) {
            return SherpaPlayResult.Failed(
                userMessage = getString(R.string.tts_error_synthesis_empty_audio),
                logDetail = "cached sentence not playable index=$sentenceIndex",
            )
        }

        val session = playbackSession
        var skipMs = startMs
        var notifyOnNextPlay = notifyStartup
        var pendingSubtitle: SubtitleUpdate? = SubtitleUpdate(
            index = sentenceIndex,
            text = subtitleText,
            totalSentences = sentences.size,
        )
        val sampleRate = cached.sampleRate
        var playbackStarted = false
        var firstWriteLogged = false

        return withContext(Dispatchers.IO) {
            if (!preparePcmPlayer(sampleRate)) {
                return@withContext SherpaPlayResult.Failed(
                    userMessage = getString(R.string.tts_error_audiotrack_init),
                    logDetail = "pcmPlayer.prepare failed sampleRate=$sampleRate",
                )
            }
            val framesBeforeSentence = pcmPlayer.framesWritten
            markSentencePlaybackStart(sampleRate)

            for (chunk in cached.pcmChunks) {
                if (!isPlaying.get() || session != playbackSession) {
                    return@withContext SherpaPlayResult.Interrupted
                }

                val chunkDurationMs = (chunk.size.toLong() * 1000) / sampleRate
                if (skipMs >= chunkDurationMs) {
                    skipMs -= chunkDurationMs
                    continue
                }

                val skipSamples = if (skipMs > 0) ((skipMs * sampleRate) / 1000).toInt() else 0
                skipMs = 0L

                if (!playbackStarted) {
                    if (!pcmPlayer.isInitialized && !preparePcmPlayer(sampleRate)) {
                        return@withContext SherpaPlayResult.Failed(
                            userMessage = getString(R.string.tts_error_audiotrack_init),
                            logDetail = "cached prepare failed sampleRate=$sampleRate",
                        )
                    }
                    if (notifyOnNextPlay) {
                        markOutputtingAudioIfStartup()
                    }
                    pcmPlayer.ensurePlaying()
                    playbackStarted = true
                    notifyStreamPlaybackStarted(notifyOnNextPlay, pendingSubtitle, sentenceIndex)
                    notifyOnNextPlay = false
                    pendingSubtitle = null
                }

                val shouldContinue = { isPlaying.get() && session == playbackSession }
                val written = if (skipSamples > 0) {
                    pcmPlayer.writeSamplesFromOffset(chunk, skipSamples, shouldContinue)
                } else {
                    pcmPlayer.writeSamples(chunk, shouldContinue)
                }
                if (written < 0) {
                    if (!isPlaying.get()) {
                        return@withContext SherpaPlayResult.Interrupted
                    }
                    return@withContext SherpaPlayResult.Failed(
                        userMessage = getString(R.string.tts_error_audiotrack_write),
                        logDetail = "cached write failed index=$sentenceIndex",
                    )
                }
                if (written > 0 && !firstWriteLogged) {
                    logGap(
                        "audiotrack_first_write index=$sentenceIndex frames=$written " +
                            "totalWritten=${pcmPlayer.framesWritten}",
                    )
                    firstWriteLogged = true
                }
                if (!isPlaying.get() || session != playbackSession) {
                    return@withContext SherpaPlayResult.Interrupted
                }

                if (playbackStarted) {
                    waitForSentencePlayback(
                        session = session,
                        framesBeforeWrite = framesBeforeSentence,
                        framesEnd = pcmPlayer.framesWritten,
                        sampleRate = sampleRate,
                        progressBaseMs = startMs,
                        sentenceIndex = sentenceIndex,
                    )
                    if (!isPlaying.get() || session != playbackSession) {
                        return@withContext SherpaPlayResult.Interrupted
                    }
                }
            }

            if (playbackStarted) {
                val head = pcmPlayer.playbackHeadFrames()
                val writtenFrames = pcmPlayer.framesWritten - framesBeforeSentence
                val playedFrames = (head - framesBeforeSentence).coerceAtLeast(0)
                if (writtenFrames > 0 && playedFrames == 0L) {
                    return@withContext SherpaPlayResult.Failed(
                        userMessage = getString(R.string.tts_error_audiotrack_write),
                        logDetail = "cached playback head never advanced index=$sentenceIndex " +
                            "written=$writtenFrames head=$head",
                    )
                }
            }

            SherpaPlayResult.Completed
        }
    }

    private fun cancelCompetingPreloads(urgentIndex: Int) {
        synchronized(preloadStateLock) {
            for (key in preloadJobs.keys.toList()) {
                if (key == urgentIndex) continue
                preloadJobs.remove(key)?.cancel()
                logGap("cancel_competing_preload index=$key urgentIndex=$urgentIndex")
            }
        }
    }

    private fun preloadCurrentNow(index: Int) {
        if (index != currentIndex) return
        if (hasPreloadedAudio(index)) return
        if (synchronized(preloadStateLock) { preloadJobs[index]?.isActive == true }) return
        cancelCompetingPreloads(index)
        preloadNext(index)
    }

    private fun enablePreloadsAfterPlaybackStart(sentenceIndex: Int) {
        if (preloadsEnabled) {
            schedulePreloadWindow(sentenceIndex)
            return
        }
        preloadsEnabled = true
        schedulePreloadWindow(sentenceIndex)
    }

    private fun schedulePreloadWindow(fromCurrentIndex: Int) {
        if (!sherpaEngine.isReady() || !preloadsEnabled) return
        trimPreloadCache()
        prefetchTextWindow(fromCurrentIndex)
        ensureSequentialPreload(fromCurrentIndex + 1)
    }

    private fun schedulePreloadsAfterSentence() {
        schedulePreloadWindow(currentIndex)
    }

    /** Start or resume the sequential preload pipeline from [startIndex] (never preloads [currentIndex]). */
    private fun ensureSequentialPreload(startIndex: Int) {
        if (!sherpaEngine.isReady() || !preloadsEnabled) return
        val endIndex = minOf(currentIndex + MAX_PRELOAD_AHEAD, sentences.lastIndex)
        val firstIndex = maxOf(startIndex, currentIndex + 1)
        if (firstIndex > endIndex || firstIndex >= sentences.size) return

        synchronized(preloadStateLock) {
            for (index in firstIndex..endIndex) {
                when {
                    preloadedAudio.containsKey(index) -> continue
                    preloadJobs[index]?.isActive == true -> return
                    else -> {
                        preloadNextLocked(index)
                        return
                    }
                }
            }
        }
    }

    private fun trimPreloadCacheInLock() {
        val keepFrom = currentIndex
        val keepTo = currentIndex + MAX_PRELOAD_AHEAD
        for (key in preloadedAudio.keys.toList()) {
            if (key !in keepFrom..keepTo) preloadedAudio.remove(key)
        }
        for (key in prefetchedText.keys.toList()) {
            if (key !in keepFrom..keepTo) {
                prefetchedText.remove(key)
                prefetchTextJobs.remove(key)?.cancel()
            }
        }
        for (key in preloadJobs.keys.toList()) {
            if (key !in keepFrom..keepTo) {
                preloadJobs.remove(key)?.cancel()
            }
        }
        if (nextPreloadIndex !in keepFrom..keepTo) {
            nextPreloadIndex = -1
        }
    }

    private fun trimPreloadCache() {
        synchronized(preloadStateLock) {
            trimPreloadCacheInLock()
        }
    }

    private fun preloadNext(index: Int) {
        if (!sherpaEngine.isReady() || !preloadsEnabled || index >= sentences.size) return
        val chainCompletedIndex = synchronized(preloadStateLock) {
            if (preloadedAudio.containsKey(index)) {
                return@synchronized index
            }
            if (preloadJobs[index]?.isActive == true) return@synchronized null
            if (index > currentIndex + MAX_PRELOAD_AHEAD) return@synchronized null
            if (preloadJobs.any { (jobIndex, job) -> jobIndex != index && job.isActive }) {
                return@synchronized null
            }
            preloadNextLocked(index)
            null
        }
        if (chainCompletedIndex != null) {
            chainPreloadAfter(chainCompletedIndex)
        }
    }

    /** Caller must hold [preloadStateLock]. */
    private fun preloadNextLocked(index: Int) {
        nextPreloadIndex = index
        val preloadSession = playbackSession
        logGap(
            "preload_start index=$index currentIndex=$currentIndex session=$preloadSession",
        )
        preloadJobs[index] = serviceScope.launch(Dispatchers.Default) {
            try {
                if (!sherpaEngine.isReady() || !preloadsEnabled || index >= sentences.size) return@launch
                if (hasPreloadedAudio(index)) return@launch

                val sentence = sentences[index]
                val text = adaptTextForCurrentModel(loadSentenceText(sentence, index))
                if (text == null) {
                    logGap("preload_skip index=$index reason=unsupported_language")
                    return@launch
                }
                val chunks = TtsTextChunker.chunk(text, MAX_SHERPA_TEXT_CHARS)
                if (chunks.isEmpty()) {
                    logGap("preload_skip index=$index reason=empty_chunks")
                    return@launch
                }

                val pcmChunks = mutableListOf<FloatArray>()
                var sampleRate = 0
                val synthesisMs = measureTimeMillis {
                    withContext(sherpaDispatcher) {
                        val effectiveSpeakerId = sherpaEngine.resolveSpeakerId(speakerId)
                        for (chunk in chunks) {
                            if (!isActive || !preloadsEnabled) return@withContext
                            val stats = sherpaEngine.synthesizeStreaming(
                                text = chunk,
                                speed = sherpaSpeed,
                                speakerId = effectiveSpeakerId,
                                shouldContinue = { isActive && preloadsEnabled },
                                onChunk = chunk@{ samples ->
                                    if (!isActive || !preloadsEnabled) return@chunk 0
                                    if (sampleRate == 0) sampleRate = sherpaEngine.sampleRate()
                                    pcmChunks.add(samples.copyOf())
                                    1
                                },
                            )
                            if (stats == null || stats.totalSamples <= 0) {
                                val batch = sherpaEngine.synthesize(chunk, sherpaSpeed, effectiveSpeakerId)
                                if (batch == null || !batch.isPlayable()) return@withContext
                                pcmChunks.add(batch.samples.copyOf())
                                if (sampleRate == 0) sampleRate = batch.sampleRate
                            } else if (stats.stoppedEarly) {
                                return@withContext
                            }
                            if (sampleRate == 0) sampleRate = stats?.sampleRate ?: sampleRate
                        }
                    }
                }

                // One shared gain for the whole sentence: per-chunk gain made loudness depend on
                // whichever chunk was estimated first (MeloTTS pitched/soft onsets), causing
                // the "忽高忽低" fluctuation.
                normalizeChunksForPlayback(pcmChunks)

                val preloaded = PreloadedSentence(pcmChunks, sampleRate, synthesisMs)
                if (!preloaded.isPlayable()) {
                    logGap("preload_skip index=$index reason=not_playable")
                    return@launch
                }

                val discardReason = preloadDiscardReason(index, preloadSession)
                if (discardReason != null) {
                    logGap("preload_discarded index=$index reason=$discardReason")
                    return@launch
                }

                synchronized(preloadStateLock) {
                    trimPreloadCacheInLock()
                    preloadedAudio[index] = preloaded
                }
                val estimatedPlayMs = estimatedPlayMs(pcmChunks, sampleRate)
                logGap(
                    "preload_done index=$index chunks=${pcmChunks.size} " +
                        "synthesisMs=$synthesisMs estimatedPlayMs=$estimatedPlayMs",
                )
                logSynthesisVsPlay(index, synthesisMs, estimatedPlayMs, "preload_done")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.w(TAG, "preload failed index=$index", t)
            } finally {
                synchronized(preloadStateLock) {
                    preloadJobs.remove(index)
                    if (nextPreloadIndex == index) {
                        nextPreloadIndex = -1
                    }
                }
                chainPreloadAfter(index)
            }
        }
    }

    private fun chainPreloadAfter(completedIndex: Int) {
        if (!preloadsEnabled) return
        val nextIndex = completedIndex + 1
        if (nextIndex > currentIndex + MAX_PRELOAD_AHEAD || nextIndex >= sentences.size) return
        val shouldChainNext = synchronized(preloadStateLock) {
            preloadedAudio.containsKey(nextIndex) || preloadJobs[nextIndex]?.isActive == true
        }
        if (shouldChainNext) {
            ensureSequentialPreload(nextIndex + 1)
            return
        }
        preloadNext(nextIndex)
    }

    private suspend fun streamSynthAndPlay(
        text: String,
        startMs: Long,
        notifyStartup: Boolean,
        sentenceIndex: Int,
    ): SherpaPlayResult {
        val textChunks = TtsTextChunker.chunk(text, MAX_SHERPA_TEXT_CHARS)
        if (textChunks.isEmpty()) {
            return synthesisFailure(getString(R.string.tts_error_empty_text), "empty text chunks")
        }

        when (val initResult = ensureSherpaEngineForPlayback()) {
            SherpaInitResult.Success -> Unit
            SherpaInitResult.NotDownloaded -> return SherpaPlayResult.Failed(
                userMessage = getString(R.string.tts_no_model_downloaded),
                logDetail = "no default model before stream synth",
            )
            else -> return SherpaPlayResult.Failed(
                userMessage = initResult.toUserMessage(),
                logDetail = "engine init failed before stream synth: $initResult",
            )
        }

        if (!sherpaEngine.isReady()) {
            return SherpaPlayResult.Failed(
                userMessage = getString(R.string.tts_error_engine_not_ready),
                logDetail = "engine not ready before stream synth",
            )
        }

        val session = playbackSession
        val gain = StreamingGain()
        gain.reset()
        val writeState = StreamChunkWriteState(
            skipMs = startMs,
            notifyStartup = notifyStartup,
            subtitle = SubtitleUpdate(
                index = sentenceIndex,
                text = text,
                totalSentences = sentences.size,
            ),
            sentenceIndex = sentenceIndex,
            session = session,
        )
        val defaultModel = app.ttsRepository.getDefaultModel()
        val effectiveSpeakerId = withContext(sherpaDispatcher) {
            sherpaEngine.resolveSpeakerId(defaultModel?.speakerId ?: speakerId)
        }
        val sampleRate = withContext(sherpaDispatcher) { sherpaEngine.sampleRate() }
        if (sampleRate <= 0) {
            return synthesisFailure("引擎采样率无效", "sampleRate=0 sid=$effectiveSpeakerId")
        }

        var lastStats: StreamingSynthStats? = null
        var prepareFailed = false
        var framesBeforeSentence: Long? = null
        val shouldContinuePlayback = {
            isPlaying.get() && session == playbackSession
        }

        val synthesisMs = measureTimeMillis {
            withContext(sherpaDispatcher) {
                if (!preparePcmPlayer(sampleRate)) {
                    prepareFailed = true
                    return@withContext
                }
                framesBeforeSentence = pcmPlayer.framesWritten
                markSentencePlaybackStart(sampleRate)

                for (textChunk in textChunks) {
                    if (!shouldContinuePlayback()) return@withContext

                    val stats = synthesizeChunkForPlayback(
                        textChunk = textChunk,
                        effectiveSpeakerId = effectiveSpeakerId,
                        sampleRate = sampleRate,
                        gain = gain,
                        writeState = writeState,
                        shouldContinue = shouldContinuePlayback,
                        sentenceIndex = sentenceIndex,
                    )

                    if (stats == null) {
                        logStreamSynthFailed(
                            sentenceIndex = sentenceIndex,
                            textChunk = textChunk,
                            effectiveSpeakerId = effectiveSpeakerId,
                            session = session,
                            reason = "null_stats",
                        )
                        return@withContext
                    }
                    lastStats = stats
                }
            }
        }

        if (prepareFailed) {
            return SherpaPlayResult.Failed(
                userMessage = getString(R.string.tts_error_audiotrack_init),
                logDetail = "pcmPlayer.prepare failed sampleRate=$sampleRate",
            )
        }
        val framesBase = framesBeforeSentence ?: return SherpaPlayResult.Failed(
            userMessage = getString(R.string.tts_error_audiotrack_init),
            logDetail = "pcmPlayer.prepare failed sampleRate=$sampleRate",
        )

        val needsFullBatchFallback = !writeState.playbackStarted &&
            (lastStats == null || (lastStats?.totalSamples ?: 0) == 0)
        if (needsFullBatchFallback && shouldContinuePlayback()) {
            logGap(
                "stream_synth_full_batch_fallback sentenceIndex=$sentenceIndex sid=$effectiveSpeakerId",
            )
            lastStats = withContext(sherpaDispatcher) {
                batchPlayTextChunks(
                    textChunks = textChunks,
                    effectiveSpeakerId = effectiveSpeakerId,
                    sampleRate = sampleRate,
                    gain = gain,
                    writeState = writeState,
                    shouldContinue = shouldContinuePlayback,
                )
            }
        }

        // Flush the look-ahead warm-up window (short sentences may never reach the lock threshold).
        if (shouldContinuePlayback()) {
            withContext(sherpaDispatcher) {
                val tail = gain.flush()
                if (tail.isNotEmpty()) {
                    writeStreamChunk(
                        samples = tail,
                        sampleRate = sampleRate,
                        state = writeState,
                        shouldContinue = shouldContinuePlayback,
                    )
                }
            }
        }

        logGap(
            "stream_synth sentenceIndex=$sentenceIndex ms=$synthesisMs " +
                "started=${writeState.playbackStarted} totalSamples=${lastStats?.totalSamples ?: 0} " +
                "callbacks=${lastStats?.callbackInvocations ?: 0} sid=$effectiveSpeakerId",
        )

        if (!isPlaying.get() || session != playbackSession) {
            return SherpaPlayResult.Interrupted
        }
        if (!writeState.playbackStarted) {
            val estimatedDurationMs = estimateTextChunksDurationMs(
                totalSamples = lastStats?.totalSamples ?: 0,
                sampleRate = sampleRate,
            )
            return when {
                startMs > 0 &&
                    estimatedDurationMs > 0 &&
                    SentencePlaybackPosition.isSentencePositionAtEnd(startMs, estimatedDurationMs) -> {
                    logGap(
                        "stream_skip_completed sentenceIndex=$sentenceIndex startMs=$startMs " +
                            "estimatedDurationMs=$estimatedDurationMs",
                    )
                    SherpaPlayResult.Completed
                }
                lastStats == null -> diagnoseStreamSynthFailure(
                    textChunks = textChunks,
                    effectiveSpeakerId = effectiveSpeakerId,
                    sentenceIndex = sentenceIndex,
                    session = session,
                )
                (lastStats?.totalSamples ?: 0) > 0 -> SherpaPlayResult.Failed(
                    userMessage = getString(R.string.tts_error_audiotrack_init),
                    logDetail = "stream synth had samples but playback did not start",
                )
                else -> synthesisFailure(
                    getString(R.string.tts_error_synthesis_empty_audio),
                    "stream synth produced no playable audio sid=$effectiveSpeakerId",
                )
            }
        }

        waitForSentencePlayback(
            session = session,
            framesBeforeWrite = framesBase,
            framesEnd = pcmPlayer.framesWritten,
            sampleRate = sampleRate,
            progressBaseMs = startMs,
            sentenceIndex = sentenceIndex,
        )
        if (writeState.playbackStarted) {
            val head = pcmPlayer.playbackHeadFrames()
            val writtenFrames = pcmPlayer.framesWritten - framesBase
            val playedFrames = (head - framesBase).coerceAtLeast(0)
            if (writtenFrames > 0 && playedFrames == 0L) {
                return SherpaPlayResult.Failed(
                    userMessage = getString(R.string.tts_error_audiotrack_write),
                    logDetail = "stream playback head never advanced index=$sentenceIndex " +
                        "written=$writtenFrames head=$head",
                )
            }
        }
        return if (!isPlaying.get() || session != playbackSession) {
            SherpaPlayResult.Interrupted
        } else {
            SherpaPlayResult.Completed
        }
    }

    /** Streaming first; batch fallback mirrors [TtsPreviewController.playPreviewChunk]. */
    private fun synthesizeChunkForPlayback(
        textChunk: String,
        effectiveSpeakerId: Int,
        sampleRate: Int,
        gain: StreamingGain,
        writeState: StreamChunkWriteState,
        shouldContinue: () -> Boolean,
        sentenceIndex: Int,
    ): StreamingSynthStats? {
        if (!shouldContinue()) return null

        var samplesWritten = 0
        val streamStats = sherpaEngine.synthesizeStreaming(
            text = textChunk,
            speed = sherpaSpeed,
            speakerId = effectiveSpeakerId,
            shouldContinue = shouldContinue,
            onChunk = chunk@{ samples ->
                if (!shouldContinue()) return@chunk 0
                val ready = gain.process(samples)
                if (ready.isEmpty()) return@chunk 1
                val framesBefore = pcmPlayer.framesWritten
                val result = writeStreamChunk(
                    samples = ready,
                    sampleRate = sampleRate,
                    state = writeState,
                    shouldContinue = shouldContinue,
                )
                if (result < 0) return@chunk 0
                val framesAfter = pcmPlayer.framesWritten
                if (framesAfter > framesBefore) {
                    samplesWritten += (framesAfter - framesBefore).toInt()
                }
                return@chunk if (result == 0) 0 else 1
            },
        )

        if (streamStats == null || (streamStats.stoppedEarly && samplesWritten <= 0)) {
            if (streamStats != null) {
                logGap(
                    "stream_synth_batch_fallback sentenceIndex=$sentenceIndex " +
                        "stoppedEarly=${streamStats.stoppedEarly} samplesWritten=$samplesWritten " +
                        "streamSamples=${streamStats.totalSamples} sid=$effectiveSpeakerId",
                )
            }
            val batch = sherpaEngine.synthesize(textChunk, sherpaSpeed, effectiveSpeakerId)
            return playBatchChunk(batch, gain, sampleRate, writeState, shouldContinue)
        }

        return streamStats
    }

    private fun batchPlayTextChunks(
        textChunks: List<String>,
        effectiveSpeakerId: Int,
        sampleRate: Int,
        gain: StreamingGain,
        writeState: StreamChunkWriteState,
        shouldContinue: () -> Boolean,
    ): StreamingSynthStats? {
        if (!preparePcmPlayer(sampleRate)) {
            return null
        }

        var totalSamples = 0
        var invocations = 0
        for (textChunk in textChunks) {
            if (!shouldContinue()) break
            val batch = sherpaEngine.synthesize(textChunk, sherpaSpeed, effectiveSpeakerId) ?: return null
            val stats = playBatchChunk(batch, gain, sampleRate, writeState, shouldContinue) ?: return null
            totalSamples += stats.totalSamples
            invocations += stats.callbackInvocations
        }
        if (totalSamples <= 0 && !writeState.playbackStarted) return null
        return StreamingSynthStats(
            sampleRate = sampleRate,
            totalSamples = totalSamples,
            callbackInvocations = invocations,
            stoppedEarly = false,
        )
    }

    private fun writeStreamChunk(
        samples: FloatArray,
        sampleRate: Int,
        state: StreamChunkWriteState,
        shouldContinue: () -> Boolean,
    ): Int {
        if (sampleRate <= 0 || samples.isEmpty()) return 0

        val chunkDurationMs = (samples.size.toLong() * 1000) / sampleRate
        if (state.skipMs >= chunkDurationMs) {
            state.skipMs -= chunkDurationMs
            return 1
        }

        val skipSamples = if (state.skipMs > 0) ((state.skipMs * sampleRate) / 1000).toInt() else 0
        state.skipMs = 0L

        if (!pcmPlayer.isInitialized && !preparePcmPlayer(sampleRate)) {
            Log.e(TAG, "writeStreamChunk prepare failed sampleRate=$sampleRate")
            return -1
        }

        if (!state.playbackStarted) {
            if (!isPlaying.get()) return 0
            if (state.notifyStartup) {
                markOutputtingAudioIfStartup()
            }
            pcmPlayer.ensurePlaying()
            state.playbackStarted = true
            notifyStreamPlaybackStarted(state.notifyStartup, state.subtitle, state.sentenceIndex)
            state.notifyStartup = false
            state.subtitle = null
        }

        val written = if (skipSamples > 0) {
            pcmPlayer.writeSamplesFromOffset(samples, skipSamples, shouldContinue)
        } else {
            pcmPlayer.writeSamples(samples, shouldContinue)
        }
        return when {
            written < 0 && !isPlaying.get() -> 0
            written < 0 -> -1
            written == 0 -> 0
            else -> 1
        }
    }

    private fun playBatchChunk(
        batch: SynthesizedAudio?,
        gain: StreamingGain,
        sampleRate: Int,
        state: StreamChunkWriteState,
        shouldContinue: () -> Boolean,
    ): StreamingSynthStats? {
        if (batch == null || !batch.isPlayable()) return null
        val rate = batch.sampleRate.takeIf { it > 0 } ?: sampleRate
        val ready = gain.process(batch.samples)
        if (ready.isEmpty()) {
            return StreamingSynthStats(
                sampleRate = rate,
                totalSamples = batch.samples.size,
                callbackInvocations = 1,
                stoppedEarly = false,
            )
        }
        val result = writeStreamChunk(ready, rate, state, shouldContinue)
        if (result < 0) return null
        if (result == 0 && !state.playbackStarted) return null
        return StreamingSynthStats(
            sampleRate = rate,
            totalSamples = batch.samples.size,
            callbackInvocations = 1,
            stoppedEarly = result == 0,
        )
    }

    private class StreamChunkWriteState(
        var skipMs: Long,
        var notifyStartup: Boolean,
        var subtitle: SubtitleUpdate?,
        val sentenceIndex: Int,
        val session: Int,
        var playbackStarted: Boolean = false,
    )

    private fun notifyStreamPlaybackStarted(
        notifyStartup: Boolean,
        subtitle: SubtitleUpdate?,
        sentenceIndex: Int,
    ) {
        if (!isPlaying.get()) return
        PlaybackController.update { state ->
            var next = state
            if (subtitle != null) {
                next = next.copy(
                    currentSentenceIndex = subtitle.index,
                    currentSentenceText = subtitle.text,
                    totalSentences = subtitle.totalSentences,
                )
            }
            if (notifyStartup) {
                next = next.copy(
                    playbackState = PlaybackState.PLAYING,
                    loadingStage = TtsLoadingStage.NONE,
                )
            } else if (next.playbackState != PlaybackState.PLAYING) {
                next = next.copy(
                    playbackState = PlaybackState.PLAYING,
                    loadingStage = TtsLoadingStage.NONE,
                )
            }
            next
        }
        if (notifyStartup) {
            finishStartupToast()
        }
        enablePreloadsAfterPlaybackStart(sentenceIndex)
    }

    private suspend fun waitForSentencePlayback(
        session: Int,
        framesBeforeWrite: Long,
        framesEnd: Long,
        sampleRate: Int,
        progressBaseMs: Long,
        sentenceIndex: Int,
    ) {
        val framesToPlay = (framesEnd - framesBeforeWrite).coerceAtLeast(0)
        val expectedMs = if (sampleRate > 0 && framesToPlay > 0) {
            (framesToPlay * 1000) / sampleRate
        } else {
            0L
        }
        val timeoutMs = PlaybackHeadWaitPolicy.expectedTimeoutMs(framesToPlay, sampleRate)
        val waitStartMs = System.currentTimeMillis()
        withContext(Dispatchers.IO) {
            while (coroutineContext.isActive) {
                if (!isPlaying.get() || session != playbackSession) return@withContext
                val headFrames = pcmPlayer.playbackHeadFrames()
                val playedFrames = (headFrames - framesBeforeWrite).coerceAtLeast(0)
                val positionMs = progressBaseMs + (playedFrames * 1000) / sampleRate
                positionInSentenceMs = positionMs
                if (isPlaying.get()) {
                    PlaybackController.update { it.copy(positionInSentenceMs = positionMs) }
                }
                if (headFrames >= framesEnd) return@withContext
                val waitedMs = System.currentTimeMillis() - waitStartMs
                if (waitedMs >= timeoutMs) {
                    logGap(
                        "playback_head_stall index=$sentenceIndex head=$headFrames " +
                            "framesEnd=$framesEnd framesBefore=$framesBeforeWrite " +
                            "waitedMs=$waitedMs expectedMs=$expectedMs timeoutMs=$timeoutMs",
                    )
                    return@withContext
                }
                delay(20)
            }
        }
    }

    private suspend fun diagnoseStreamSynthFailure(
        textChunks: List<String>,
        effectiveSpeakerId: Int,
        sentenceIndex: Int,
        session: Int,
    ): SherpaPlayResult.Failed {
        val probeText = textChunks.first()
        val probe = withContext(sherpaDispatcher) {
            sherpaEngine.synthesize(probeText, sherpaSpeed, effectiveSpeakerId)
        }
        logGap(
            "stream_synth_diagnose sentenceIndex=$sentenceIndex sid=$effectiveSpeakerId " +
                "textLen=${probeText.length} textPreview=${textPreview(probeText)} " +
                "probeSamples=${probe?.samples?.size ?: 0} " +
                "isPlaying=${isPlaying.get()} session=$session liveSession=$playbackSession " +
                "jobActive=${playbackJob?.isActive == true}",
        )
        return if (probe == null || !probe.isPlayable()) {
            synthesisFailure(
                getString(R.string.tts_error_synthesis_empty_audio),
                "stream synth returned null stats and batch probe failed sid=$effectiveSpeakerId",
            )
        } else {
            SherpaPlayResult.Failed(
                userMessage = getString(R.string.tts_error_audiotrack_init),
                logDetail = "stream synth probe ok (${probe.samples.size} samples) but playback write failed",
            )
        }
    }

    private fun logStreamSynthFailed(
        sentenceIndex: Int,
        textChunk: String,
        effectiveSpeakerId: Int,
        session: Int,
        reason: String,
        stoppedEarly: Boolean? = null,
        samplesWritten: Int? = null,
        playbackStarted: Boolean? = null,
    ) {
        logGap(
            "stream_synth_failed sentenceIndex=$sentenceIndex reason=$reason " +
                "sid=$effectiveSpeakerId textLen=${textChunk.length} " +
                "textPreview=${textPreview(textChunk)} " +
                "stoppedEarly=$stoppedEarly samplesWritten=$samplesWritten " +
                "playbackStarted=$playbackStarted isPlaying=${isPlaying.get()} " +
                "session=$session liveSession=$playbackSession " +
                "jobActive=${playbackJob?.isActive == true}",
        )
    }

    private fun textPreview(text: String, maxChars: Int = 30): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "<empty>"
        return buildString {
            append('"')
            append(trimmed.take(maxChars))
            if (trimmed.length > maxChars) append('…')
            append('"')
        }
    }

    private suspend fun ensureSherpaEngineForPlayback(): SherpaInitResult {
        val model = app.ttsRepository.getDefaultModel() ?: return SherpaInitResult.NotDownloaded
        val result = ensureSherpaInitializedWithRetry(model)
        if (result is SherpaInitResult.Success) {
            refreshSpeakerId(model)
            activeTtsKey = sherpaInitCoordinator.initKeyFor(model)
        }
        return result
    }

    private suspend fun ensureSherpaInitializedWithRetry(model: TtsModelEntity?): SherpaInitResult {
        if (model == null ||
            model.downloadState != TtsDownloadState.DOWNLOADED.name ||
            model.localPath == null
        ) {
            return SherpaInitResult.NotDownloaded
        }
        if (!sherpaInitCoordinator.isReadyFor(model)) {
            cancelPreloads()
        }
        var result = sherpaInitCoordinator.ensureInitialized(model)
        if (shouldRetrySherpaInit(result)) {
            Log.w(TAG, "Sherpa init failed ($result), retrying after preload cleanup")
            cancelPreloads()
            System.runFinalization()
            delay(INIT_RETRY_DELAY_MS)
            result = sherpaInitCoordinator.ensureInitialized(model)
        }
        return result
    }

    private fun shouldRetrySherpaInit(result: SherpaInitResult): Boolean = when (result) {
        SherpaInitResult.ModelLoadFailed,
        SherpaInitResult.LowMemory,
        SherpaInitResult.OutOfMemory,
        -> true
        else -> false
    }

    private fun synthesisFailure(reason: String, logDetail: String): SherpaPlayResult.Failed {
        return SherpaPlayResult.Failed(
            userMessage = getString(R.string.tts_error_synthesis_failed, reason),
            logDetail = logDetail,
        )
    }

    private suspend fun releaseSherpaEngine() {
        cancelPreloads()
        sherpaInitCoordinator.invalidate()
        withContext(sherpaDispatcher) {
            sherpaEngine.release()
        }
    }

    private fun cancelPreloads() {
        synchronized(preloadStateLock) {
            if (preloadJobs.isNotEmpty() || preloadedAudio.isNotEmpty()) {
                logGap("preload_cancel jobs=${preloadJobs.size} cached=${preloadedAudio.size}")
            }
            cancelAndClearJobs(preloadJobs)
            preloadedAudio.clear()
            cancelAndClearJobs(prefetchTextJobs)
            prefetchedText.clear()
            nextPreloadIndex = -1
        }
    }

    private fun cancelAndClearJobs(jobMap: MutableMap<Int, Job>) {
        // Snapshot and clear first: cancel() may trigger completion callbacks that mutate this map.
        val jobsToCancel = jobMap.values.toList()
        jobMap.clear()
        jobsToCancel.forEach { it.cancel() }
    }

    private fun safePauseAudioTrack() {
        pcmPlayer.pauseImmediately()
    }

    private fun releaseAudioTrack() {
        audioTrackNeedsReset = true
        pcmPlayer.release()
    }

    private fun prepareForTtsPreview() {
        isPlaying.set(false)
        playbackJob?.cancel()
        startupToastPending = false
        cancelPreloads()
        pcmPlayer.signalStopRequested()
        pcmPlayer.pauseImmediately()
        serviceScope.launch {
            withContext(sherpaDispatcher) {
                // Drain in-flight Sherpa work on the shared thread before preview loads a model.
            }
            sherpaInitCoordinator.completePreviewPrepare()
        }
    }

    private fun pausePlayback(persistSession: Boolean = true) {
        if (!pauseInFlight.compareAndSet(false, true)) {
            logGap("transition_dropped kind=pause reason=in_flight ${playbackSnapshot()}")
            return
        }
        logGap("pause_enter ${playbackSnapshot()}")
        isPlaying.set(false)
        wakeLock.release()
        if (persistSession) {
            persistPlaybackSession(shouldResumeOnRestart = false)
        }
        pcmPlayer.signalStopRequested()
        pcmPlayer.pauseImmediately()
        startupToastPending = false
        playbackSession++
        startPlaybackJob?.cancel()
        startPlaybackJob = null
        PlaybackController.update {
            it.copy(playbackState = PlaybackState.PAUSED, loadingStage = TtsLoadingStage.NONE)
        }
        playFromCurrentJob?.cancel()
        playFromCurrentJob = null
        drainedPlaybackJob = playbackJob
        playbackJob?.cancel()
        playbackJob = null
        submitAudioStop(snapshotPosition = true)
        pauseFinalizeJob?.cancel()
        val pauseSessionAtStart = playbackSession
        pauseFinalizeJob = serviceScope.launch {
            try {
                awaitAudioStop(SEEK_AUDIO_STOP_TIMEOUT_MS)
                if (shouldSkipPauseFinalize(pauseSessionAtStart)) {
                    logGap("pause_finalize_skip reason=resumed ${playbackSnapshot()}")
                    return@launch
                }
                sanitizeCurrentSentencePosition()
                if (shouldSkipPauseFinalize(pauseSessionAtStart)) {
                    logGap("pause_finalize_skip reason=resumed ${playbackSnapshot()}")
                    return@launch
                }
                advanceIfSentenceAlreadyFinished(saveProgress = false)
                if (shouldSkipPauseFinalize(pauseSessionAtStart)) {
                    logGap("pause_finalize_skip reason=resumed ${playbackSnapshot()}")
                    return@launch
                }
                saveProgress()
                if (shouldSkipPauseFinalize(pauseSessionAtStart)) {
                    logGap("pause_finalize_skip reason=resumed ${playbackSnapshot()}")
                    return@launch
                }
                cancelPreloads()
                invalidateSentenceFrameSnapshot()
                updateNotification(false)
                logGap("pause_exit ${playbackSnapshot()}")
            } finally {
                pauseInFlight.set(false)
                pauseFinalizeJob = null
            }
        }
    }

    private fun resumePlayback() {
        if (!resumeInFlight.compareAndSet(false, true)) {
            logGap("transition_dropped kind=resume reason=in_flight ${playbackSnapshot()}")
            return
        }
        logGap("resume_enter ${playbackSnapshot()}")
        if (sentences.isEmpty()) {
            if (bookId.isNotEmpty()) launchStartPlayback(bookId)
            resumeInFlight.set(false)
            return
        }
        if (isPlaying.get() &&
            (playbackJob?.isActive == true || playFromCurrentJob?.isActive == true)
        ) {
            logGap("resume_already_active ${playbackSnapshot()}")
            resumeInFlight.set(false)
            return
        }

        val signal = CompletableDeferred<Unit>()
        resumeStartSignal = signal
        playbackScope.launch {
            try {
                awaitPauseTransitionComplete()
                if (!sherpaEngine.isReady()) {
                    val defaultModel = app.ttsRepository.getDefaultModel()
                    when (val initResult = ensureSherpaInitializedWithRetry(defaultModel)) {
                        SherpaInitResult.Success -> {
                            refreshSpeakerId(defaultModel)
                            activeTtsKey = sherpaInitCoordinator.initKeyFor(defaultModel)
                            beginResumePlayback()
                        }
                        else -> failPlayback(initResult.toUserMessage())
                    }
                } else {
                    beginResumePlayback()
                }
                val started = withTimeoutOrNull(RESUME_LOOP_START_TIMEOUT_MS) {
                    signal.await()
                    true
                } ?: false
                if (!started) {
                    logGap("resume_loop_start_timeout ${playbackSnapshot()}")
                    if (isPlaying.get() && currentIndex < sentences.size) {
                        schedulePlaybackRecoveryIfNeeded()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "resumePlayback failed", t)
            } finally {
                logGap("resume_exit ${playbackSnapshot()}")
                resumeStartSignal?.complete(Unit)
                resumeStartSignal = null
                resumeInFlight.set(false)
            }
        }
    }

    private fun startPlaybackPipeline(forceRestart: Boolean) {
        sanitizeCurrentSentencePosition()
        advanceIfSentenceAlreadyFinished()
        if (currentIndex >= sentences.size) {
            pausePlayback()
            PlaybackController.update { it.copy(playbackState = PlaybackState.STOPPED) }
            return
        }
        isPlaying.set(true)
        PlaybackController.update {
            it.copy(playbackState = PlaybackState.PLAYING, loadingStage = TtsLoadingStage.NONE)
        }
        preloadsEnabled = true
        playFromCurrent(forceRestart = forceRestart)
    }

    private fun beginResumePlayback() {
        startPlaybackPipeline(forceRestart = true)
    }

    private fun applySpeedChange() {
        if (isPlaying.get()) {
            showTtsToast(R.string.tts_applying_speed)
            restartPlaybackFromCurrent(clearPreloads = true)
        } else {
            cancelPreloads()
            releaseAudioTrack()
        }
    }

    private suspend fun syncTtsEngine() {
        val app = application as VoicebookApp
        val defaultModel = app.ttsRepository.getDefaultModel()
        when (val initResult = ensureSherpaEngineForPlayback()) {
            SherpaInitResult.Success -> Unit
            else -> {
                if (isPlaying.get()) {
                    failPlayback(initResult.toUserMessage())
                }
                return
            }
        }
        refreshSpeakerId(defaultModel)
        activeTtsKey = sherpaInitCoordinator.initKeyFor(defaultModel)
        preloadsEnabled = false
        cancelPreloads()
        if (isPlaying.get()) {
            restartPlaybackFromCurrent(clearPreloads = true)
        }
    }

    private suspend fun reloadTtsEngine() {
        val app = application as VoicebookApp
        showTtsToast(R.string.tts_reloading_engine)
        val wasPlaying = isPlaying.get()
        if (wasPlaying) {
            isPlaying.set(false)
            drainedPlaybackJob = playbackJob
            playbackJob?.cancel()
            playbackJob = null
            safePauseAudioTrack()
        }
        when (val initResult = awaitReinitializeTts(app.ttsRepository.getDefaultModel())) {
            SherpaInitResult.Success -> {
                preloadsEnabled = true
                if (wasPlaying) startPlaybackPipeline(forceRestart = true)
            }
            else -> failPlayback(initResult.toUserMessage())
        }
    }

    private fun SherpaInitResult.toUserMessage(): String = when (this) {
        SherpaInitResult.NotDownloaded -> getString(R.string.tts_no_model_downloaded)
        SherpaInitResult.LowMemory -> getString(
            R.string.tts_init_low_memory,
            SherpaInitCoordinator.MIN_FREE_MB,
        )
        SherpaInitResult.OutOfMemory -> getString(R.string.tts_init_oom)
        SherpaInitResult.ModelLoadFailed -> getString(R.string.tts_init_failed)
        is SherpaInitResult.Error -> getString(R.string.tts_init_failed_detail, detail)
        SherpaInitResult.Success -> ""
    }

    private suspend fun refreshSpeakerId(model: TtsModelEntity?) {
        speakerId = model?.speakerId ?: 0
        if (sherpaEngine.isReady()) {
            speakerId = withContext(sherpaDispatcher) { sherpaEngine.resolveSpeakerId(speakerId) }
        }
    }

    private suspend fun awaitReinitializeTts(defaultModel: TtsModelEntity?): SherpaInitResult {
        val initKey = sherpaInitCoordinator.initKeyFor(defaultModel)
        if (defaultModel == null || initKey == null) {
            return SherpaInitResult.NotDownloaded
        }

        val cacheHit = sherpaInitCoordinator.isReadyFor(defaultModel)
        if (!cacheHit) {
            setLoadingStage(TtsLoadingStage.INIT_ENGINE)
            showTtsToast(R.string.tts_startup_init_engine)
            if (defaultModel.downloadState == TtsDownloadState.DOWNLOADED.name &&
                defaultModel.localPath != null
            ) {
                setLoadingStage(TtsLoadingStage.INIT_SHERPA)
                showTtsToast(R.string.tts_startup_init_sherpa, defaultModel.name)
            }
        } else {
            logGap("engine_cache_hit key=$initKey")
        }

        cancelPreloads()
        val result = ensureSherpaInitializedWithRetry(defaultModel)

        if (result is SherpaInitResult.Success) {
            refreshSpeakerId(defaultModel)
            activeTtsKey = initKey
            preloadsEnabled = false
        } else {
            if (activeTtsKey != null) {
                releaseSherpaEngine()
                releaseAudioTrack()
                activeTtsKey = null
            }
        }

        return result
    }

    private fun stopPlayback() {
        invalidateToastSession()
        startPlaybackJob?.cancel()
        startPlaybackJob = null
        pausePlayback(persistSession = false)
        playbackSessionStore.clear()
        PlaybackController.reset()
        bookId = ""
        sentences = emptyList()
        if (isForegroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundStarted = false
        }
        stopSelf()
    }

    private fun abortStartup() {
        startupToastPending = false
        PlaybackController.update {
            it.copy(loadingStage = TtsLoadingStage.NONE)
        }
        if (isForegroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundStarted = false
        }
        stopSelf()
    }

    private fun ensureForeground(playing: Boolean = isPlaying.get()): Boolean {
        if (isForegroundStarted) return true
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(playing),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
            isForegroundStarted = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start playback foreground service", t)
            false
        }
    }

    private fun skipSentence(offset: Int) {
        if (sentences.isEmpty() || offset == 0) return
        val newIndex = (currentIndex + offset).coerceIn(0, sentences.lastIndex)
        if (newIndex == currentIndex) return
        val wasPlaying = isPlaying.get()
        seekToSentenceImmediate(newIndex, resume = wasPlaying)
    }

    private fun seekToSentenceImmediate(index: Int, resume: Boolean) {
        if (!seekInFlight.compareAndSet(false, true)) {
            logGap("transition_dropped kind=seek reason=in_flight ${playbackSnapshot()}")
            return
        }
        val generation = ++seekGeneration
        playFromCurrentJob?.cancel()
        playFromCurrentJob = null
        currentIndex = index.coerceIn(0, (sentences.size - 1).coerceAtLeast(0))
        positionInSentenceMs = 0
        invalidateSentenceFrameSnapshot()
        PlaybackController.update {
            it.copy(
                currentSentenceIndex = currentIndex,
                currentSentenceText = sentences.getOrNull(currentIndex)?.textPreview ?: "",
                positionInSentenceMs = 0,
            )
        }
        cancelPreloads()
        val previousJob = interruptPlayback()
        serviceScope.launch {
            try {
                if (generation != seekGeneration) return@launch
                awaitAudioStop(SEEK_AUDIO_STOP_TIMEOUT_MS)
                if (generation != seekGeneration) return@launch
                lastSentenceEndMs = 0L
                awaitPlaybackDrain(previousJob)
                if (generation != seekGeneration) return@launch
                if (resume) {
                    if (sentences.isEmpty()) return@launch
                    if (!sherpaEngine.isReady()) {
                        failPlayback(getString(R.string.tts_error_engine_not_ready))
                        return@launch
                    }
                    showTtsToast(R.string.tts_seek_preparing)
                    preloadsEnabled = true
                    startPlaybackPipeline(forceRestart = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "seekToSentenceImmediate failed index=$index resume=$resume", t)
                handlePlaybackFailure(t)
            } finally {
                seekInFlight.set(false)
            }
        }
    }

    private fun clearSleepTimer(persist: Boolean = true) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepUntilEndOfBook = false
        PlaybackController.update {
            it.copy(sleepTimerRemainingMs = 0, sleepUntilEndOfBook = false)
        }
        if (persist) {
            serviceScope.launch {
                (application as VoicebookApp).settingsRepository.clearSleepTimer()
            }
        }
    }

    private fun scheduleSleepTimer(minutes: Int, untilEnd: Boolean) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        if (!untilEnd && minutes <= 0) {
            clearSleepTimer(persist = false)
            return
        }
        sleepUntilEndOfBook = untilEnd
        if (untilEnd) {
            PlaybackController.update {
                it.copy(sleepTimerRemainingMs = 0, sleepUntilEndOfBook = true)
            }
            return
        }
        val totalMs = minutes * 60_000L
        sleepTimerJob = serviceScope.launch {
            var remaining = totalMs
            while (remaining > 0 && isActive) {
                PlaybackController.update {
                    it.copy(sleepTimerRemainingMs = remaining, sleepUntilEndOfBook = false)
                }
                delay(1000)
                remaining -= 1000
            }
            PlaybackController.update { it.copy(sleepTimerRemainingMs = 0) }
            pausePlayback()
            clearSleepTimer(persist = true)
        }
    }

    private fun saveProgressAsync() {
        if (isPlaying.get()) {
            persistPlaybackSession(shouldResumeOnRestart = true)
        }
        serviceScope.launch { saveProgress() }
    }

    private suspend fun saveProgress() {
        if (bookId.isEmpty()) return
        (application as VoicebookApp).bookRepository.saveProgress(
            bookId, currentIndex, positionInSentenceMs,
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.playback_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.playback_notification_desc)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(playing: Boolean): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            REQ_CONTENT,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggleAction = if (playing) ACTION_PAUSE else ACTION_RESUME
        val toggleLabel = getString(if (playing) R.string.pause else R.string.play)
        val toggleIcon = if (playing) R.drawable.ic_notif_pause else R.drawable.ic_notification_playback
        val togglePendingIntent = PendingIntent.getService(
            this,
            REQ_TOGGLE,
            controlIntent(this, toggleAction),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQ_STOP,
            controlIntent(this, ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(bookTitle.ifBlank { getString(R.string.app_name) })
            .setContentText(PlaybackController.state.value.currentSentenceText.take(60))
            .setSmallIcon(R.drawable.ic_notification_playback)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(toggleIcon, toggleLabel, togglePendingIntent)
            .addAction(
                R.drawable.ic_notif_stop,
                getString(R.string.stop_playback),
                stopPendingIntent,
            )
            .build()
    }

    private fun updateNotification(playing: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(playing))
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            val cachedCount = synchronized(preloadStateLock) {
                trimPreloadCacheInLock()
                preloadedAudio.size
            }
            logGap("trim_memory level=$level preloaded=$cachedCount")
        }
    }

    override fun onDestroy() {
        if (runningInstance === this) runningInstance = null
        if (::wakeLock.isInitialized) {
            wakeLock.release()
        }
        PlaybackController.setImmediatePauseHandler(null)
        PlaybackController.setImmediateResumeHandler(null)
        playbackJob?.cancel()
        playbackScope.cancel()
        clearSleepTimer(persist = false)
        cancelPreloads()
        serviceScope.cancel()
        try {
            audioStopLatch.await(200, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        audioControlExecutor.execute { pcmPlayer.release() }
        audioControlExecutor.shutdown()
        if (!audioControlExecutor.awaitTermination(300, TimeUnit.MILLISECONDS)) {
            audioControlExecutor.shutdownNow()
        }
        super.onDestroy()
    }

    private sealed class SherpaPlayResult {
        data object Completed : SherpaPlayResult()
        data object Interrupted : SherpaPlayResult()
        data class Failed(val userMessage: String, val logDetail: String) : SherpaPlayResult()
    }

    private data class PreloadedSentence(
        val pcmChunks: List<FloatArray>,
        val sampleRate: Int,
        val synthesisMs: Long = 0L,
    ) {
        fun isPlayable(): Boolean = pcmChunks.isNotEmpty() && sampleRate > 0 &&
            pcmChunks.all { it.isNotEmpty() }
    }

    private data class SubtitleUpdate(
        val index: Int,
        val text: String,
        val totalSentences: Int,
    )

    companion object {
        private const val TAG = "TtsPlaybackService"
        private const val GAP_DEBUG_TAG = "TtsGapDebug"

        @Volatile
        private var runningInstance: TtsPlaybackService? = null

        /** Pause audio immediately without waiting for a service Intent (used from the player UI). */
        fun pausePlaybackIfActive() {
            runningInstance?.pausePlayback()
        }

        fun isRunning(): Boolean = runningInstance != null

        /** Atomically seek and optionally resume without Intent round-trip (used from the player UI). */
        fun seekToSentenceIfRunning(index: Int, resume: Boolean) {
            runningInstance?.seekToSentenceImmediate(index, resume)
        }

        const val ACTION_PLAY = "com.dafei.voicebook.PLAY"
        const val ACTION_PAUSE = "com.dafei.voicebook.PAUSE"
        const val ACTION_RESUME = "com.dafei.voicebook.RESUME"
        const val ACTION_TOGGLE = "com.dafei.voicebook.TOGGLE"
        const val ACTION_REWIND = "com.dafei.voicebook.REWIND"
        const val ACTION_FORWARD = "com.dafei.voicebook.FORWARD"
        const val ACTION_STOP = "com.dafei.voicebook.STOP"
        const val ACTION_SET_SPEED = "com.dafei.voicebook.SET_SPEED"
        const val ACTION_RELOAD_TTS = "com.dafei.voicebook.RELOAD_TTS"
        const val ACTION_SYNC_TTS_ENGINE = "com.dafei.voicebook.SYNC_TTS_ENGINE"
        const val ACTION_PREPARE_TTS_PREVIEW = "com.dafei.voicebook.PREPARE_TTS_PREVIEW"
        const val ACTION_SET_SLEEP = "com.dafei.voicebook.SET_SLEEP"
        const val ACTION_SEEK_SENTENCE = "com.dafei.voicebook.SEEK_SENTENCE"
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_SLEEP_MINUTES = "sleep_minutes"
        const val EXTRA_SLEEP_END = "sleep_end"
        const val EXTRA_SENTENCE_INDEX = "sentence_index"
        private const val CHANNEL_ID = "voicebook_playback"
        private const val NOTIFICATION_ID = 1001
        private const val REQ_CONTENT = 0
        private const val REQ_TOGGLE = 1
        private const val REQ_STOP = 2
        /** Sherpa synthesis above this length risks OOM on low-RAM devices. */
        private const val MAX_SHERPA_TEXT_CHARS = 400
        private const val MIN_SHERPA_SPEED = 0.5f
        private const val MAX_SHERPA_SPEED = 3.0f
        /** Keep enough sentences buffered when synthesis is slower than playback. */
        private const val MAX_PRELOAD_AHEAD = 8
        private const val INIT_RETRY_DELAY_MS = 200L
        private const val GAP_WARN_MS = 300L
        private const val PRELOAD_AWAIT_POLLS = 40
        private const val PRELOAD_AWAIT_POLL_MS = 25L
        private const val SEEK_AUDIO_STOP_TIMEOUT_MS = 1000L
        private const val PAUSE_FINALIZE_AWAIT_TIMEOUT_MS = 1500L
        private const val RESUME_LOOP_START_TIMEOUT_MS = 2000L

        fun playIntent(context: Context, bookId: String): Intent {
            return Intent(context, TtsPlaybackService::class.java).apply {
                action = ACTION_PLAY
                putExtra(EXTRA_BOOK_ID, bookId)
            }
        }

        fun controlIntent(context: Context, action: String): Intent {
            return Intent(context, TtsPlaybackService::class.java).apply { this.action = action }
        }
    }
}
