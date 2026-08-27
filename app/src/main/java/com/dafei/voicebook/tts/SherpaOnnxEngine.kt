package com.dafei.voicebook.tts

import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class SherpaOnnxEngine : TtsEngine {
    override val engineId: String = "sherpa_onnx"
    override val engineName: String = "Sherpa-ONNX"

    private val lock = ReentrantLock()
    private var tts: OfflineTts? = null
    private var currentModelPath: String? = null
    private var currentModelType: String? = null
    private var modelSampleRate: Int = 0

    override fun isReady(): Boolean = lock.withLock { tts != null }

    fun isLoadedFor(modelPath: String, modelType: String): Boolean = lock.withLock {
        tts != null && currentModelPath == modelPath && currentModelType == modelType
    }

    override fun initialize(modelPath: String, modelType: String, speakerId: Int): Boolean =
        loadModel(modelPath, modelType).isSuccess

    fun loadModel(modelPath: String, modelType: String): SherpaModelLoadResult = lock.withLock {
        if (currentModelPath == modelPath && currentModelType == modelType && tts != null) {
            return SherpaModelLoadResult.Success
        }
        releaseLocked()

        val modelDir = File(modelPath)
        if (!modelDir.exists()) {
            Log.e(TAG, "Model directory missing: $modelPath")
            return SherpaModelLoadResult.Failure(SherpaModelLoadFailure.MissingDir)
        }

        val assets = resolveTtsAssets(modelDir, modelType)
        if (assets == null) {
            Log.e(TAG, "Invalid model layout: $modelPath type=$modelType")
            return SherpaModelLoadResult.Failure(SherpaModelLoadFailure.InvalidLayout)
        }

        return try {
            val numThreads = when (modelType) {
                "kokoro", "kitten" -> 1
                else -> Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            }
            val config = buildOfflineTtsConfig(
                modelDir = modelDir,
                modelType = modelType,
                assets = assets,
                numThreads = numThreads,
            )
            if (config == null) {
                Log.e(TAG, "Failed to build TTS config: $modelPath type=$modelType")
                return SherpaModelLoadResult.Failure(SherpaModelLoadFailure.InvalidLayout)
            }
            Log.d(TAG, "Sherpa TTS type=$modelType numThreads=$numThreads model=$modelPath")
            if (modelType == "kokoro") {
                Log.d(
                    TAG,
                    "Kokoro config lexicon=${assets.lexicon} dictDir=${assets.dictDir} " +
                        "ruleFsts=${assets.ruleFsts} dataDir=${assets.dataDir}",
                )
            }
            tts = OfflineTts(config = config)
            modelSampleRate = tts?.sampleRate() ?: 0
            if (modelSampleRate <= 0) {
                releaseLocked()
                Log.e(TAG, "Invalid sample rate after load: $modelPath")
                return SherpaModelLoadResult.Failure(SherpaModelLoadFailure.InvalidSampleRate)
            }
            currentModelPath = modelPath
            currentModelType = modelType
            SherpaModelLoadResult.Success
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM initializing Sherpa model at $modelPath", e)
            releaseLocked()
            SherpaModelLoadResult.Failure(SherpaModelLoadFailure.OutOfMemory, e.message)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed initializing Sherpa model at $modelPath", e)
            releaseLocked()
            SherpaModelLoadResult.Failure(SherpaModelLoadFailure.NativeError, e.message)
        }
    }

    override fun getVoices(): List<TtsVoice> = lock.withLock {
        val count = tts?.numSpeakers() ?: 1
        return (0 until count).map { id ->
            TtsVoice(id = id.toString(), name = "Speaker $id", language = "auto")
        }.ifEmpty {
            listOf(TtsVoice(id = "0", name = "默认音色", language = "auto"))
        }
    }

    override fun sampleRate(): Int = lock.withLock { modelSampleRate }

    override fun resolveSpeakerId(speakerId: Int): Int = lock.withLock {
        val engine = tts ?: return speakerId.coerceAtLeast(0)
        val count = engine.numSpeakers().coerceAtLeast(1)
        speakerId.coerceIn(0, count - 1).also { resolved ->
            if (resolved != speakerId) {
                Log.w(TAG, "Clamped speakerId $speakerId -> $resolved (numSpeakers=$count)")
            }
        }
    }

    override fun synthesize(text: String, speed: Float, speakerId: Int): SynthesizedAudio? = lock.withLock {
        val engine = tts ?: run {
            Log.e(TAG, "Synthesis skipped: engine not loaded")
            return null
        }
        if (text.isBlank()) {
            Log.w(TAG, "Synthesis skipped: blank text")
            return null
        }
        val numSpeakers = engine.numSpeakers()
        val sid = speakerId.coerceIn(0, (numSpeakers.coerceAtLeast(1)) - 1)
        if (sid != speakerId) {
            Log.w(TAG, "Synthesis using clamped speakerId $speakerId -> $sid (numSpeakers=$numSpeakers)")
        }
        return try {
            val audio = engine.generate(text = text, sid = sid, speed = speed)
            val sampleRate = audio.sampleRate.takeIf { it > 0 } ?: modelSampleRate
            if (sampleRate <= 0 || audio.samples.isEmpty()) {
                Log.e(
                    TAG,
                    "Synthesis returned empty audio: textLen=${text.length} sid=$speakerId sampleRate=$sampleRate",
                )
                return null
            }
            SynthesizedAudio(samples = audio.samples, sampleRate = sampleRate)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM synthesizing textLen=${text.length} sid=$speakerId", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Synthesis failed textLen=${text.length} sid=$speakerId speed=$speed", e)
            null
        }
    }

    override fun synthesizeStreaming(
        text: String,
        speed: Float,
        speakerId: Int,
        shouldContinue: () -> Boolean,
        onChunk: (samples: FloatArray) -> Int,
    ): StreamingSynthStats? = lock.withLock {
        if (currentModelType == "kokoro" || currentModelType == "kitten") {
            Log.d(TAG, "Streaming callback skipped for $currentModelType (JNI workaround)")
            return null
        }
        val engine = tts ?: run {
            Log.e(TAG, "Streaming synthesis skipped: engine not loaded")
            return null
        }
        if (text.isBlank()) {
            Log.w(TAG, "Streaming synthesis skipped: blank text")
            return null
        }
        val numSpeakers = engine.numSpeakers()
        val sid = speakerId.coerceIn(0, (numSpeakers.coerceAtLeast(1)) - 1)
        if (sid != speakerId) {
            Log.w(TAG, "Streaming synthesis using clamped speakerId $speakerId -> $sid (numSpeakers=$numSpeakers)")
        }
        return try {
            var totalSamples = 0
            var callbackInvocations = 0
            var stoppedEarly = false

            val streamingCallback = StreamingChunkCallback(
                shouldContinue = shouldContinue,
                onChunk = onChunk,
            ) { invocations, samples, stopped ->
                callbackInvocations = invocations
                totalSamples = samples
                stoppedEarly = stopped
            }

            val audio = engine.generateWithCallback(
                text = text,
                sid = sid,
                speed = speed,
                callback = streamingCallback,
            )

            if (callbackInvocations == 0 && !stoppedEarly) {
                val fallbackSamples = audio.samples
                if (fallbackSamples.isNotEmpty()) {
                    Log.w(
                        TAG,
                        "Streaming callbacks not invoked; using returned buffer " +
                            "(${fallbackSamples.size} samples, textLen=${text.length})",
                    )
                    streamingCallback.invoke(fallbackSamples)
                } else {
                    Log.w(
                        TAG,
                        "Streaming callbacks not invoked; falling back to generate() textLen=${text.length}",
                    )
                    val batch = engine.generate(text = text, sid = sid, speed = speed)
                    if (batch.samples.isNotEmpty()) {
                        streamingCallback.invoke(batch.samples)
                    }
                }
            }

            val sampleRate = audio.sampleRate.takeIf { it > 0 } ?: modelSampleRate
            if (sampleRate <= 0) {
                Log.e(TAG, "Streaming synthesis returned invalid sample rate")
                return null
            }
            if (totalSamples <= 0 && !stoppedEarly) {
                Log.w(
                    TAG,
                    "Streaming produced no samples; retrying batch generate textLen=${text.length} sid=$sid",
                )
                val batch = engine.generate(text = text, sid = sid, speed = speed)
                if (batch.samples.isNotEmpty()) {
                    val result = onChunk(batch.samples)
                    totalSamples = batch.samples.size
                    callbackInvocations = 1
                    if (result == 0) stoppedEarly = true
                } else {
                    Log.e(
                        TAG,
                        "Streaming synthesis produced no samples: textLen=${text.length} sid=$sid",
                    )
                    return null
                }
            }
            StreamingSynthStats(
                sampleRate = sampleRate,
                totalSamples = totalSamples,
                callbackInvocations = callbackInvocations,
                stoppedEarly = stoppedEarly,
            )
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM streaming synthesis textLen=${text.length}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Streaming synthesis failed textLen=${text.length}", e)
            null
        }
    }

    override fun release() = lock.withLock {
        releaseLocked()
    }

    private fun releaseLocked() {
        tts?.release()
        tts = null
        currentModelPath = null
        currentModelType = null
        modelSampleRate = 0
    }

    companion object {
        private const val TAG = "SherpaOnnxEngine"

        /**
         * Sherpa JNI expects ( [F )Ljava/lang/Integer; — use an explicit Integer return.
         */
        private class StreamingChunkCallback(
            private val shouldContinue: () -> Boolean,
            private val onChunk: (FloatArray) -> Int,
            private val onStats: (invocations: Int, totalSamples: Int, stoppedEarly: Boolean) -> Unit,
        ) : (FloatArray) -> Int {
            private var callbackInvocations = 0
            private var totalSamples = 0
            private var stoppedEarly = false

            override fun invoke(samples: FloatArray): Int {
                if (!shouldContinue()) {
                    stoppedEarly = true
                    publish()
                    return 0
                }
                callbackInvocations++
                totalSamples += samples.size
                val result = onChunk(samples)
                if (result == 0) {
                    stoppedEarly = true
                }
                publish()
                return result
            }

            private fun publish() {
                onStats(callbackInvocations, totalSamples, stoppedEarly)
            }
        }

        fun isModelLayoutValid(modelDir: File, modelType: String = "vits"): Boolean =
            resolveTtsAssets(modelDir, modelType) != null

        /** Kokoro (and future Kitten) must use batch [synthesize] — streaming callback SIGABRT on Android. */
        fun requiresBatchSynthesis(modelType: String): Boolean =
            modelType == "kokoro" || modelType == "kitten"

        private fun buildOfflineTtsConfig(
            modelDir: File,
            modelType: String,
            assets: ResolvedTtsAssets,
            numThreads: Int,
        ): OfflineTtsConfig? {
            val dir = modelDir.absolutePath
            return when (modelType) {
                "kitten" -> {
                    Log.e(
                        TAG,
                        "Kitten TTS requires sherpa-onnx >= 1.13.0. " +
                            "Download sherpa-onnx-1.13.0.aar from GitHub releases.",
                    )
                    null
                }
                "kokoro" -> OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = "$dir/${assets.modelName}",
                            voices = "$dir/${assets.voices}",
                            tokens = "$dir/tokens.txt",
                            dataDir = assets.dataDir,
                            lexicon = assets.lexicon,
                            dictDir = assets.dictDir,
                        ),
                        numThreads = numThreads,
                        debug = false,
                        provider = "cpu",
                    ),
                    ruleFsts = assets.ruleFsts,
                    ruleFars = "",
                    maxNumSentences = 1,
                )
                else -> getOfflineTtsConfig(
                    modelDir = dir,
                    modelName = assets.modelName,
                    acousticModelName = "",
                    vocoder = "",
                    voices = "",
                    lexicon = assets.lexicon,
                    dataDir = assets.dataDir,
                    dictDir = assets.dictDir,
                    ruleFsts = assets.ruleFsts,
                    ruleFars = "",
                    numThreads = numThreads,
                )
            }
        }

        private fun resolveTtsAssets(modelDir: File, modelType: String): ResolvedTtsAssets? {
            return when (modelType) {
                "kokoro" -> resolveKokoroAssets(modelDir)
                "kitten" -> resolveKittenAssets(modelDir)
                else -> resolveVitsAssets(modelDir)
            }
        }

        private fun resolveVitsAssets(modelDir: File): ResolvedTtsAssets? {
            val children = modelDir.listFiles() ?: return null

            val preferInt8 = modelDir.name.contains("int8", ignoreCase = true)
            val onnxCandidates = children.filter {
                it.isFile &&
                    it.extension == "onnx" &&
                    !it.name.contains("espeak", ignoreCase = true)
            }
            val onnxFile = if (preferInt8) {
                onnxCandidates.firstOrNull { it.name.contains("int8", ignoreCase = true) }
                    ?: onnxCandidates.firstOrNull { !it.name.contains("int8", ignoreCase = true) }
            } else {
                onnxCandidates.firstOrNull { !it.name.contains("int8", ignoreCase = true) }
                    ?: onnxCandidates.firstOrNull()
            } ?: return null

            if (!File(modelDir, "tokens.txt").isFile) return null

            val espeakDir = children.firstOrNull {
                it.isDirectory &&
                    (it.name.contains("espeak", ignoreCase = true) || it.name == "espeak-ng-data")
            }
            val dictDirFile = children.firstOrNull { it.isDirectory && it.name == "dict" }
            val lexiconFile = children.firstOrNull {
                it.isFile && (it.name == "lexicon.txt" || it.name.endsWith("lexicon.txt"))
            }

            val ruleFsts = FST_FILES.mapNotNull { name ->
                File(modelDir, name).takeIf { it.isFile }?.absolutePath
            }.joinToString(",")

            val dataDir = espeakDir?.absolutePath ?: ""
            val dictDir = dictDirFile?.absolutePath ?: ""
            val lexicon = lexiconFile?.name ?: ""

            if (lexicon.isNotEmpty() && dataDir.isEmpty() && dictDir.isEmpty() && ruleFsts.isEmpty()) {
                return null
            }

            return ResolvedTtsAssets(
                modelName = onnxFile.name,
                voices = "",
                lexicon = lexicon,
                dataDir = dataDir,
                dictDir = dictDir,
                ruleFsts = ruleFsts,
            )
        }

        private fun resolveKokoroAssets(modelDir: File): ResolvedTtsAssets? {
            val children = modelDir.listFiles() ?: return null
            if (!File(modelDir, "tokens.txt").isFile) return null
            if (!File(modelDir, "voices.bin").isFile) return null

            val onnxFile = children.firstOrNull {
                it.isFile &&
                    it.extension == "onnx" &&
                    !it.name.contains("espeak", ignoreCase = true) &&
                    (it.name == "model.onnx" || it.name.startsWith("model"))
            } ?: children.firstOrNull {
                it.isFile && it.extension == "onnx" && !it.name.contains("espeak", ignoreCase = true)
            } ?: return null

            val espeakDir = children.firstOrNull {
                it.isDirectory &&
                    (it.name.contains("espeak", ignoreCase = true) || it.name == "espeak-ng-data")
            } ?: return null

            val lexiconFiles = children.filter {
                it.isFile && it.name.startsWith("lexicon") && it.name.endsWith(".txt")
            }.sortedBy { it.name }
            val modelDirPath = modelDir.absolutePath
            val dictDirFile = children.firstOrNull { it.isDirectory && it.name == "dict" }

            val lexicon = lexiconFiles.joinToString(",") { "$modelDirPath/${it.name}" }

            // Kokoro >= v1.0 multi-lang (model version 2) requires lexicon + dict/.
            if (lexiconFiles.isNotEmpty() && dictDirFile == null) {
                Log.e(TAG, "Kokoro multi-lang requires dict/ directory in $modelDirPath")
                return null
            }
            if (lexiconFiles.isEmpty() && dictDirFile != null) {
                Log.e(TAG, "Kokoro dict/ present but lexicon-*.txt missing in $modelDirPath")
                return null
            }

            val ruleFsts = KOKORO_FST_FILES.mapNotNull { name ->
                File(modelDir, name).takeIf { it.isFile }?.let { "$modelDirPath/$name" }
            }.joinToString(",")

            return ResolvedTtsAssets(
                modelName = onnxFile.name,
                voices = "voices.bin",
                lexicon = lexicon,
                dataDir = espeakDir.absolutePath,
                dictDir = dictDirFile?.absolutePath ?: "",
                ruleFsts = ruleFsts,
            )
        }

        private fun resolveKittenAssets(modelDir: File): ResolvedTtsAssets? {
            val children = modelDir.listFiles() ?: return null
            if (!File(modelDir, "tokens.txt").isFile) return null
            if (!File(modelDir, "voices.bin").isFile) return null

            val onnxFile = children.firstOrNull {
                it.isFile &&
                    it.extension == "onnx" &&
                    !it.name.contains("espeak", ignoreCase = true) &&
                    it.name.startsWith("model")
            } ?: return null

            val espeakDir = children.firstOrNull {
                it.isDirectory &&
                    (it.name.contains("espeak", ignoreCase = true) || it.name == "espeak-ng-data")
            } ?: return null

            return ResolvedTtsAssets(
                modelName = onnxFile.name,
                voices = "voices.bin",
                lexicon = "",
                dataDir = espeakDir.absolutePath,
                dictDir = "",
                ruleFsts = "",
            )
        }

        private val FST_FILES = listOf(
            "phone.fst",
            "number.fst",
            "date.fst",
            "new_heteronym.fst",
        )

        private val KOKORO_FST_FILES = listOf(
            "phone-zh.fst",
            "date-zh.fst",
            "number-zh.fst",
        )

        private data class ResolvedTtsAssets(
            val modelName: String,
            val voices: String,
            val lexicon: String,
            val dataDir: String,
            val dictDir: String,
            val ruleFsts: String,
        )
    }
}
