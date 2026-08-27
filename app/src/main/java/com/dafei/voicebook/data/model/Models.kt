package com.dafei.voicebook.data.model

enum class BookFormat {
    EPUB, TXT, MOBI, AZW, AZW3, UNKNOWN
}

enum class ImportStatus {
    PENDING, PROCESSING, COMPLETED, FAILED
}

enum class SortBy {
    UPLOAD_TIME, LAST_PLAYED
}

enum class SortOrder {
    ASC, DESC
}

data class ParsedBook(
    val title: String,
    val author: String,
    val summary: String,
    val textContent: String,
    val coverBytes: ByteArray? = null,
    val publisher: String = "",
    val publishedDate: String = "",
    val language: String = "",
    val isbn: String = "",
    val subjects: String = "",
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ParsedBook
        if (title != other.title) return false
        if (author != other.author) return false
        if (summary != other.summary) return false
        if (textContent != other.textContent) return false
        if (publisher != other.publisher) return false
        if (publishedDate != other.publishedDate) return false
        if (language != other.language) return false
        if (isbn != other.isbn) return false
        if (subjects != other.subjects) return false
        if (coverBytes != null) {
            if (other.coverBytes == null) return false
            if (!coverBytes.contentEquals(other.coverBytes)) return false
        } else if (other.coverBytes != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + author.hashCode()
        result = 31 * result + summary.hashCode()
        result = 31 * result + textContent.hashCode()
        result = 31 * result + publisher.hashCode()
        result = 31 * result + publishedDate.hashCode()
        result = 31 * result + language.hashCode()
        result = 31 * result + isbn.hashCode()
        result = 31 * result + subjects.hashCode()
        result = 31 * result + (coverBytes?.contentHashCode() ?: 0)
        return result
    }
}

data class SentenceChunk(
    val index: Int,
    val byteOffsetStart: Int,
    val byteOffsetEnd: Int,
    val text: String,
)

/** Lightweight import-time sentence index; holds byte offsets and a short preview only. */
data class SentenceIndexSlice(
    val index: Int,
    val byteOffsetStart: Int,
    val byteOffsetEnd: Int,
    val textPreview: String,
)

enum class TtsModelFamily(val id: String) {
    VITS("vits"),
    KOKORO("kokoro"),
    KITTEN("kitten"),
    ;

    companion object {
        fun fromModelType(modelType: String): TtsModelFamily = when (modelType) {
            "kokoro" -> KOKORO
            "kitten" -> KITTEN
            else -> VITS
        }
    }
}

data class TtsModelInfo(
    val id: String,
    val name: String,
    val language: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val mirrorDownloadUrl: String? = null,
    val modelType: String = "vits",
    val family: TtsModelFamily = TtsModelFamily.VITS,
    val speakerId: Int = 0,
    val speakerCount: Int = 1,
    val voicesFile: String = "voices.bin",
)

data class TtsModelBenchmark(
    val modelId: String,
    val speakerId: Int,
    val synthesisMs: Long,
    val audioDurationMs: Long,
    val rtf: Float,
    val sampleRate: Int,
    val benchmarkedAt: Long,
)

enum class TtsDownloadState {
    NOT_DOWNLOADED, DOWNLOADING, DOWNLOADED, FAILED
}
