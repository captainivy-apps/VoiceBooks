package com.dafei.voicebook.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Shared gate to block app navigation while TTS engine is being switched. */
class TtsSettingsGate {
    private val _isBlocking = MutableStateFlow(false)
    val isBlocking: StateFlow<Boolean> = _isBlocking.asStateFlow()

    fun setBlocking(blocking: Boolean) {
        _isBlocking.value = blocking
    }
}

enum class EngineSwitchState {
    Idle,
    Previewing,
    Applying,
    Failed,
}

data class TtsPreviewResult(
    val benchmark: com.dafei.voicebook.data.model.TtsModelBenchmark,
)

sealed class TtsPreviewOutcome {
    data class Success(val result: TtsPreviewResult) : TtsPreviewOutcome()
    data class Failure(val message: String) : TtsPreviewOutcome()
    data object Cancelled : TtsPreviewOutcome()
}
