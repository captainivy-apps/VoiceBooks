package com.dafei.voicebook.data.model

sealed class ImportResult {
    data class Started(val bookId: String) : ImportResult()
    data class Duplicate(val existingTitle: String) : ImportResult()
    data class Failed(val message: String) : ImportResult()
}
