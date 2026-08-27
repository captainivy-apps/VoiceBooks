package com.dafei.voicebook.tts

sealed class SherpaInitResult {
    data object Success : SherpaInitResult()
    data object NotDownloaded : SherpaInitResult()
    data object LowMemory : SherpaInitResult()
    data object OutOfMemory : SherpaInitResult()
    data object ModelLoadFailed : SherpaInitResult()
    data class Error(val detail: String) : SherpaInitResult()
}
