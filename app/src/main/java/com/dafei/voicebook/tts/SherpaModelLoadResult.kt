package com.dafei.voicebook.tts

enum class SherpaModelLoadFailure {
    MissingDir,
    InvalidLayout,
    OutOfMemory,
    InvalidSampleRate,
    NativeError,
}

sealed class SherpaModelLoadResult {
    data object Success : SherpaModelLoadResult()
    data class Failure(val reason: SherpaModelLoadFailure, val detail: String? = null) : SherpaModelLoadResult()

    val isSuccess: Boolean get() = this is Success
}
