package com.dafei.voicebook.data.repository

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import com.dafei.voicebook.R
import com.dafei.voicebook.data.FileStorageManager
import com.dafei.voicebook.data.db.AppDatabase
import com.dafei.voicebook.data.db.BookEntity
import com.dafei.voicebook.data.db.BookWithProgress
import com.dafei.voicebook.data.db.PlaybackProgressEntity
import com.dafei.voicebook.data.db.SentenceIndexEntity
import com.dafei.voicebook.data.model.BookFormat
import com.dafei.voicebook.data.model.ImportResult
import com.dafei.voicebook.data.model.ImportStatus
import com.dafei.voicebook.data.model.SortBy
import com.dafei.voicebook.data.model.SortOrder
import com.dafei.voicebook.domain.import.EbookUrlHelper
import com.dafei.voicebook.domain.import.EpubParser
import com.dafei.voicebook.domain.import.ImportErrorMessages
import com.dafei.voicebook.domain.import.ImportWorker
import com.dafei.voicebook.domain.import.UrlImportWorker
import com.dafei.voicebook.service.UploadServerService
import com.dafei.voicebook.util.FileMd5Hasher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class BookRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val fileStorage: FileStorageManager,
) {
    data class LibraryRebuildProgress(
        val isRunning: Boolean = false,
        val stage: RebuildStage = RebuildStage.IDLE,
        val totalFiles: Int = 0,
        val processedFiles: Int = 0,
        val successCount: Int = 0,
        val failedCount: Int = 0,
        val currentFileName: String? = null,
        val message: String? = null,
    )

    enum class RebuildStage {
        IDLE,
        PREPARING,
        SCANNING,
        REBUILDING,
        COMPLETED,
    }

    private val bookDao = database.bookDao()
    private val sentenceDao = database.sentenceIndexDao()
    private val progressDao = database.playbackProgressDao()
    private val _libraryRebuildProgress = MutableStateFlow(LibraryRebuildProgress())
    val libraryRebuildProgress: StateFlow<LibraryRebuildProgress> = _libraryRebuildProgress.asStateFlow()

    fun observeBooks(sortBy: SortBy, sortOrder: SortOrder): Flow<List<BookWithProgress>> {
        return bookDao.observeAllBooks().map { books ->
            val comparator = when (sortBy) {
                SortBy.UPLOAD_TIME -> compareBy<BookWithProgress> { it.uploadTime }
                SortBy.LAST_PLAYED -> compareBy<BookWithProgress> { it.lastPlayedTime ?: 0L }
            }
            if (sortOrder == SortOrder.DESC) {
                books.sortedWith(comparator.reversed())
            } else {
                books.sortedWith(comparator)
            }
        }
    }

    fun observeBook(bookId: String) = bookDao.observeBook(bookId)

    suspend fun getBook(bookId: String) = bookDao.getBook(bookId)

    suspend fun getSentences(bookId: String) = sentenceDao.getSentences(bookId)

    fun observeSentences(bookId: String) = sentenceDao.observeSentences(bookId)

    suspend fun getProgress(bookId: String) = progressDao.getProgress(bookId)

    fun observeProgress(bookId: String) = progressDao.observeProgress(bookId)

    suspend fun healOversizedBookRows() {
        runCatching {
            bookDao.truncateOversizedTextColumns(
                titleMax = 256,
                authorMax = 128,
                coverPathMax = 1024,
                summaryMax = MAX_SUMMARY_LENGTH,
                importErrorMax = 1024,
                sourceUrlMax = 512,
                sourcePathMax = 1024,
                textPathMax = 1024,
                publisherMax = 128,
                subjectsMax = 256,
            )
        }
    }

    suspend fun saveProgress(bookId: String, sentenceIndex: Int, positionMs: Long) {
        progressDao.upsert(
            PlaybackProgressEntity(
                bookId = bookId,
                currentSentenceIndex = sentenceIndex,
                positionInSentenceMs = positionMs,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        bookDao.updateLastPlayed(bookId, System.currentTimeMillis())
    }

    suspend fun importFromUri(uri: Uri, fileName: String, batchMd5s: MutableSet<String>? = null): ImportResult {
        val tempFile = File(context.cacheDir, "import_incoming/${System.currentTimeMillis()}_$fileName")
        tempFile.parentFile?.mkdirs()
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return ImportResult.Failed(context.getString(R.string.import_error_read_file))

            importFromLocalFile(tempFile, fileName, sourceUrl = null, batchMd5s = batchMd5s, deleteSourceOnFinish = true)
        } catch (e: Exception) {
            tempFile.delete()
            ImportResult.Failed(ImportErrorMessages.toUserMessage(e, context))
        }
    }

    suspend fun importFromLocalFile(
        file: File,
        fileName: String,
        sourceUrl: String? = null,
        batchMd5s: MutableSet<String>? = null,
        deleteSourceOnFinish: Boolean = false,
    ): ImportResult {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val format = extensionToFormat(extension)
        val title = fileName.substringBeforeLast('.')
        var computedMd5: String? = null

        return try {
            if (format == BookFormat.UNKNOWN) {
                throw ImportErrorMessages.UnsupportedFormatException(extension)
            }

            ensureDuplicateIndexHealthy()
            val md5 = FileMd5Hasher.md5Of(file)
            computedMd5 = md5
            findDuplicateByMd5(md5)?.let { existing ->
                if (deleteSourceOnFinish) file.delete()
                return ImportResult.Duplicate(existing.title)
            }
            if (batchMd5s?.contains(md5) == true) {
                if (deleteSourceOnFinish) file.delete()
                return ImportResult.Duplicate(title)
            }
            batchMd5s?.add(md5)

            val bookId = fileStorage.newBookId()
            val sourceFile = fileStorage.bookSourceFile(bookId, extension)
            if (file.absolutePath != sourceFile.absolutePath) {
                file.copyTo(sourceFile, overwrite = true)
                if (deleteSourceOnFinish) file.delete()
            }

            startImport(
                bookId = bookId,
                title = title,
                format = format,
                sourcePath = sourceFile.absolutePath,
                sourceUrl = sourceUrl,
                contentMd5 = md5,
                enqueueImport = { ImportWorker.enqueue(context, bookId) },
            )
            ImportResult.Started(bookId)
        } catch (e: SQLiteConstraintException) {
            if (deleteSourceOnFinish) file.delete()
            val existing = computedMd5?.let { findDuplicateByMd5(it) }
            if (existing != null) {
                ImportResult.Duplicate(existing.title)
            } else {
                val bookId = fileStorage.newBookId()
                handleImportFailure(bookId, title, format, e)
                ImportResult.Failed(ImportErrorMessages.toUserMessage(e, context))
            }
        } catch (e: Exception) {
            if (deleteSourceOnFinish) file.delete()
            val bookId = fileStorage.newBookId()
            handleImportFailure(bookId, title, format, e)
            ImportResult.Failed(ImportErrorMessages.toUserMessage(e, context))
        }
    }

    suspend fun checkDuplicateAfterDownload(file: File, batchMd5s: MutableSet<String>? = null): ImportResult? {
        ensureDuplicateIndexHealthy()
        val md5 = FileMd5Hasher.md5Of(file)
        findDuplicateByMd5(md5)?.let { existing ->
            file.delete()
            return ImportResult.Duplicate(existing.title)
        }
        if (batchMd5s?.contains(md5) == true) {
            file.delete()
            return ImportResult.Duplicate(file.name)
        }
        batchMd5s?.add(md5)
        return null
    }

    suspend fun importFromUrl(url: String): ImportResult {
        val trimmedUrl = url.trim()
        val bookId = fileStorage.newBookId()

        if (!EbookUrlHelper.isValidHttpUrl(trimmedUrl)) {
            handleImportFailure(bookId, trimmedUrl, BookFormat.UNKNOWN, ImportErrorMessages.InvalidUrlException())
            return ImportResult.Failed(context.getString(R.string.import_error_invalid_url))
        }

        val extension = EbookUrlHelper.extensionFromUrl(trimmedUrl)
            ?: return ImportResult.Failed(
                context.getString(R.string.import_error_unsupported_format, "未知"),
            )
        val format = extensionToFormat(extension)
        if (format == BookFormat.UNKNOWN) {
            return ImportResult.Failed(
                context.getString(R.string.import_error_unsupported_format, extension),
            )
        }
        val fileName = EbookUrlHelper.fileNameFromUrlPath(trimmedUrl)
        val title = fileName.substringBeforeLast('.').ifBlank { fileName }
        val sourceFile = fileStorage.bookSourceFile(bookId, extension)

        return try {
            startImport(
                bookId = bookId,
                title = title,
                format = format,
                sourcePath = sourceFile.absolutePath,
                sourceUrl = trimmedUrl,
                contentMd5 = null,
                enqueueImport = { UrlImportWorker.enqueue(context, bookId, trimmedUrl) },
            )
            ImportResult.Started(bookId)
        } catch (e: Exception) {
            handleImportFailure(bookId, title, format, e)
            ImportResult.Failed(ImportErrorMessages.toUserMessage(e, context))
        }
    }

    suspend fun finalizeUrlImport(
        bookId: String,
        sourceFile: File,
        format: BookFormat,
    ): ImportResult {
        ensureDuplicateIndexHealthy()
        val md5 = FileMd5Hasher.md5Of(sourceFile)
        findDuplicateByMd5(md5)?.let { existing ->
            sourceFile.delete()
            bookDao.deleteBook(bookId)
            return ImportResult.Duplicate(existing.title)
        }
        try {
            bookDao.updateContentMd5(bookId, md5)
        } catch (_: SQLiteConstraintException) {
            val existing = findDuplicateByMd5(md5)
            sourceFile.delete()
            bookDao.deleteBook(bookId)
            return ImportResult.Duplicate(existing?.title ?: sourceFile.nameWithoutExtension)
        }
        bookDao.updateSourceFile(bookId, sourceFile.absolutePath, format)
        ImportWorker.enqueue(context, bookId)
        return ImportResult.Started(bookId)
    }

    suspend fun rebuildLibraryFromStorage() {
        if (_libraryRebuildProgress.value.isRunning) return
        _libraryRebuildProgress.value = LibraryRebuildProgress(
            isRunning = true,
            stage = RebuildStage.PREPARING,
            message = "preparing",
        )
        try {
            ensureDuplicateIndexHealthy()
            val existingMd5s = bookDao.getAllBookMd5Entries().mapNotNull { it.contentMd5 }
            UploadServerService.removeDedupMd5s(existingMd5s)
            bookDao.clearDuplicateIndexAndMetadata()

            _libraryRebuildProgress.value = _libraryRebuildProgress.value.copy(
                stage = RebuildStage.SCANNING,
                message = "scanning",
            )

            val sourceFiles = fileStorage.booksDir.listFiles()
                ?.filter { it.isFile && extensionToFormat(it.extension.lowercase()) != BookFormat.UNKNOWN }
                ?.sortedBy { it.name }
                ?: emptyList()
            val total = sourceFiles.size
            _libraryRebuildProgress.value = _libraryRebuildProgress.value.copy(
                stage = RebuildStage.REBUILDING,
                totalFiles = total,
                message = "rebuilding",
            )

            var processed = 0
            var success = 0
            var failed = 0

            sourceFiles.forEach { file ->
                val fileName = file.name
                _libraryRebuildProgress.value = _libraryRebuildProgress.value.copy(
                    currentFileName = fileName,
                    processedFiles = processed,
                    successCount = success,
                    failedCount = failed,
                )

                val result = runCatching { rebuildSingleFile(file) }.getOrElse { ImportResult.Failed(it.message ?: "failed") }
                when (result) {
                    is ImportResult.Started,
                    is ImportResult.Duplicate,
                    -> success++
                    is ImportResult.Failed -> failed++
                }
                processed++
                _libraryRebuildProgress.value = _libraryRebuildProgress.value.copy(
                    processedFiles = processed,
                    successCount = success,
                    failedCount = failed,
                )
            }

            _libraryRebuildProgress.value = _libraryRebuildProgress.value.copy(
                isRunning = false,
                stage = RebuildStage.COMPLETED,
                currentFileName = null,
                message = "completed",
            )
        } catch (e: Exception) {
            _libraryRebuildProgress.value = _libraryRebuildProgress.value.copy(
                isRunning = false,
                stage = RebuildStage.COMPLETED,
                currentFileName = null,
                message = e.message ?: "failed",
            )
        }
    }

    suspend fun retryImport(bookId: String) {
        val book = bookDao.getBook(bookId) ?: return
        if (book.format != BookFormat.EPUB) {
            markImportFailed(
                bookId,
                context.getString(R.string.import_error_unsupported_format, book.format.name.lowercase()),
            )
            return
        }
        val sourceFile = book.sourcePath.takeIf { it.isNotBlank() }?.let { File(it) }
        if (sourceFile?.isFile == true) {
            bookDao.updateImportStatus(bookId, ImportStatus.PENDING, null)
            ImportWorker.enqueue(context, bookId)
            return
        }
        val sourceUrl = book.sourceUrl?.trim().orEmpty()
        if (sourceUrl.isNotEmpty()) {
            bookDao.updateImportStatus(bookId, ImportStatus.PENDING, null)
            UrlImportWorker.enqueue(context, bookId, sourceUrl)
            return
        }
        markImportFailed(bookId, context.getString(R.string.import_error_source_missing))
    }

    suspend fun updateSourceFile(bookId: String, sourcePath: String, format: BookFormat) {
        bookDao.updateSourceFile(bookId, sourcePath, format)
    }

    suspend fun deleteBook(bookId: String) {
        clearDuplicateIndexForBooks(listOf(bookId))
        bookDao.deleteBook(bookId)
        sentenceDao.deleteForBook(bookId)
        progressDao.deleteForBook(bookId)
        fileStorage.deleteBookFiles(bookId)
    }

    suspend fun deleteBooks(bookIds: List<String>) {
        if (bookIds.isEmpty()) return
        clearDuplicateIndexForBooks(bookIds)
        bookDao.deleteBooks(bookIds)
        sentenceDao.deleteForBooks(bookIds)
        progressDao.deleteForBooks(bookIds)
        fileStorage.deleteBooksFiles(bookIds)
    }

    private suspend fun clearDuplicateIndexForBooks(bookIds: List<String>) {
        if (bookIds.isEmpty()) return
        val md5s = bookDao.getContentMd5sForBooks(bookIds)
        UploadServerService.removeDedupMd5s(md5s)
    }

    private suspend fun rebuildSingleFile(file: File): ImportResult {
        val extension = file.extension.lowercase()
        val format = extensionToFormat(extension)
        if (format == BookFormat.UNKNOWN) return ImportResult.Failed(context.getString(R.string.import_error_unsupported_format, extension))
        val existing = bookDao.getBookBySourcePath(file.absolutePath)
        return if (existing != null) {
            val md5 = FileMd5Hasher.md5Of(file)
            runCatching { bookDao.updateContentMd5(existing.id, md5) }
            retryImport(existing.id)
            ImportResult.Started(existing.id)
        } else {
            importFromLocalFile(
                file = file,
                fileName = file.name,
                sourceUrl = null,
                batchMd5s = null,
                deleteSourceOnFinish = false,
            )
        }
    }

    private suspend fun findDuplicateByMd5(md5: String): BookEntity? {
        return runCatching { bookDao.getBookByContentMd5(md5) }.getOrNull()
    }

    private fun ensureDuplicateIndexHealthy() {
        runCatching {
            database.openHelper.writableDatabase.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_books_contentMd5 ON books(contentMd5)",
            )
        }
    }

    suspend fun refreshBookMetadata(bookId: String): BookEntity {
        val book = bookDao.getBook(bookId) ?: throw IllegalStateException("Book not found")
        val sourceFile = book.sourcePath.takeIf { it.isNotBlank() }?.let { File(it) }
        if (sourceFile?.isFile != true) {
            throw IllegalStateException(context.getString(R.string.import_error_source_missing))
        }

        if (book.format != BookFormat.EPUB) {
            throw IllegalStateException(
                context.getString(
                    R.string.import_error_unsupported_format,
                    book.format.name.lowercase(),
                ),
            )
        }

        val parser = EpubParser()
        val parsed = parser.parse(sourceFile, book.format)

        var coverPath = book.coverPath
        parsed.coverBytes?.let { bytes ->
            val coverFile = fileStorage.bookCoverFile(bookId)
            coverFile.writeBytes(bytes)
            coverPath = coverFile.absolutePath
        }

        bookDao.updateBookMetadata(
            bookId = bookId,
            title = parsed.title,
            author = parsed.author.ifBlank { context.getString(R.string.empty_author) },
            summary = parsed.summary.take(MAX_SUMMARY_LENGTH),
            coverPath = coverPath,
            publisher = parsed.publisher.ifBlank { null },
            publishedDate = parsed.publishedDate.ifBlank { null },
            language = parsed.language.ifBlank { null },
            isbn = parsed.isbn.ifBlank { null },
            subjects = parsed.subjects.ifBlank { null },
        )

        return bookDao.getBook(bookId) ?: throw IllegalStateException("Book not found")
    }

    suspend fun updateAfterImport(
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
    ) {
        bookDao.updateBookAfterImport(
            bookId = bookId,
            title = title,
            author = author,
            summary = summary,
            coverPath = coverPath,
            textPath = textPath,
            sentenceCount = sentenceCount,
            publisher = publisher,
            publishedDate = publishedDate,
            language = language,
            isbn = isbn,
            subjects = subjects,
            status = ImportStatus.COMPLETED,
        )
    }

    suspend fun markImportFailed(bookId: String, error: String) {
        bookDao.updateImportStatus(bookId, ImportStatus.FAILED, error)
    }

    suspend fun markImportProcessing(bookId: String) {
        bookDao.updateImportStatus(bookId, ImportStatus.PROCESSING, null)
    }

    suspend fun updateImportProgress(bookId: String, progress: Int) {
        bookDao.updateImportProgress(bookId, progress.coerceIn(0, 100))
    }

    suspend fun insertSentences(bookId: String, sentences: List<SentenceIndexEntity>) {
        sentenceDao.deleteForBook(bookId)
        sentenceDao.insertAll(sentences)
    }

    suspend fun insertSentencesBatched(
        bookId: String,
        sentences: List<SentenceIndexEntity>,
        onProgress: suspend (Int) -> Unit,
    ) {
        sentenceDao.deleteForBook(bookId)
        if (sentences.isEmpty()) return

        val batches = sentences.chunked(SENTENCE_INSERT_BATCH_SIZE)
        val totalBatches = batches.size
        batches.forEachIndexed { index, batch ->
            sentenceDao.insertAll(batch)
            val progress = 70 + (25 * (index + 1) / totalBatches)
            onProgress(progress)
        }
    }

    fun readTextFile(path: String): String = File(path).readText()

    fun readSentenceText(textPath: String, byteOffsetStart: Int, byteOffsetEnd: Int): String {
        val file = File(textPath)
        if (!file.isFile) return ""
        val start = byteOffsetStart.coerceAtLeast(0)
        val end = byteOffsetEnd.coerceAtLeast(start)
        val length = end - start
        if (length == 0) return ""
        return RandomAccessFile(file, "r").use { randomAccess ->
            randomAccess.seek(start.toLong())
            val bytes = ByteArray(length)
            randomAccess.readFully(bytes)
            String(bytes, Charsets.UTF_8).trim()
        }
    }

    private suspend fun startImport(
        bookId: String,
        title: String,
        format: BookFormat,
        sourcePath: String,
        sourceUrl: String?,
        contentMd5: String?,
        enqueueImport: () -> Unit,
    ) {
        val book = BookEntity(
            id = bookId,
            title = title,
            author = "",
            summary = "",
            coverPath = null,
            sourcePath = sourcePath,
            textPath = "",
            format = format,
            uploadTime = System.currentTimeMillis(),
            lastPlayedTime = null,
            sentenceCount = 0,
            importStatus = ImportStatus.PENDING,
            importError = null,
            sourceUrl = sourceUrl,
            contentMd5 = contentMd5,
        )
        bookDao.insertBook(book)
        enqueueImport()
    }

    private suspend fun handleImportFailure(
        bookId: String,
        title: String,
        format: BookFormat,
        error: Exception,
    ) {
        val message = ImportErrorMessages.toUserMessage(error, context)
        val failedBook = BookEntity(
            id = bookId,
            title = title,
            author = "",
            summary = "",
            coverPath = null,
            sourcePath = "",
            textPath = "",
            format = format,
            uploadTime = System.currentTimeMillis(),
            lastPlayedTime = null,
            sentenceCount = 0,
            importStatus = ImportStatus.FAILED,
            importError = message,
        )
        bookDao.insertBook(failedBook)
    }

    companion object {
        private const val SENTENCE_INSERT_BATCH_SIZE = 500
        private const val MAX_SUMMARY_LENGTH = 2048

        fun extensionToFormat(ext: String): BookFormat = when (ext) {
            "epub" -> BookFormat.EPUB
            else -> BookFormat.UNKNOWN
        }
    }
}
