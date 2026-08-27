package com.dafei.voicebook.domain.import

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dafei.voicebook.VoicebookApp
import com.dafei.voicebook.data.db.SentenceIndexEntity
import kotlinx.coroutines.CancellationException
import java.io.File

class ImportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        val app = applicationContext as VoicebookApp
        val bookRepo = app.bookRepository
        val fileStorage = app.fileStorage

        return try {
            val book = bookRepo.getBook(bookId) ?: return Result.failure()
            bookRepo.markImportProcessing(bookId)

            val sourceFile = File(book.sourcePath)
            if (book.format != com.dafei.voicebook.data.model.BookFormat.EPUB) {
                throw ImportErrorMessages.UnsupportedFormatException(book.format.name.lowercase())
            }
            val parser = EpubParser()
            bookRepo.updateImportProgress(bookId, 5)
            val parsed = parser.parse(sourceFile, book.format)
            bookRepo.updateImportProgress(bookId, 45)

            if (parsed.textContent.isBlank()) {
                throw ImportErrorMessages.EmptyContentException()
            }

            val title = parsed.title
            val author = parsed.author.ifBlank { "未知作者" }
            val summary = parsed.summary.take(MAX_SUMMARY_LENGTH)
            val coverBytes = parsed.coverBytes

            val textFile = fileStorage.bookTextFile(bookId)
            textFile.writeText(parsed.textContent, Charsets.UTF_8)
            bookRepo.updateImportProgress(bookId, 50)

            var coverPath: String? = null
            coverBytes?.let { bytes ->
                val coverFile = fileStorage.bookCoverFile(bookId)
                coverFile.writeBytes(bytes)
                coverPath = coverFile.absolutePath
            }

            bookRepo.updateImportProgress(bookId, 55)
            val sentences = SentenceSplitter.splitForImport(textFile)
            bookRepo.updateImportProgress(bookId, 70)
            val sentenceEntities = sentences.map { slice ->
                SentenceIndexEntity(
                    bookId = bookId,
                    sentenceIndex = slice.index,
                    byteOffsetStart = slice.byteOffsetStart,
                    byteOffsetEnd = slice.byteOffsetEnd,
                    textPreview = slice.textPreview,
                )
            }
            bookRepo.insertSentencesBatched(bookId, sentenceEntities) { progress ->
                bookRepo.updateImportProgress(bookId, progress)
            }

            bookRepo.updateAfterImport(
                bookId = bookId,
                title = title,
                author = author,
                summary = summary,
                coverPath = coverPath,
                textPath = textFile.absolutePath,
                sentenceCount = sentences.size,
                publisher = parsed.publisher.ifBlank { null },
                publishedDate = parsed.publishedDate.ifBlank { null },
                language = parsed.language.ifBlank { null },
                isbn = parsed.isbn.ifBlank { null },
                subjects = parsed.subjects.ifBlank { null },
            )
            Result.success()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val message = ImportErrorMessages.toUserMessage(Exception(t), applicationContext)
            Log.e(TAG, "Import failed for book $bookId", t)
            bookRepo.markImportFailed(bookId, message)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "ImportWorker"
        private const val KEY_BOOK_ID = "book_id"
        private const val MAX_SUMMARY_LENGTH = 2048

        fun enqueue(context: Context, bookId: String) {
            val request = OneTimeWorkRequestBuilder<ImportWorker>()
                .setInputData(workDataOf(KEY_BOOK_ID to bookId))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "import:$bookId",
                androidx.work.ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
