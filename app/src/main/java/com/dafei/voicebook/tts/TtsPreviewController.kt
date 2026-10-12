package com.dafei.voicebook.tts

import android.util.Log
import com.dafei.voicebook.data.db.TtsModelEntity
import com.dafei.voicebook.data.model.TtsDownloadState
import com.dafei.voicebook.data.model.TtsModelBenchmark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.system.measureTimeMillis

class TtsPreviewController(
    private val sherpaEngine: SherpaOnnxEngine,
    private val sherpaInitCoordinator: SherpaInitCoordinator,
) {
    private val player = PcmStreamPlayer()
    private val cancelRequested = AtomicBoolean(false)

    suspend fun preview(
        model: TtsModelEntity,
        sampleText: String,
        speakerId: Int,
    ): TtsPreviewOutcome = sherpaInitCoordinator.withExclusiveSynthesis {
        try {
            previewLocked(model, sampleText, speakerId)
        } finally {
            sherpaEngine.release()
            sherpaInitCoordinator.invalidate()
        }
    }

    private suspend fun previewLocked(
        model: TtsModelEntity,
        sampleText: String,
        speakerId: Int,
    ): TtsPreviewOutcome {
        if (model.downloadState != TtsDownloadState.DOWNLOADED.name || model.localPath == null) {
            return TtsPreviewOutcome.Failure("模型未下载")
        }
        val target = model.copy(speakerId = speakerId)
        when (val initResult = sherpaInitCoordinator.ensureInitialized(target)) {
            SherpaInitResult.Success -> Unit
            SherpaInitResult.NotDownloaded -> return TtsPreviewOutcome.Failure("模型未下载")
            SherpaInitResult.LowMemory -> return TtsPreviewOutcome.Failure(
                "内存不足，无法加载模型（需要约 ${SherpaInitCoordinator.MIN_FREE_MB}MB 空闲内存）",
            )
            SherpaInitResult.OutOfMemory -> return TtsPreviewOutcome.Failure("内存不足，无法加载模型")
            SherpaInitResult.ModelLoadFailed -> return TtsPreviewOutcome.Failure("模型加载失败")
            is SherpaInitResult.Error -> return TtsPreviewOutcome.Failure(
                initResult.detail.ifBlank { "模型加载失败" },
            )
        }

        val effectiveSpeakerId = sherpaEngine.resolveSpeakerId(speakerId)
        val batchOnly = SherpaOnnxEngine.requiresBatchSynthesis(model.modelType)
        val maxChunkChars = if (batchOnly) KOKORO_PREVIEW_MAX_CHARS else MAX_PREVIEW_TEXT_CHARS

        cancelRequested.set(false)
        player.release()

        val previewJob = coroutineContext[Job]
        val shouldContinue = {
            !cancelRequested.get() && previewJob?.isActive != false
        }

        val audioChunks = mutableListOf<PreviewAudioChunk>()
        val synthesisMs: Long
        try {
            synthesisMs = measureTimeMillis {
                val textChunks = TtsTextChunker.chunk(sampleText, maxChunkChars)
                for (textChunk in textChunks) {
                    if (!shouldContinue()) return@measureTimeMillis
                    var chunk = if (batchOnly) {
                        null
                    } else {
                        synthesizePreviewChunkStreaming(
                            textChunk = textChunk,
                            speakerId = effectiveSpeakerId,
                            shouldContinue = shouldContinue,
                        )
                    }
                    if (chunk == null) {
                        chunk = synthesizePreviewChunkBatch(
                            textChunk = textChunk,
                            speakerId = effectiveSpeakerId,
                            normalizeBatch = batchOnly,
                        )
                    }
                    if (chunk != null) {
                        audioChunks += chunk
                    }
                    if (!shouldContinue()) return@measureTimeMillis
                }
            }
        } catch (e: CancellationException) {
            player.stopAndFlush()
            throw e
        } catch (e: OutOfMemoryError) {
            player.release()
            return TtsPreviewOutcome.Failure("内存不足，无法合成语音")
        } catch (e: Exception) {
            Log.e(TAG, "Preview synthesis failed", e)
            player.release()
            return TtsPreviewOutcome.Failure(e.message ?: "合成失败")
        }

        if (!shouldContinue()) {
            player.stopAndFlush()
            return TtsPreviewOutcome.Cancelled
        }

        if (audioChunks.isEmpty()) {
            player.release()
            return TtsPreviewOutcome.Failure("合成失败")
        }

        val totalSamples = audioChunks.sumOf { it.samples.size }
        var sampleRate = 0
        var playbackStarted = false
        var framesBeforePreview = 0L
        var waitedMs = 0L

        try {
            withContext(Dispatchers.IO) {
                val gain = StreamingGain()
                gain.reset()

                for (chunk in audioChunks) {
                    if (!shouldContinue()) return@withContext
                    playPreviewChunkOnIo(
                        chunk = chunk,
                        gain = gain,
                        sampleRateHolder = { sampleRate },
                        onSampleRate = { rate -> sampleRate = rate },
                        onFirstPlaybackStart = {
                            if (!playbackStarted) {
                                player.ensurePlaying()
                                framesBeforePreview = player.framesWritten
                                playbackStarted = true
                            }
                        },
                        shouldContinue = shouldContinue,
                    )
                }

                // Flush the look-ahead warm-up window (short clips may never reach the lock threshold).
                val tail = gain.flush()
                if (tail.isNotEmpty() && sampleRate > 0) {
                    if (!playbackStarted) {
                        player.ensurePlaying()
                        framesBeforePreview = player.framesWritten
                        playbackStarted = true
                    }
                    player.writeSamples(tail, shouldContinue)
                }
                if (!playbackStarted) {
                    Log.w(TAG, "preview chunk writes produced no samples")
                }

                if (sampleRate <= 0) {
                    sampleRate = audioChunks.first().sampleRate.takeIf { it > 0 }
                        ?: sherpaEngine.sampleRate()
                }

                if (playbackStarted && sampleRate > 0) {
                    val framesEnd = player.framesWritten
                    Log.d(
                        TAG,
                        "preview_playback totalSamples=$totalSamples sampleRate=$sampleRate " +
                            "framesBefore=$framesBeforePreview framesEnd=$framesEnd",
                    )
                    waitedMs = awaitPcmPlaybackComplete(
                        player = player,
                        framesBeforeWrite = framesBeforePreview,
                        framesEnd = framesEnd,
                        sampleRate = sampleRate,
                        shouldContinue = shouldContinue,
                        logTag = TAG,
                    )
                }
                player.release()
            }
        } catch (e: CancellationException) {
            player.stopAndFlush()
            throw e
        }

        if (!shouldContinue()) {
            return TtsPreviewOutcome.Cancelled
        }

        if (totalSamples <= 0 || sampleRate <= 0) {
            return TtsPreviewOutcome.Failure("合成失败")
        }

        val audioDurationMs = (totalSamples * 1000L) / sampleRate
        val rtf = if (audioDurationMs > 0) synthesisMs.toFloat() / audioDurationMs else 0f
        Log.d(
            TAG,
            "preview_done synthesisMs=$synthesisMs audioDurationMs=$audioDurationMs " +
                "rtf=$rtf waitedMs=$waitedMs playbackStarted=$playbackStarted",
        )
        return TtsPreviewOutcome.Success(
            TtsPreviewResult(
                benchmark = TtsModelBenchmark(
                    modelId = model.id,
                    speakerId = effectiveSpeakerId,
                    synthesisMs = synthesisMs,
                    audioDurationMs = audioDurationMs,
                    rtf = rtf,
                    sampleRate = sampleRate,
                    benchmarkedAt = System.currentTimeMillis(),
                ),
            ),
        )
    }

    fun stopPlayback() {
        cancelRequested.set(true)
        player.stopAndFlush()
    }

    private fun synthesizePreviewChunkBatch(
        textChunk: String,
        speakerId: Int,
        normalizeBatch: Boolean,
    ): PreviewAudioChunk? {
        val batch = sherpaEngine.synthesize(textChunk, 1.0f, speakerId) ?: return null
        if (!batch.isPlayable()) return null
        if (normalizeBatch) {
            normalizeForPlayback(batch.samples)
        }
        val rate = batch.sampleRate.takeIf { it > 0 } ?: sherpaEngine.sampleRate()
        if (rate <= 0) return null
        return PreviewAudioChunk(batch.samples, rate)
    }

    private fun synthesizePreviewChunkStreaming(
        textChunk: String,
        speakerId: Int,
        shouldContinue: () -> Boolean,
    ): PreviewAudioChunk? {
        val pcmChunks = mutableListOf<FloatArray>()
        val stats = sherpaEngine.synthesizeStreaming(
            text = textChunk,
            speed = 1.0f,
            speakerId = speakerId,
            shouldContinue = shouldContinue,
            onChunk = chunk@{ pcm ->
                if (!shouldContinue()) return@chunk 0
                pcmChunks += pcm.copyOf()
                1
            },
        )
        if (stats == null || pcmChunks.isEmpty()) return null
        val rate = stats.sampleRate.takeIf { it > 0 } ?: sherpaEngine.sampleRate()
        if (rate <= 0) return null
        val merged = mergePcmChunks(pcmChunks)
        if (merged.isEmpty()) return null
        return PreviewAudioChunk(merged, rate)
    }

    private fun playPreviewChunkOnIo(
        chunk: PreviewAudioChunk,
        gain: StreamingGain,
        sampleRateHolder: () -> Int,
        onSampleRate: (Int) -> Unit,
        onFirstPlaybackStart: () -> Unit,
        shouldContinue: () -> Boolean,
    ): Int {
        var rate = sampleRateHolder()
        if (rate <= 0) {
            rate = chunk.sampleRate
            if (rate <= 0 || !player.prepare(rate)) return 0
            onSampleRate(rate)
        }

        val ready = gain.process(chunk.samples)
        if (ready.isEmpty()) return 0
        onFirstPlaybackStart()
        val written = player.writeSamples(ready, shouldContinue)
        return written.coerceAtLeast(0)
    }

    private fun mergePcmChunks(chunks: List<FloatArray>): FloatArray {
        if (chunks.isEmpty()) return FloatArray(0)
        if (chunks.size == 1) return chunks[0]
        val total = chunks.sumOf { it.size }
        val merged = FloatArray(total)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(merged, destinationOffset = offset)
            offset += chunk.size
        }
        return merged
    }

    private data class PreviewAudioChunk(
        val samples: FloatArray,
        val sampleRate: Int,
    )

    companion object {
        private const val TAG = "TtsPreviewController"
        private const val MAX_PREVIEW_TEXT_CHARS = 400
        /** Kokoro batch synthesis is heavy; keep preview chunks short to avoid native abort/OOM. */
        private const val KOKORO_PREVIEW_MAX_CHARS = 80
    }
}
