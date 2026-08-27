package com.dafei.voicebook.domain.import

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dafei.voicebook.VoicebookApp
import com.dafei.voicebook.data.model.BookFormat
import com.dafei.voicebook.data.model.ImportResult
import com.dafei.voicebook.data.repository.BookRepository
import kotlinx.coroutines.CancellationException
import java.io.File

class UrlImportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val bookId = inputData.getString(KEY_BOOK_ID) ?: return Result.failure()
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val app = applicationContext as VoicebookApp
        val bookRepo = app.bookRepository
        val fileStorage = app.fileStorage

        return try {
            val book = bookRepo.getBook(bookId) ?: return Result.failure()
            bookRepo.markImportProcessing(bookId)

            val inferredExtension = EbookUrlHelper.extensionFromUrl(url)
            val extension = inferredExtension
                ?: book.sourcePath.substringAfterLast('.', "").lowercase().takeIf { it.isNotBlank() }
                ?: "tmp"
            val targetFile = if (book.sourcePath.isNotBlank() && File(book.sourcePath).parentFile?.exists() == true) {
                File(book.sourcePath)
            } else {
                fileStorage.bookSourceFile(bookId, extension)
            }
            targetFile.parentFile?.mkdirs()

            val downloader = EbookDownloader()
            when (val result = downloader.download(url, targetFile) { progress ->
                bookRepo.updateImportProgress(bookId, progress)
            }) {
                is EbookDownloadResult.Success -> {
                    val resolvedExtension = result.fileName.substringAfterLast('.', "").lowercase()
                    val format = BookRepository.extensionToFormat(resolvedExtension)
                    if (format == BookFormat.UNKNOWN) {
                        targetFile.delete()
                        bookRepo.markImportFailed(
                            bookId,
                            ImportErrorMessages.toUserMessage(
                                ImportErrorMessages.UnsupportedFormatException(resolvedExtension),
                                applicationContext,
                            ),
                        )
                        return Result.failure()
                    }

                    val finalFile = if (targetFile.absolutePath.endsWith(".$resolvedExtension")) {
                        targetFile
                    } else {
                        val corrected = fileStorage.bookSourceFile(bookId, resolvedExtension)
                        if (targetFile.absolutePath != corrected.absolutePath) {
                            if (corrected.exists()) corrected.delete()
                            targetFile.renameTo(corrected)
                        }
                        corrected
                    }

                    when (bookRepo.finalizeUrlImport(bookId, finalFile, format)) {
                        is ImportResult.Duplicate -> Result.success()
                        is ImportResult.Started -> Result.success()
                        is ImportResult.Failed -> Result.failure()
                    }
                }
                is EbookDownloadResult.Failure -> {
                    targetFile.delete()
                    bookRepo.markImportFailed(
                        bookId,
                        ImportErrorMessages.downloadErrorMessage(result.error, applicationContext),
                    )
                    Result.failure()
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            val message = ImportErrorMessages.toUserMessage(Exception(t), applicationContext)
            Log.e(TAG, "URL import failed for book $bookId", t)
            bookRepo.markImportFailed(bookId, message)
            Result.failure()
        }
    }

    companion object {
        private const val TAG = "UrlImportWorker"
        private const val KEY_BOOK_ID = "book_id"
        private const val KEY_URL = "url"

        fun enqueue(context: Context, bookId: String, url: String) {
            val request = OneTimeWorkRequestBuilder<UrlImportWorker>()
                .setInputData(workDataOf(KEY_BOOK_ID to bookId, KEY_URL to url))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "url-import:$bookId",
                androidx.work.ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
