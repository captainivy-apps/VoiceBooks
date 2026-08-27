package com.dafei.voicebook.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.dafei.voicebook.data.model.ImportStatus
import kotlinx.coroutines.flow.Flow

data class BookWithProgress(
    val id: String,
    val title: String,
    val author: String,
    val coverPath: String?,
    val uploadTime: Long,
    val lastPlayedTime: Long?,
    val sentenceCount: Int,
    val importStatus: String,
    val importError: String?,
    val importProgress: Int,
    val sourceUrl: String?,
    val currentSentenceIndex: Int?,
    val positionInSentenceMs: Long?,
)

data class BookMd5Entry(
    val id: String,
    val contentMd5: String?,
)

@Dao
interface BookDao {
    @Query(
        """
        SELECT
            id,
            SUBSTR(title, 1, 256) AS title,
            SUBSTR(author, 1, 128) AS author,
            SUBSTR(summary, 1, 2048) AS summary,
            SUBSTR(coverPath, 1, 1024) AS coverPath,
            SUBSTR(sourcePath, 1, 1024) AS sourcePath,
            SUBSTR(textPath, 1, 1024) AS textPath,
            format,
            uploadTime,
            lastPlayedTime,
            sentenceCount,
            importStatus,
            SUBSTR(importError, 1, 1024) AS importError,
            importProgress,
            SUBSTR(sourceUrl, 1, 512) AS sourceUrl,
            SUBSTR(publisher, 1, 128) AS publisher,
            SUBSTR(publishedDate, 1, 64) AS publishedDate,
            SUBSTR(language, 1, 64) AS language,
            SUBSTR(isbn, 1, 64) AS isbn,
            SUBSTR(subjects, 1, 256) AS subjects,
            contentMd5
        FROM books
        WHERE id = :bookId
        LIMIT 1
        """,
    )
    suspend fun getBook(bookId: String): BookEntity?

    @Query(
        """
        SELECT
            id,
            SUBSTR(title, 1, 256) AS title,
            SUBSTR(author, 1, 128) AS author,
            SUBSTR(summary, 1, 2048) AS summary,
            SUBSTR(coverPath, 1, 1024) AS coverPath,
            SUBSTR(sourcePath, 1, 1024) AS sourcePath,
            SUBSTR(textPath, 1, 1024) AS textPath,
            format,
            uploadTime,
            lastPlayedTime,
            sentenceCount,
            importStatus,
            SUBSTR(importError, 1, 1024) AS importError,
            importProgress,
            SUBSTR(sourceUrl, 1, 512) AS sourceUrl,
            SUBSTR(publisher, 1, 128) AS publisher,
            SUBSTR(publishedDate, 1, 64) AS publishedDate,
            SUBSTR(language, 1, 64) AS language,
            SUBSTR(isbn, 1, 64) AS isbn,
            SUBSTR(subjects, 1, 256) AS subjects,
            contentMd5
        FROM books
        WHERE id = :bookId
        LIMIT 1
        """,
    )
    fun observeBook(bookId: String): Flow<BookEntity?>

    @Query(
        """
        SELECT
            b.id,
            SUBSTR(b.title, 1, 256) AS title,
            SUBSTR(b.author, 1, 128) AS author,
            SUBSTR(b.coverPath, 1, 1024) AS coverPath,
            b.uploadTime,
            b.lastPlayedTime,
            b.sentenceCount,
            b.importStatus,
            SUBSTR(b.importError, 1, 512) AS importError,
            b.importProgress,
            SUBSTR(b.sourceUrl, 1, 512) AS sourceUrl,
            p.currentSentenceIndex,
            p.positionInSentenceMs
        FROM books b
        LEFT JOIN playback_progress p ON b.id = p.bookId
        """,
    )
    fun observeAllBooks(): Flow<List<BookWithProgress>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity)

    @Query(
        "UPDATE books SET importStatus = :status, importError = :error, importProgress = 0 WHERE id = :bookId",
    )
    suspend fun updateImportStatus(bookId: String, status: ImportStatus, error: String?)

    @Query("UPDATE books SET importProgress = :progress WHERE id = :bookId")
    suspend fun updateImportProgress(bookId: String, progress: Int)

    @Query("UPDATE books SET sourcePath = :sourcePath, format = :format WHERE id = :bookId")
    suspend fun updateSourceFile(bookId: String, sourcePath: String, format: com.dafei.voicebook.data.model.BookFormat)

    @Query(
        """
        UPDATE books SET title = :title, author = :author, summary = :summary,
        coverPath = :coverPath, textPath = :textPath, sentenceCount = :sentenceCount,
        publisher = :publisher, publishedDate = :publishedDate, language = :language,
        isbn = :isbn, subjects = :subjects,
        importStatus = :status, importError = NULL, importProgress = 0 WHERE id = :bookId
        """,
    )
    suspend fun updateBookAfterImport(
        bookId: String,
        title: String,
        author: String,
        summary: String,
        coverPath: String?,
        textPath: String,
        sentenceCount: Int,
        publisher: String?,
        publishedDate: String?,
        language: String?,
        isbn: String?,
        subjects: String?,
        status: ImportStatus,
    )

    @Query("UPDATE books SET lastPlayedTime = :time WHERE id = :bookId")
    suspend fun updateLastPlayed(bookId: String, time: Long)

    @Query(
        """
        UPDATE books SET title = :title, author = :author, summary = :summary,
        coverPath = :coverPath, publisher = :publisher, publishedDate = :publishedDate,
        language = :language, isbn = :isbn, subjects = :subjects
        WHERE id = :bookId
        """,
    )
    suspend fun updateBookMetadata(
        bookId: String,
        title: String,
        author: String,
        summary: String,
        coverPath: String?,
        publisher: String?,
        publishedDate: String?,
        language: String?,
        isbn: String?,
        subjects: String?,
    )

    @Query(
        """
        SELECT
            id,
            SUBSTR(title, 1, 256) AS title,
            SUBSTR(author, 1, 128) AS author,
            SUBSTR(summary, 1, 2048) AS summary,
            SUBSTR(coverPath, 1, 1024) AS coverPath,
            SUBSTR(sourcePath, 1, 1024) AS sourcePath,
            SUBSTR(textPath, 1, 1024) AS textPath,
            format,
            uploadTime,
            lastPlayedTime,
            sentenceCount,
            importStatus,
            SUBSTR(importError, 1, 1024) AS importError,
            importProgress,
            SUBSTR(sourceUrl, 1, 512) AS sourceUrl,
            SUBSTR(publisher, 1, 128) AS publisher,
            SUBSTR(publishedDate, 1, 64) AS publishedDate,
            SUBSTR(language, 1, 64) AS language,
            SUBSTR(isbn, 1, 64) AS isbn,
            SUBSTR(subjects, 1, 256) AS subjects,
            contentMd5
        FROM books
        WHERE contentMd5 = :md5
        LIMIT 1
        """
    )
    suspend fun getBookByContentMd5(md5: String): BookEntity?

    @Query("UPDATE books SET contentMd5 = :md5 WHERE id = :bookId")
    suspend fun updateContentMd5(bookId: String, md5: String)

    @Query("SELECT contentMd5 FROM books WHERE id IN (:bookIds) AND contentMd5 IS NOT NULL")
    suspend fun getContentMd5sForBooks(bookIds: List<String>): List<String>

    @Query("SELECT id, contentMd5 FROM books")
    suspend fun getAllBookMd5Entries(): List<BookMd5Entry>

    @Query(
        """
        SELECT
            id,
            SUBSTR(title, 1, 256) AS title,
            SUBSTR(author, 1, 128) AS author,
            SUBSTR(summary, 1, 2048) AS summary,
            SUBSTR(coverPath, 1, 1024) AS coverPath,
            SUBSTR(sourcePath, 1, 1024) AS sourcePath,
            SUBSTR(textPath, 1, 1024) AS textPath,
            format,
            uploadTime,
            lastPlayedTime,
            sentenceCount,
            importStatus,
            SUBSTR(importError, 1, 1024) AS importError,
            importProgress,
            SUBSTR(sourceUrl, 1, 512) AS sourceUrl,
            SUBSTR(publisher, 1, 128) AS publisher,
            SUBSTR(publishedDate, 1, 64) AS publishedDate,
            SUBSTR(language, 1, 64) AS language,
            SUBSTR(isbn, 1, 64) AS isbn,
            SUBSTR(subjects, 1, 256) AS subjects,
            contentMd5
        FROM books
        WHERE sourcePath = :sourcePath
        LIMIT 1
        """
    )
    suspend fun getBookBySourcePath(sourcePath: String): BookEntity?

    @Query(
        """
        UPDATE books SET
        contentMd5 = NULL,
        title = '',
        author = '',
        summary = '',
        coverPath = NULL,
        publisher = NULL,
        publishedDate = NULL,
        language = NULL,
        isbn = NULL,
        subjects = NULL
        """,
    )
    suspend fun clearDuplicateIndexAndMetadata()

    @Query(
        """
        UPDATE books SET
        title = CASE WHEN LENGTH(title) > :titleMax THEN SUBSTR(title, 1, :titleMax) ELSE title END,
        author = CASE WHEN LENGTH(author) > :authorMax THEN SUBSTR(author, 1, :authorMax) ELSE author END,
        coverPath = CASE WHEN LENGTH(coverPath) > :coverPathMax THEN SUBSTR(coverPath, 1, :coverPathMax) ELSE coverPath END,
        summary = CASE WHEN LENGTH(summary) > :summaryMax THEN SUBSTR(summary, 1, :summaryMax) ELSE summary END,
        importError = CASE WHEN LENGTH(importError) > :importErrorMax THEN SUBSTR(importError, 1, :importErrorMax) ELSE importError END,
        sourceUrl = CASE WHEN LENGTH(sourceUrl) > :sourceUrlMax THEN SUBSTR(sourceUrl, 1, :sourceUrlMax) ELSE sourceUrl END,
        sourcePath = CASE WHEN LENGTH(sourcePath) > :sourcePathMax THEN SUBSTR(sourcePath, 1, :sourcePathMax) ELSE sourcePath END,
        textPath = CASE WHEN LENGTH(textPath) > :textPathMax THEN SUBSTR(textPath, 1, :textPathMax) ELSE textPath END,
        publisher = CASE WHEN LENGTH(publisher) > :publisherMax THEN SUBSTR(publisher, 1, :publisherMax) ELSE publisher END,
        subjects = CASE WHEN LENGTH(subjects) > :subjectsMax THEN SUBSTR(subjects, 1, :subjectsMax) ELSE subjects END
        """
    )
    suspend fun truncateOversizedTextColumns(
        titleMax: Int,
        authorMax: Int,
        coverPathMax: Int,
        summaryMax: Int,
        importErrorMax: Int,
        sourceUrlMax: Int,
        sourcePathMax: Int,
        textPathMax: Int,
        publisherMax: Int,
        subjectsMax: Int,
    )

    @Query("DELETE FROM books WHERE id = :bookId")
    suspend fun deleteBook(bookId: String)

    @Query("DELETE FROM books WHERE id IN (:bookIds)")
    suspend fun deleteBooks(bookIds: List<String>)
}

@Dao
interface SentenceIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sentences: List<SentenceIndexEntity>)

    @Query("SELECT * FROM sentence_index WHERE bookId = :bookId ORDER BY sentenceIndex ASC")
    suspend fun getSentences(bookId: String): List<SentenceIndexEntity>

    @Query("SELECT * FROM sentence_index WHERE bookId = :bookId ORDER BY sentenceIndex ASC")
    fun observeSentences(bookId: String): Flow<List<SentenceIndexEntity>>

    @Query("SELECT * FROM sentence_index WHERE bookId = :bookId AND sentenceIndex = :index LIMIT 1")
    suspend fun getSentence(bookId: String, index: Int): SentenceIndexEntity?

    @Query("DELETE FROM sentence_index WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)

    @Query("DELETE FROM sentence_index WHERE bookId IN (:bookIds)")
    suspend fun deleteForBooks(bookIds: List<String>)
}

@Dao
interface PlaybackProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: PlaybackProgressEntity)

    @Query("SELECT * FROM playback_progress WHERE bookId = :bookId")
    suspend fun getProgress(bookId: String): PlaybackProgressEntity?

    @Query("SELECT * FROM playback_progress WHERE bookId = :bookId")
    fun observeProgress(bookId: String): Flow<PlaybackProgressEntity?>

    @Query("DELETE FROM playback_progress WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)

    @Query("DELETE FROM playback_progress WHERE bookId IN (:bookIds)")
    suspend fun deleteForBooks(bookIds: List<String>)
}

@Dao
interface TtsModelDao {
    @Query("SELECT * FROM tts_models ORDER BY name ASC")
    fun observeModels(): Flow<List<TtsModelEntity>>

    @Query("SELECT * FROM tts_models WHERE id = :id")
    suspend fun getModel(id: String): TtsModelEntity?

    @Query("SELECT * FROM tts_models WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultModel(): TtsModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(models: List<TtsModelEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: TtsModelEntity)

    @Query("UPDATE tts_models SET isDefault = 0")
    suspend fun clearDefault()

    @Query("UPDATE tts_models SET isDefault = 1 WHERE id = :id")
    suspend fun setDefault(id: String)

    @Query(
        "UPDATE tts_models SET downloadState = :state, localPath = :localPath, " +
            "downloadError = :downloadError WHERE id = :id",
    )
    suspend fun updateDownloadState(
        id: String,
        state: String,
        localPath: String?,
        downloadError: String? = null,
    )

    @Query("DELETE FROM tts_models WHERE id = :id")
    suspend fun deleteModel(id: String)
}

@Dao
interface TtsModelBenchmarkDao {
    @Query("SELECT * FROM tts_model_benchmarks")
    fun observeBenchmarks(): Flow<List<TtsModelBenchmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(benchmark: TtsModelBenchmarkEntity)

    @Query("SELECT * FROM tts_model_benchmarks WHERE modelId = :modelId AND speakerId = :speakerId LIMIT 1")
    suspend fun getBenchmark(modelId: String, speakerId: Int): TtsModelBenchmarkEntity?
}
