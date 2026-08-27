package com.dafei.voicebook.domain.import

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

sealed class EbookDownloadResult {
    data class Success(val fileName: String) : EbookDownloadResult()
    data class Failure(val error: EbookDownloadError) : EbookDownloadResult()
}

enum class EbookDownloadError {
    INVALID_URL,
    HTTP_ERROR,
    NETWORK_ERROR,
    EMPTY_RESPONSE,
    UNSUPPORTED_FORMAT,
}

class EbookDownloader(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
) {
    suspend fun download(
        url: String,
        targetFile: File,
        onProgress: suspend (Int) -> Unit,
    ): EbookDownloadResult = withContext(Dispatchers.IO) {
        if (!EbookUrlHelper.isValidHttpUrl(url)) {
            return@withContext EbookDownloadResult.Failure(EbookDownloadError.INVALID_URL)
        }

        val tempFile = File(targetFile.parentFile, "${targetFile.name}.download")
        try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext EbookDownloadResult.Failure(EbookDownloadError.HTTP_ERROR)
                }
                val body = response.body
                    ?: return@withContext EbookDownloadResult.Failure(EbookDownloadError.EMPTY_RESPONSE)

                val fileName = EbookUrlHelper.resolveFileName(
                    url = url,
                    contentDisposition = response.header("Content-Disposition"),
                )
                val extension = fileName.substringAfterLast('.', "").lowercase()
                if (!EbookUrlHelper.isSupportedExtension(extension)) {
                    return@withContext EbookDownloadResult.Failure(EbookDownloadError.UNSUPPORTED_FORMAT)
                }

                val total = body.contentLength()
                var downloaded = 0L
                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val progress = (downloaded * 15 / total).toInt().coerceIn(0, 15)
                                onProgress(progress)
                            }
                        }
                    }
                }

                if (targetFile.exists()) targetFile.delete()
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
                onProgress(15)
                EbookDownloadResult.Success(fileName)
            }
        } catch (e: CancellationException) {
            tempFile.delete()
            throw e
        } catch (_: Exception) {
            tempFile.delete()
            EbookDownloadResult.Failure(EbookDownloadError.NETWORK_ERROR)
        }
    }
}

object EbookUrlHelper {
    const val SUPPORTED_EXTENSION = "epub"
    private val SUPPORTED_EXTENSIONS = setOf(SUPPORTED_EXTENSION)

    fun isValidHttpUrl(url: String): Boolean {
        val trimmed = url.trim()
        return trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
    }

    fun isSupportedExtension(extension: String): Boolean {
        return extension.lowercase() in SUPPORTED_EXTENSIONS
    }

    fun extensionFromUrl(url: String): String? {
        val fileName = fileNameFromUrlPath(url)
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension.takeIf { isSupportedExtension(it) }
    }

    fun fileNameFromUrlPath(url: String): String {
        val path = url.trim().substringBefore('?').substringBefore('#')
        val segment = path.substringAfterLast('/')
        return decodeFileName(segment).ifBlank { "book.epub" }
    }

    fun resolveFileName(url: String, contentDisposition: String?): String {
        fileNameFromContentDisposition(contentDisposition)?.let { return it }
        return fileNameFromUrlPath(url)
    }

    fun fileNameFromContentDisposition(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        val filenameStar = Regex("filename\\*=UTF-8''([^;\\s]+)", RegexOption.IGNORE_CASE)
            .find(contentDisposition)?.groupValues?.getOrNull(1)
        if (!filenameStar.isNullOrBlank()) {
            return decodeFileName(filenameStar)
        }
        val filename = Regex("filename=\"?([^\";\\n]+)\"?", RegexOption.IGNORE_CASE)
            .find(contentDisposition)?.groupValues?.getOrNull(1)
        return filename?.let { decodeFileName(it.trim()) }
    }

    private fun decodeFileName(value: String): String {
        return try {
            URLDecoder.decode(value, Charsets.UTF_8.name())
        } catch (_: Exception) {
            value
        }
    }
}
