package com.dafei.voicebook

import android.app.Application
import android.util.Log
import com.dafei.voicebook.data.FileStorageManager
import com.dafei.voicebook.data.SettingsRepository
import com.dafei.voicebook.data.db.AppDatabase
import com.dafei.voicebook.data.repository.BookRepository
import com.dafei.voicebook.data.repository.TtsRepository
import com.dafei.voicebook.domain.playback.PlaybackController
import com.dafei.voicebook.data.model.TtsDownloadState
import com.dafei.voicebook.domain.startup.StartupIssue
import com.dafei.voicebook.domain.startup.StartupIssueClassifier
import com.dafei.voicebook.tts.SherpaInitCoordinator
import com.dafei.voicebook.tts.SherpaInitResult
import com.dafei.voicebook.tts.SherpaOnnxEngine
import com.dafei.voicebook.tts.TtsPreviewController
import com.dafei.voicebook.tts.TtsSettingsGate
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class VoicebookApp : Application() {
    private var defaultUncaughtHandler: Thread.UncaughtExceptionHandler? = null
    @Volatile
    private var startupGuardActive = true
    @Volatile
    private var libraryRecoveryPromptShown = false
    private val _startupIssue = MutableStateFlow<StartupIssue?>(null)
    val startupIssue: StateFlow<StartupIssue?> = _startupIssue.asStateFlow()

    private val appExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled app coroutine error", throwable)
        reportStartupIssue(throwable)
    }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + appExceptionHandler)

    lateinit var database: AppDatabase
        private set
    lateinit var fileStorage: FileStorageManager
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var bookRepository: BookRepository
        private set
    lateinit var ttsRepository: TtsRepository
        private set

    /** Playback Sherpa engine — shared with [TtsPlaybackService]. */
    val sherpaEngine = SherpaOnnxEngine()
    val sherpaInitCoordinator by lazy { SherpaInitCoordinator(sherpaEngine, applicationContext) }

    /** Isolated preview engine so audition never releases/reloads the playback native session. */
    private val sherpaPreviewEngine = SherpaOnnxEngine()
    private val sherpaPreviewInitCoordinator by lazy {
        SherpaInitCoordinator(sherpaPreviewEngine, applicationContext)
    }
    val ttsSettingsGate = TtsSettingsGate()
    val ttsPreviewController by lazy {
        TtsPreviewController(sherpaPreviewEngine, sherpaPreviewInitCoordinator)
    }

    override fun onCreate() {
        super.onCreate()
        installUncaughtHandler()
        try {
            database = AppDatabase.getInstance(this)
            fileStorage = FileStorageManager(this)
            settingsRepository = SettingsRepository(this)
            runBlocking {
                settingsRepository.clearSleepTimer()
            }
            bookRepository = BookRepository(this, database, fileStorage)
            ttsRepository = TtsRepository(this, database, fileStorage, settingsRepository)

            appScope.launch {
                runCatching { database.openHelper.writableDatabase }
                    .onFailure { reportStartupIssue(it) }
            }
            appScope.launch {
                runCatching { bookRepository.healOversizedBookRows() }
                    .onFailure { reportStartupIssue(it) }
            }
            appScope.launch {
                runCatching {
                    ttsRepository.ensureBuiltInModels()
                    warmupDefaultSherpaEngine()
                }.onFailure { reportStartupIssue(it) }
            }
            appScope.launch {
                runCatching {
                    val speed = settingsRepository.getPlaybackSpeed()
                    PlaybackController.update { it.copy(speed = speed) }
                }.onFailure { reportStartupIssue(it) }
            }
            appScope.launch {
                delay(15_000)
                startupGuardActive = false
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Fatal error during app init", t)
            reportStartupIssue(t)
        }
    }

    fun clearStartupIssue() {
        _startupIssue.value = null
    }

    fun shouldShowLibraryRecoveryPrompt(): Boolean = !libraryRecoveryPromptShown

    fun markLibraryRecoveryPromptShown() {
        libraryRecoveryPromptShown = true
    }

    private fun reportStartupIssue(throwable: Throwable) {
        if (_startupIssue.value == null) {
            _startupIssue.value = StartupIssueClassifier.classify(throwable)
        }
    }

    private fun installUncaughtHandler() {
        if (defaultUncaughtHandler != null) return
        defaultUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "Uncaught thread exception on ${thread.name}", throwable)
            reportStartupIssue(throwable)
            if (!startupGuardActive) {
                defaultUncaughtHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private suspend fun warmupDefaultSherpaEngine() {
        val model = ttsRepository.getDefaultModel() ?: return
        if (model.downloadState != TtsDownloadState.DOWNLOADED.name || model.localPath == null) return
        val result = sherpaInitCoordinator.ensureInitialized(model)
        if (result is SherpaInitResult.Success) {
            sherpaInitCoordinator.withSherpaThread {
                val speakerId = sherpaEngine.resolveSpeakerId(model.speakerId)
                sherpaEngine.synthesize(".", 1.0f, speakerId)
            }
        }
    }

    companion object {
        private const val TAG = "VoicebookApp"
    }
}
