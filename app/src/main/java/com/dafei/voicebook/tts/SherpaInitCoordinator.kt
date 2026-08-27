package com.dafei.voicebook.tts

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.dafei.voicebook.data.db.TtsModelEntity
import com.dafei.voicebook.data.model.TtsDownloadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/**
 * Ensures only one Sherpa model load runs at a time across app warmup and playback service.
 */
class SherpaInitCoordinator(
    private val sherpaEngine: SherpaOnnxEngine,
    private val appContext: Context? = null,
) {
    private val mutex = Mutex()
    private val synthesisMutex = Mutex()
    private var initDeferred: CompletableDeferred<SherpaInitResult>? = null
    private var lastInitKey: String? = null

    private val sherpaExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sherpa-tts")
    }
    val sherpaDispatcher: CoroutineDispatcher = sherpaExecutor.asCoroutineDispatcher()

    /** Serialize all native Sherpa calls on one thread (JNI safety). */
    suspend fun <T> withSherpaThread(block: suspend () -> T): T = withContext(sherpaDispatcher) {
        block()
    }

    /** Hold exclusive access while preview synthesizes so playback cannot release the engine. */
    suspend fun <T> withExclusiveSynthesis(block: suspend () -> T): T = synthesisMutex.withLock {
        withSherpaThread(block)
    }

    suspend fun waitForPlaybackPreviewPrepare() {
        val deferred = CompletableDeferred<Unit>()
        previewPrepareDeferred = deferred
        try {
            withTimeoutOrNull(PREVIEW_PREPARE_TIMEOUT_MS) {
                deferred.await()
            }
        } finally {
            if (previewPrepareDeferred === deferred) {
                previewPrepareDeferred = null
            }
        }
    }

    internal fun completePreviewPrepare() {
        previewPrepareDeferred?.complete(Unit)
        previewPrepareDeferred = null
    }

    fun shutdown() {
        sherpaExecutor.shutdownNow()
    }

    private var previewPrepareDeferred: CompletableDeferred<Unit>? = null

    fun initKeyFor(model: TtsModelEntity?): String? {
        if (model == null ||
            model.downloadState != TtsDownloadState.DOWNLOADED.name ||
            model.localPath == null
        ) {
            return null
        }
        return "${model.id}:${model.modelType}:${model.localPath}"
    }

    fun isReadyFor(model: TtsModelEntity?): Boolean {
        val initKey = initKeyFor(model) ?: return false
        return sherpaEngine.isReady() && lastInitKey == initKey
    }

    suspend fun ensureInitialized(model: TtsModelEntity?): SherpaInitResult {
        if (model == null ||
            model.downloadState != TtsDownloadState.DOWNLOADED.name ||
            model.localPath == null
        ) {
            return SherpaInitResult.NotDownloaded
        }

        val initKey = initKeyFor(model)!!
        if (sherpaEngine.isReady() && lastInitKey == initKey) {
            Log.d(TAG, "engine_cache_hit key=$initKey")
            return SherpaInitResult.Success
        }

        if (sherpaEngine.isLoadedFor(model.localPath, model.modelType)) {
            lastInitKey = initKey
            Log.d(TAG, "engine_soft_cache_hit key=$initKey")
            return SherpaInitResult.Success
        }

        val (deferred, shouldInit) = mutex.withLock {
            if (sherpaEngine.isReady() && lastInitKey == initKey) {
                return SherpaInitResult.Success
            }
            if (sherpaEngine.isLoadedFor(model.localPath, model.modelType)) {
                lastInitKey = initKey
                return SherpaInitResult.Success
            }
            val existing = initDeferred
            if (existing != null) {
                existing to false
            } else {
                CompletableDeferred<SherpaInitResult>().also { initDeferred = it } to true
            }
        }

        if (!shouldInit) {
            return deferred.await()
        }

        val result = try {
            loadEngine(model, initKey)
        } catch (t: Throwable) {
            Log.e(TAG, "Sherpa init failed for ${model.localPath}", t)
            SherpaInitResult.Error(t.message ?: t.javaClass.simpleName)
        }

        if (result !is SherpaInitResult.Success) {
            lastInitKey = null
        }

        mutex.withLock {
            if (initDeferred === deferred) {
                initDeferred = null
            }
        }
        deferred.complete(result)
        return result
    }

    private suspend fun loadEngine(model: TtsModelEntity, initKey: String): SherpaInitResult {
        val localPath = model.localPath
            ?: return SherpaInitResult.NotDownloaded
        if (!hasEnoughFreeMemory()) {
            Log.w(TAG, "Skipping Sherpa init: insufficient JVM heap headroom key=$initKey")
            return SherpaInitResult.LowMemory
        }
        return withSherpaThread {
            logMemory("before Sherpa init")
            val loadStartMs = System.currentTimeMillis()
            val loadResult = sherpaEngine.loadModel(localPath, model.modelType)
            val loadMs = System.currentTimeMillis() - loadStartMs
            logMemory("after Sherpa init success=${loadResult.isSuccess}")
            when (loadResult) {
                SherpaModelLoadResult.Success -> {
                    Log.d(TAG, "engine_load_ms=$loadMs key=$initKey success=true")
                    lastInitKey = initKey
                    SherpaInitResult.Success
                }
                is SherpaModelLoadResult.Failure -> {
                    Log.e(
                        TAG,
                        "engine_load_ms=$loadMs key=$initKey success=false reason=${loadResult.reason} " +
                            "detail=${loadResult.detail}",
                    )
                    loadResult.toInitResult()
                }
            }
        }
    }

    private fun SherpaModelLoadResult.Failure.toInitResult(): SherpaInitResult = when (reason) {
        SherpaModelLoadFailure.OutOfMemory -> SherpaInitResult.OutOfMemory
        SherpaModelLoadFailure.MissingDir,
        SherpaModelLoadFailure.InvalidLayout,
        SherpaModelLoadFailure.InvalidSampleRate,
        SherpaModelLoadFailure.NativeError,
        -> SherpaInitResult.ModelLoadFailed
    }

    fun invalidate() {
        lastInitKey = null
    }

    private fun hasEnoughFreeMemory(): Boolean {
        val runtime = Runtime.getRuntime()
        val freeMb = (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())) / (1024 * 1024)
        return freeMb >= MIN_FREE_MB
    }

    private fun logMemory(label: String) {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMb = runtime.maxMemory() / (1024 * 1024)
        val freeMb = maxMb - usedMb
        val context = appContext
        if (context != null) {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            val availMb = memInfo.availMem / (1024 * 1024)
            Log.d(
                TAG,
                "$label: heapUsed=${usedMb}MB heapFree=${freeMb}MB heapMax=${maxMb}MB " +
                    "sysAvail=${availMb}MB lowMemory=${memInfo.lowMemory}",
            )
        } else {
            Log.d(TAG, "$label: heapUsed=${usedMb}MB heapFree=${freeMb}MB heapMax=${maxMb}MB")
        }
    }

    companion object {
        private const val TAG = "SherpaInitCoordinator"
        /** Rough headroom needed before loading a typical offline TTS model. */
        const val MIN_FREE_MB = 80L
        private const val PREVIEW_PREPARE_TIMEOUT_MS = 3_000L
    }
}
