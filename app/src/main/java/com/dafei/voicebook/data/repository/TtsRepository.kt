package com.dafei.voicebook.data.repository

import android.content.Context
import com.dafei.voicebook.data.FileStorageManager
import com.dafei.voicebook.data.SettingsRepository
import com.dafei.voicebook.data.db.AppDatabase
import com.dafei.voicebook.data.db.TtsModelBenchmarkEntity
import com.dafei.voicebook.data.db.TtsModelEntity
import com.dafei.voicebook.data.model.TtsDownloadState
import com.dafei.voicebook.data.model.TtsModelBenchmark
import com.dafei.voicebook.tts.BuiltInTtsModels
import com.dafei.voicebook.tts.SherpaOnnxEngine
import com.dafei.voicebook.tts.TtsModelDownloader
import com.dafei.voicebook.tts.TtsModelDownloadResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class TtsRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val fileStorage: FileStorageManager,
    private val settingsRepository: SettingsRepository,
    private val downloader: TtsModelDownloader = TtsModelDownloader(),
) {
    private val dao = database.ttsModelDao()
    private val benchmarkDao = database.ttsModelBenchmarkDao()

    val models: Flow<List<TtsModelEntity>> = dao.observeModels()

    val benchmarks: Flow<List<TtsModelBenchmark>> = benchmarkDao.observeBenchmarks().map { entities ->
        entities.map { it.toBenchmark() }
    }

    suspend fun ensureBuiltInModels() {
        recoverInterruptedDownloads()

        val existing = dao.observeModels().first()
        val existingById = existing.associateBy { it.id }

        BuiltInTtsModels.MODELS.forEach { info ->
            val current = existingById[info.id]
            if (current == null) {
                dao.upsert(
                    TtsModelEntity(
                        id = info.id,
                        name = info.name,
                        language = info.language,
                        sizeBytes = info.sizeBytes,
                        downloadUrl = info.downloadUrl,
                        localPath = null,
                        downloadState = TtsDownloadState.NOT_DOWNLOADED.name,
                        isDefault = false,
                        modelType = info.modelType,
                        speakerId = info.speakerId,
                    ),
                )
            } else {
                dao.upsert(
                    current.copy(
                        name = info.name,
                        language = info.language,
                        sizeBytes = info.sizeBytes,
                        downloadUrl = info.downloadUrl,
                        modelType = info.modelType,
                    ),
                )
            }
        }

        if (existing.isEmpty()) {
            val firstId = BuiltInTtsModels.MODELS.first().id
            dao.clearDefault()
            dao.setDefault(firstId)
            settingsRepository.setDefaultTtsEngine(firstId)
        }
    }

    suspend fun updateSpeakerId(modelId: String, speakerId: Int) {
        val model = dao.getModel(modelId) ?: return
        val maxSpeaker = BuiltInTtsModels.infoFor(modelId)?.speakerCount ?: 1
        dao.upsert(model.copy(speakerId = speakerId.coerceIn(0, maxSpeaker - 1)))
    }

    suspend fun applyTtsConfiguration(
        defaultModelId: String,
        speakerIds: Map<String, Int>,
    ) {
        val models = dao.observeModels().first()
        models.forEach { model ->
            val pendingSpeaker = speakerIds[model.id] ?: model.speakerId
            val maxSpeaker = BuiltInTtsModels.infoFor(model.id)?.speakerCount ?: 1
            val clamped = pendingSpeaker.coerceIn(0, maxSpeaker - 1)
            if (model.speakerId != clamped) {
                dao.upsert(model.copy(speakerId = clamped))
            }
        }
        dao.clearDefault()
        dao.setDefault(defaultModelId)
        settingsRepository.setDefaultTtsEngine(defaultModelId)
    }

    suspend fun saveBenchmark(benchmark: TtsModelBenchmark) {
        benchmarkDao.upsert(
            TtsModelBenchmarkEntity(
                modelId = benchmark.modelId,
                speakerId = benchmark.speakerId,
                synthesisMs = benchmark.synthesisMs,
                audioDurationMs = benchmark.audioDurationMs,
                rtf = benchmark.rtf,
                sampleRate = benchmark.sampleRate,
                benchmarkedAt = benchmark.benchmarkedAt,
            ),
        )
    }

    suspend fun getBenchmark(modelId: String, speakerId: Int): TtsModelBenchmark? =
        benchmarkDao.getBenchmark(modelId, speakerId)?.toBenchmark()

    suspend fun downloadModel(
        modelId: String,
        useMirror: Boolean = false,
        onProgress: (Float) -> Unit,
    ): TtsModelDownloadResult {
        val model = dao.getModel(modelId)
            ?: return TtsModelDownloadResult.Failure("找不到模型")
        dao.updateDownloadState(modelId, TtsDownloadState.DOWNLOADING.name, null, null)

        val targetDir = fileStorage.ttsModelDir(modelId)
        targetDir.deleteRecursively()

        val mirrorBaseUrl = settingsRepository.settings.first().mirrorBaseUrl
        val url = if (useMirror) {
            BuiltInTtsModels.mirrorUrlFor(modelId, mirrorBaseUrl)
                ?: return TtsModelDownloadResult.Failure("未配置镜像地址").also {
                    dao.updateDownloadState(modelId, TtsDownloadState.FAILED.name, null, "未配置镜像地址")
                }
        } else {
            model.downloadUrl
        }

        return try {
            val downloadResult = downloader.downloadAndExtract(url, targetDir, onProgress)

            when {
                downloadResult is TtsModelDownloadResult.Failure -> {
                    cleanupPartialDownload(modelId)
                    dao.updateDownloadState(
                        modelId,
                        TtsDownloadState.FAILED.name,
                        null,
                        downloadResult.message,
                    )
                    downloadResult
                }
                else -> {
                    val extraFailure = downloadExtraAssets(modelId, targetDir, useMirror, mirrorBaseUrl, onProgress)
                    if (extraFailure != null) {
                        cleanupPartialDownload(modelId)
                        dao.updateDownloadState(
                            modelId,
                            TtsDownloadState.FAILED.name,
                            null,
                            extraFailure.message,
                        )
                        extraFailure
                    } else if (SherpaOnnxEngine.isModelLayoutValid(targetDir, model.modelType)) {
                        dao.updateDownloadState(
                            modelId,
                            TtsDownloadState.DOWNLOADED.name,
                            targetDir.absolutePath,
                            null,
                        )
                        TtsModelDownloadResult.Success
                    } else {
                        cleanupPartialDownload(modelId)
                        val error = "模型文件不完整或格式无效"
                        dao.updateDownloadState(modelId, TtsDownloadState.FAILED.name, null, error)
                        TtsModelDownloadResult.Failure(error)
                    }
                }
            }
        } catch (e: CancellationException) {
            cleanupPartialDownload(modelId)
            markDownloadInterrupted(modelId)
            throw e
        }
    }

    /** Download standalone extra assets (e.g. Matcha vocoder) that ship outside the model archive. */
    private suspend fun downloadExtraAssets(
        modelId: String,
        targetDir: java.io.File,
        useMirror: Boolean,
        mirrorBaseUrl: String,
        onProgress: (Float) -> Unit,
    ): TtsModelDownloadResult.Failure? {
        val extraUrl = BuiltInTtsModels.infoFor(modelId)?.extraAssetUrl ?: return null
        val fileName = extraUrl.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: return null
        val targetFile = java.io.File(targetDir, fileName)

        val mirroredUrl = if (useMirror) {
            BuiltInTtsModels.mirrorExtraAssetUrlFor(modelId, mirrorBaseUrl)
        } else {
            null
        }

        if (mirroredUrl != null) {
            val mirrored = downloader.downloadFile(mirroredUrl, targetFile, onProgress)
            if (mirrored is TtsModelDownloadResult.Success) return null
        }
        val result = downloader.downloadFile(extraUrl, targetFile, onProgress)
        return result as? TtsModelDownloadResult.Failure
    }

    suspend fun recoverInterruptedDownloads() {
        val models = dao.observeModels().first()
        for (model in models) {
            if (model.downloadState == TtsDownloadState.DOWNLOADING.name) {
                cleanupPartialDownload(model.id)
                markDownloadInterrupted(model.id)
            }
        }
    }

    private suspend fun markDownloadInterrupted(modelId: String) {
        dao.updateDownloadState(
            modelId,
            TtsDownloadState.FAILED.name,
            null,
            "下载已中断，请重试",
        )
    }

    private fun cleanupPartialDownload(modelId: String) {
        fileStorage.ttsModelDir(modelId).deleteRecursively()
        java.io.File(fileStorage.ttsModelsDir, "$modelId.tar.bz2").delete()
    }

    suspend fun setDefaultModel(modelId: String) {
        dao.clearDefault()
        dao.setDefault(modelId)
        settingsRepository.setDefaultTtsEngine(modelId)
    }

    suspend fun deleteModel(modelId: String) {
        val model = dao.getModel(modelId) ?: return
        model.localPath?.let { path ->
            java.io.File(path).deleteRecursively()
        }
        dao.updateDownloadState(modelId, TtsDownloadState.NOT_DOWNLOADED.name, null, null)
    }

    suspend fun getDefaultModel(): TtsModelEntity? = dao.getDefaultModel()

    suspend fun getModel(modelId: String): TtsModelEntity? = dao.getModel(modelId)

    private fun TtsModelBenchmarkEntity.toBenchmark(): TtsModelBenchmark = TtsModelBenchmark(
        modelId = modelId,
        speakerId = speakerId,
        synthesisMs = synthesisMs,
        audioDurationMs = audioDurationMs,
        rtf = rtf,
        sampleRate = sampleRate,
        benchmarkedAt = benchmarkedAt,
    )
}
