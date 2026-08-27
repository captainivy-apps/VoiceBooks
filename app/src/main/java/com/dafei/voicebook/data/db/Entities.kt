package com.dafei.voicebook.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dafei.voicebook.data.model.BookFormat
import com.dafei.voicebook.data.model.ImportStatus

@Entity(
    tableName = "books",
    indices = [
        Index(
            value = ["contentMd5"],
            unique = true,
            name = "index_books_contentMd5",
        ),
    ],
)
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String,
    val summary: String,
    val coverPath: String?,
    val sourcePath: String,
    val textPath: String,
    val format: BookFormat,
    val uploadTime: Long,
    val lastPlayedTime: Long?,
    val sentenceCount: Int,
    val importStatus: ImportStatus,
    val importError: String?,
    @ColumnInfo(defaultValue = "0")
    val importProgress: Int = 0,
    val sourceUrl: String? = null,
    val publisher: String? = null,
    val publishedDate: String? = null,
    val language: String? = null,
    val isbn: String? = null,
    val subjects: String? = null,
    val contentMd5: String? = null,
)

@Entity(
    tableName = "playback_progress",
    primaryKeys = ["bookId"],
)
data class PlaybackProgressEntity(
    val bookId: String,
    val currentSentenceIndex: Int,
    val positionInSentenceMs: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "sentence_index",
    primaryKeys = ["bookId", "sentenceIndex"],
)
data class SentenceIndexEntity(
    val bookId: String,
    val sentenceIndex: Int,
    val byteOffsetStart: Int,
    val byteOffsetEnd: Int,
    val textPreview: String,
)

@Entity(
    tableName = "tts_model_benchmarks",
    primaryKeys = ["modelId", "speakerId"],
)
data class TtsModelBenchmarkEntity(
    val modelId: String,
    val speakerId: Int,
    val synthesisMs: Long,
    val audioDurationMs: Long,
    val rtf: Float,
    val sampleRate: Int,
    val benchmarkedAt: Long,
)

@Entity(tableName = "tts_models")
data class TtsModelEntity(
    @PrimaryKey val id: String,
    val name: String,
    val language: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val localPath: String?,
    val downloadState: String,
    val downloadError: String? = null,
    val isDefault: Boolean,
    val modelType: String,
    val speakerId: Int,
)
