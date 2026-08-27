package com.dafei.voicebook.tts

sealed class TtsModelDownloadResult {
    data object Success : TtsModelDownloadResult()
    data class Failure(val message: String) : TtsModelDownloadResult()
}
