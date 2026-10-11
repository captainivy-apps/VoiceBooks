package com.dafei.voicebook.ui.settings

import android.app.Application
import android.widget.Toast
import com.dafei.voicebook.ui.AppToast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dafei.voicebook.R
import com.dafei.voicebook.data.UserSettings
import com.dafei.voicebook.VoicebookApp
import com.dafei.voicebook.data.db.TtsModelEntity
import com.dafei.voicebook.data.model.TtsDownloadState
import com.dafei.voicebook.data.model.TtsModelBenchmark
import com.dafei.voicebook.data.model.TtsModelFamily
import com.dafei.voicebook.domain.playback.PlaybackController
import com.dafei.voicebook.domain.playback.PlaybackState
import com.dafei.voicebook.service.TtsPlaybackService
import com.dafei.voicebook.service.UploadServerService
import com.dafei.voicebook.tts.BuiltInTtsModels
import com.dafei.voicebook.tts.EngineSwitchState
import com.dafei.voicebook.tts.SherpaInitResult
import com.dafei.voicebook.tts.TtsPreviewOutcome
import com.dafei.voicebook.util.BatteryOptimizationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

data class SettingsUiState(
    val keepScreenOn: Boolean = true,
    val isIgnoringBatteryOptimizations: Boolean = true,
    val ttsModels: List<TtsModelEntity> = emptyList(),
    val downloadProgress: Map<String, Float> = emptyMap(),
    val selectedTab: Int = 0,
    val selectedFamily: TtsModelFamily = TtsModelFamily.VITS,
    val selectedLanguageFilter: String? = null,
    val pendingDefaultModelId: String? = null,
    val pendingSpeakerIds: Map<String, Int> = emptyMap(),
    val hasPendingChanges: Boolean = false,
    val engineSwitchState: EngineSwitchState = EngineSwitchState.Idle,
    val engineSwitchError: String? = null,
    val benchmarks: Map<String, TtsModelBenchmark> = emptyMap(),
    val previewingModelId: String? = null,
    val draftInitialized: Boolean = false,
    val previewText: String = "",
    val mirrorBaseUrl: String = "",
    val uploadServerEnabled: Boolean = false,
    val uploadServerAddress: String? = null,
    val rebuildRunning: Boolean = false,
    val rebuildStage: com.dafei.voicebook.data.repository.BookRepository.RebuildStage =
        com.dafei.voicebook.data.repository.BookRepository.RebuildStage.IDLE,
    val rebuildTotalFiles: Int = 0,
    val rebuildProcessedFiles: Int = 0,
    val rebuildSuccessCount: Int = 0,
    val rebuildFailedCount: Int = 0,
    val rebuildCurrentFileName: String? = null,
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as VoicebookApp
    private val _selectedTab = MutableStateFlow(0)
    private val _selectedFamily = MutableStateFlow(TtsModelFamily.VITS)
    private val _selectedLanguageFilter = MutableStateFlow<String?>(null)
    private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val _pendingDefaultModelId = MutableStateFlow<String?>(null)
    private val _pendingSpeakerIds = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _engineSwitchState = MutableStateFlow(EngineSwitchState.Idle)
    private val _engineSwitchError = MutableStateFlow<String?>(null)
    private val _previewingModelId = MutableStateFlow<String?>(null)
    private val _draftInitialized = MutableStateFlow(false)
    private val _isIgnoringBatteryOptimizations = MutableStateFlow(
        BatteryOptimizationHelper.isIgnoringBatteryOptimizations(application),
    )
    private val _uploadServerEnabled = MutableStateFlow(UploadServerService.isRunning())
    private val activeDownloads = ConcurrentHashMap.newKeySet<String>()
    private var previewJob: Job? = null

    private val ttsDraftState = combine(
        combine(
            app.ttsRepository.models,
            app.ttsRepository.benchmarks,
            _selectedFamily,
            _downloadProgress,
            _pendingDefaultModelId,
        ) { models, benchmarkList, family, progress, pendingDefault ->
            TtsDraftInputs(
                models = models,
                benchmarkList = benchmarkList,
                family = family,
                progress = progress,
                pendingDefault = pendingDefault,
            )
        },
        combine(
            _pendingSpeakerIds,
            _engineSwitchState,
            _engineSwitchError,
            _previewingModelId,
            _draftInitialized,
        ) { pendingSpeakers, switchState, switchError, previewingId, draftInit ->
            TtsDraftUiInputs(
                pendingSpeakers = pendingSpeakers,
                switchState = switchState,
                switchError = switchError,
                previewingId = previewingId,
                draftInit = draftInit,
            )
        },
    ) { repo, ui ->
        val persistedDefault = repo.models.firstOrNull { it.isDefault }?.id
        val hasPending = repo.pendingDefault != null && (
            repo.pendingDefault != persistedDefault ||
                repo.models.any { model ->
                    val pending = ui.pendingSpeakers[model.id] ?: model.speakerId
                    pending != model.speakerId
                }
            )
        TtsDraftBundle(
            models = repo.models,
            benchmarks = repo.benchmarkList.associateBy { benchmarkKey(it.modelId, it.speakerId) },
            selectedFamily = repo.family,
            downloadProgress = repo.progress,
            pendingDefaultModelId = repo.pendingDefault,
            pendingSpeakerIds = ui.pendingSpeakers,
            hasPendingChanges = hasPending,
            engineSwitchState = ui.switchState,
            engineSwitchError = ui.switchError,
            previewingModelId = ui.previewingId,
            draftInitialized = ui.draftInit,
        )
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(
            app.settingsRepository.settings,
            _selectedTab,
            ttsDraftState,
            _isIgnoringBatteryOptimizations,
            _selectedLanguageFilter,
        ) { settings, tab, tts, isIgnoringBatteryOptimizations, languageFilter ->
            GeneralSettingsBundle(settings, tab, tts, isIgnoringBatteryOptimizations, languageFilter)
        },
        UploadServerService.serverAddress,
        _uploadServerEnabled,
        app.bookRepository.libraryRebuildProgress,
    ) { bundle, serverAddress, uploadServerEnabled, rebuildProgress ->
        val draftModel = resolveDraftModel(bundle.tts.models, bundle.tts.pendingDefaultModelId)
        val defaultPreview = draftModel?.let { previewTextFor(it) }
            ?: getApplication<Application>().getString(R.string.tts_preview_sample_zh)
        SettingsUiState(
            keepScreenOn = bundle.settings.keepScreenOn,
            isIgnoringBatteryOptimizations = bundle.isIgnoringBatteryOptimizations,
            ttsModels = bundle.tts.models,
            downloadProgress = bundle.tts.downloadProgress,
            selectedTab = bundle.tab,
            selectedFamily = bundle.tts.selectedFamily,
            selectedLanguageFilter = bundle.languageFilter,
            pendingDefaultModelId = bundle.tts.pendingDefaultModelId,
            pendingSpeakerIds = bundle.tts.pendingSpeakerIds,
            hasPendingChanges = bundle.tts.hasPendingChanges,
            engineSwitchState = bundle.tts.engineSwitchState,
            engineSwitchError = bundle.tts.engineSwitchError,
            benchmarks = bundle.tts.benchmarks,
            previewingModelId = bundle.tts.previewingModelId,
            draftInitialized = bundle.tts.draftInitialized,
            previewText = bundle.settings.ttsPreviewText.ifBlank { defaultPreview },
            mirrorBaseUrl = bundle.settings.mirrorBaseUrl,
            uploadServerEnabled = uploadServerEnabled,
            uploadServerAddress = if (uploadServerEnabled) serverAddress else null,
            rebuildRunning = rebuildProgress.isRunning,
            rebuildStage = rebuildProgress.stage,
            rebuildTotalFiles = rebuildProgress.totalFiles,
            rebuildProcessedFiles = rebuildProgress.processedFiles,
            rebuildSuccessCount = rebuildProgress.successCount,
            rebuildFailedCount = rebuildProgress.failedCount,
            rebuildCurrentFileName = rebuildProgress.currentFileName,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsUiState())

    private data class GeneralSettingsBundle(
        val settings: UserSettings,
        val tab: Int,
        val tts: TtsDraftBundle,
        val isIgnoringBatteryOptimizations: Boolean,
        val languageFilter: String?,
    )

    init {
        viewModelScope.launch {
            app.ttsRepository.models.first { it.isNotEmpty() }.let { models ->
                if (!_draftInitialized.value) {
                    syncDraftFromPersisted(models)
                    _draftInitialized.value = true
                }
            }
        }
    }

    fun setSelectedTab(tab: Int) {
        if (tab == 0 && _selectedTab.value == 1) {
            if (_engineSwitchState.value == EngineSwitchState.Applying) return
        }
        _selectedTab.value = tab
    }

    fun setSelectedFamily(family: TtsModelFamily) {
        if (_engineSwitchState.value != EngineSwitchState.Idle) return
        _selectedFamily.value = family
        _selectedLanguageFilter.value = null
    }

    fun setLanguageFilter(groupId: String?) {
        if (_engineSwitchState.value != EngineSwitchState.Idle) return
        _selectedLanguageFilter.value = groupId
    }

    fun setKeepScreenOn(enabled: Boolean) {
        viewModelScope.launch {
            app.settingsRepository.setKeepScreenOn(enabled)
        }
    }

    fun setUploadServerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            _uploadServerEnabled.value = enabled
            val context = getApplication<Application>()
            if (enabled) {
                UploadServerService.start(context)
            } else {
                UploadServerService.stop(context)
            }
        }
    }

    fun copyUploadServerAddress(address: String) {
        val context = getApplication<Application>()
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("upload_url", address))
        AppToast.show(context, context.getString(R.string.upload_server_address_copied))
    }

    fun rebuildLibrary() {
        if (uiState.value.rebuildRunning) {
            viewModelScope.launch {
                AppToast.show(getApplication(), getApplication<Application>().getString(R.string.rebuild_library_running))
            }
            return
        }
        app.appScope.launch {
            app.bookRepository.rebuildLibraryFromStorage()
            val progress = app.bookRepository.libraryRebuildProgress.value
            val context = getApplication<Application>()
            AppToast.show(
                context,
                context.getString(
                    R.string.rebuild_library_done_summary,
                    progress.successCount,
                    progress.failedCount,
                ),
                Toast.LENGTH_LONG,
            )
        }
    }

    fun refreshBatteryOptimizationStatus() {
        _isIgnoringBatteryOptimizations.value =
            BatteryOptimizationHelper.isIgnoringBatteryOptimizations(getApplication())
    }

    fun openBatteryOptimizationSettings() {
        val context = getApplication<Application>()
        val opened = BatteryOptimizationHelper.openRequestSettings(context)
        if (!opened) {
            viewModelScope.launch {
                AppToast.show(
                    context,
                    context.getString(R.string.battery_optimization_open_failed),
                    Toast.LENGTH_LONG,
                )
            }
        }
    }

    fun downloadModel(modelId: String, useMirror: Boolean = false) {
        if (!activeDownloads.add(modelId)) return
        app.appScope.launch {
            try {
                app.ttsRepository.downloadModel(modelId, useMirror) { progress ->
                    _downloadProgress.value = _downloadProgress.value + (modelId to progress)
                }
            } finally {
                activeDownloads.remove(modelId)
                _downloadProgress.value = _downloadProgress.value - modelId
            }
        }
    }

    fun selectDefaultDraft(modelId: String) {
        if (_engineSwitchState.value != EngineSwitchState.Idle) return
        _pendingDefaultModelId.value = modelId
        _engineSwitchError.value = null
    }

    fun selectSpeakerDraft(modelId: String, speakerId: Int) {
        if (_engineSwitchState.value != EngineSwitchState.Idle) return
        val maxSpeaker = BuiltInTtsModels.infoFor(modelId)?.speakerCount ?: 1
        _pendingSpeakerIds.value = _pendingSpeakerIds.value +
            (modelId to speakerId.coerceIn(0, maxSpeaker - 1))
        _engineSwitchError.value = null
    }

    fun discardPendingTtsSettings() {
        if (_engineSwitchState.value == EngineSwitchState.Applying) return
        viewModelScope.launch {
            val models = app.ttsRepository.models.first()
            syncDraftFromPersisted(models)
            _engineSwitchError.value = null
            restorePersistedEngineIfNeeded()
        }
    }

    fun applyPendingTtsSettings() {
        val state = uiState.value
        if (!state.hasPendingChanges || state.engineSwitchState != EngineSwitchState.Idle) return

        viewModelScope.launch {
            _engineSwitchState.value = EngineSwitchState.Applying
            _engineSwitchError.value = null
            app.ttsSettingsGate.setBlocking(true)
            app.ttsPreviewController.stopPlayback()
            showToast(R.string.tts_apply_loading)

            val pendingDefaultId = state.pendingDefaultModelId ?: return@launch finishApplyFailed("未选择默认引擎")
            val model = app.ttsRepository.getModel(pendingDefaultId)
                ?: return@launch finishApplyFailed("找不到所选引擎")
            val speakerId = state.pendingSpeakerIds[pendingDefaultId] ?: model.speakerId
            val target = model.copy(speakerId = speakerId)

            pausePlaybackIfNeeded()
            showToast(R.string.tts_startup_init_sherpa, model.name)

            val initResult = withContext(Dispatchers.IO) {
                app.sherpaInitCoordinator.ensureInitialized(target)
            }

            if (initResult !is SherpaInitResult.Success) {
                finishApplyFailed(initResult.toUserMessage())
                return@launch
            }

            app.ttsRepository.applyTtsConfiguration(pendingDefaultId, state.pendingSpeakerIds)
            val models = app.ttsRepository.models.first()
            syncDraftFromPersisted(models)
            notifyTtsSync()
            _engineSwitchState.value = EngineSwitchState.Idle
            app.ttsSettingsGate.setBlocking(false)
            showToast(R.string.tts_apply_success)
        }
    }

    fun previewModel(modelId: String) {
        if (_engineSwitchState.value == EngineSwitchState.Applying) return
        if (_engineSwitchState.value == EngineSwitchState.Previewing) {
            if (_previewingModelId.value == modelId) {
                stopPreview()
            }
            return
        }

        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            val state = uiState.value
            val model = state.ttsModels.find { it.id == modelId } ?: return@launch
            if (model.downloadState != TtsDownloadState.DOWNLOADED.name) return@launch

            _engineSwitchState.value = EngineSwitchState.Previewing
            _previewingModelId.value = modelId
            _engineSwitchError.value = null

            val speakerId = state.pendingSpeakerIds[modelId] ?: model.speakerId
            val storedPreview = app.settingsRepository.settings.first().ttsPreviewText
            val sampleText = storedPreview.trim().ifBlank { previewTextFor(model) }

            prepareForPreview()
            showToast(R.string.tts_preview_synthesizing)

            try {
                when (
                    val outcome = app.ttsPreviewController.preview(
                        model = model,
                        sampleText = sampleText,
                        speakerId = speakerId,
                    )
                ) {
                    is TtsPreviewOutcome.Success -> {
                        app.ttsRepository.saveBenchmark(outcome.result.benchmark)
                    }
                    is TtsPreviewOutcome.Failure -> {
                        _engineSwitchError.value = outcome.message
                        showToastMessage(outcome.message)
                    }
                    TtsPreviewOutcome.Cancelled -> Unit
                }
            } catch (_: CancellationException) {
                app.ttsPreviewController.stopPlayback()
            } finally {
                _engineSwitchState.value = EngineSwitchState.Idle
                _previewingModelId.value = null
                previewJob = null
            }
        }
    }

    fun stopPreview() {
        previewJob?.cancel()
        app.ttsPreviewController.stopPlayback()
        _engineSwitchState.value = EngineSwitchState.Idle
        _previewingModelId.value = null
        previewJob = null
    }

    fun setPreviewText(text: String) {
        viewModelScope.launch {
            app.settingsRepository.setTtsPreviewText(text)
        }
    }

    fun setMirrorBaseUrl(url: String) {
        viewModelScope.launch {
            app.settingsRepository.setMirrorBaseUrl(url)
        }
    }

    fun previewTextFor(model: TtsModelEntity): String {
        val context = getApplication<Application>()
        return when {
            model.language.contains("zh") -> context.getString(R.string.tts_preview_sample_zh)
            model.language.contains("en") -> context.getString(R.string.tts_preview_sample_en)
            else -> context.getString(R.string.tts_preview_sample_zh)
        }
    }

    private suspend fun prepareForPreview() {
        val playback = PlaybackController.state.value
        if (playback.playbackState == PlaybackState.PLAYING) {
            ContextCompat.startForegroundService(
                getApplication(),
                TtsPlaybackService.controlIntent(getApplication(), TtsPlaybackService.ACTION_PAUSE),
            )
            try {
                withTimeout(2_000) {
                    while (PlaybackController.state.value.playbackState == PlaybackState.PLAYING) {
                        delay(50)
                    }
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                // Best effort; playback service will still cancel preloads below.
            }
        }
        val app = getApplication<VoicebookApp>()
        if (TtsPlaybackService.isRunning()) {
            coroutineScope {
                val prepared = async { app.sherpaInitCoordinator.waitForPlaybackPreviewPrepare() }
                ContextCompat.startForegroundService(
                    getApplication(),
                    TtsPlaybackService.controlIntent(
                        getApplication(),
                        TtsPlaybackService.ACTION_PREPARE_TTS_PREVIEW,
                    ),
                )
                prepared.await()
            }
        }
    }

    private fun resolveDraftModel(
        models: List<TtsModelEntity>,
        pendingDefaultModelId: String?,
    ): TtsModelEntity? {
        val modelId = pendingDefaultModelId ?: models.firstOrNull { it.isDefault }?.id ?: return null
        return models.find { it.id == modelId }
    }

    fun deleteModel(modelId: String) {
        if (_engineSwitchState.value != EngineSwitchState.Idle) return
        viewModelScope.launch {
            app.ttsRepository.deleteModel(modelId)
        }
    }

    fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000 -> "${bytes / 1_000_000} MB"
            bytes >= 1_000 -> "${bytes / 1_000} KB"
            else -> "$bytes B"
        }
    }

    fun formatBenchmark(benchmark: TtsModelBenchmark): String {
        val context = getApplication<Application>()
        val audioSec = benchmark.audioDurationMs / 1000f
        return context.getString(
            R.string.tts_benchmark_summary,
            benchmark.synthesisMs,
            audioSec,
            benchmark.rtf,
            benchmark.sampleRate / 1000,
        )
    }

    fun isBlockingNavigation(): Boolean =
        _engineSwitchState.value == EngineSwitchState.Applying

    fun benchmarkKey(modelId: String, speakerId: Int): String = "$modelId:$speakerId"

    private suspend fun syncDraftFromPersisted(models: List<TtsModelEntity>) {
        val default = models.firstOrNull { it.isDefault }
        _pendingDefaultModelId.value = default?.id
        _pendingSpeakerIds.value = models.associate { it.id to it.speakerId }
    }

    private suspend fun restorePersistedEngineIfNeeded() {
        val persisted = app.ttsRepository.getDefaultModel() ?: return
        withContext(Dispatchers.IO) {
            app.sherpaInitCoordinator.ensureInitialized(persisted)
        }
    }

    private suspend fun finishApplyFailed(message: String) {
        _engineSwitchError.value = message
        _engineSwitchState.value = EngineSwitchState.Failed
        app.ttsSettingsGate.setBlocking(false)
        _engineSwitchState.value = EngineSwitchState.Idle
        showToastMessage(message)
    }

    private suspend fun showToast(@androidx.annotation.StringRes resId: Int, vararg args: Any) {
        val context = getApplication<Application>()
        val message = context.getString(resId, *args)
        AppToast.show(context, message, Toast.LENGTH_SHORT)
    }

    private suspend fun showToastMessage(message: String) {
        AppToast.show(getApplication(), message, Toast.LENGTH_LONG)
    }

    private fun SherpaInitResult.toUserMessage(): String {
        val context = getApplication<Application>()
        return when (this) {
            SherpaInitResult.NotDownloaded -> context.getString(R.string.tts_no_model_downloaded)
            SherpaInitResult.LowMemory -> context.getString(
                R.string.tts_init_low_memory,
                com.dafei.voicebook.tts.SherpaInitCoordinator.MIN_FREE_MB,
            )
            SherpaInitResult.OutOfMemory -> context.getString(R.string.tts_init_oom)
            SherpaInitResult.ModelLoadFailed -> context.getString(R.string.tts_init_failed)
            is SherpaInitResult.Error -> context.getString(R.string.tts_init_failed_detail, detail)
            SherpaInitResult.Success -> ""
        }
    }

    private fun pausePlaybackIfNeeded() {
        val playback = PlaybackController.state.value
        if (playback.playbackState == PlaybackState.PLAYING) {
            ContextCompat.startForegroundService(
                getApplication(),
                TtsPlaybackService.controlIntent(getApplication(), TtsPlaybackService.ACTION_PAUSE),
            )
        }
    }

    private fun notifyTtsSync() {
        ContextCompat.startForegroundService(
            getApplication(),
            TtsPlaybackService.controlIntent(getApplication(), TtsPlaybackService.ACTION_SYNC_TTS_ENGINE),
        )
    }

    override fun onCleared() {
        stopPreview()
        app.ttsSettingsGate.setBlocking(false)
        super.onCleared()
    }

    private data class TtsDraftInputs(
        val models: List<TtsModelEntity>,
        val benchmarkList: List<TtsModelBenchmark>,
        val family: TtsModelFamily,
        val progress: Map<String, Float>,
        val pendingDefault: String?,
    )

    private data class TtsDraftUiInputs(
        val pendingSpeakers: Map<String, Int>,
        val switchState: EngineSwitchState,
        val switchError: String?,
        val previewingId: String?,
        val draftInit: Boolean,
    )

    private data class TtsDraftBundle(
        val models: List<TtsModelEntity>,
        val benchmarks: Map<String, TtsModelBenchmark>,
        val selectedFamily: TtsModelFamily,
        val downloadProgress: Map<String, Float>,
        val pendingDefaultModelId: String?,
        val pendingSpeakerIds: Map<String, Int>,
        val hasPendingChanges: Boolean,
        val engineSwitchState: EngineSwitchState,
        val engineSwitchError: String?,
        val previewingModelId: String?,
        val draftInitialized: Boolean,
    )
}
