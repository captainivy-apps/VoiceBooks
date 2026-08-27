package com.dafei.voicebook.domain.import

import android.content.Context
import com.dafei.voicebook.R
import java.io.IOException
import java.util.zip.ZipException

object ImportErrorMessages {
    class EmptyContentException : IllegalStateException()

    class UnsupportedFormatException(val extension: String) : IllegalArgumentException(extension)

    class InvalidUrlException : IllegalArgumentException()

    class EpubParseException(cause: Throwable) : IllegalStateException(cause.message, cause)

    fun downloadErrorMessage(error: EbookDownloadError, context: Context): String {
        return when (error) {
            EbookDownloadError.INVALID_URL -> context.getString(R.string.import_error_invalid_url)
            EbookDownloadError.HTTP_ERROR -> context.getString(R.string.import_error_download_http)
            EbookDownloadError.NETWORK_ERROR -> context.getString(R.string.import_error_download_network)
            EbookDownloadError.EMPTY_RESPONSE -> context.getString(R.string.import_error_download_network)
            EbookDownloadError.UNSUPPORTED_FORMAT -> context.getString(R.string.import_error_unsupported_format, "?")
        }
    }

    fun toUserMessage(throwable: Throwable, context: Context): String {
        return when (throwable) {
            is EmptyContentException -> context.getString(R.string.import_error_empty_content)
            is InvalidUrlException -> context.getString(R.string.import_error_invalid_url)
            is UnsupportedFormatException -> context.getString(
                R.string.import_error_unsupported_format,
                throwable.extension.ifBlank { "?" },
            )
            is EpubParseException -> {
                val detail = throwable.cause?.message?.takeIf { it.isNotBlank() }
                    ?: throwable.message?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.import_failed)
                context.getString(R.string.import_error_epub_parse, detail)
            }
            is IllegalStateException -> {
                val message = throwable.message?.takeIf { it.isNotBlank() }
                if (message != null) message else context.getString(R.string.import_failed)
            }
            is IllegalArgumentException -> {
                val message = throwable.message?.takeIf { it.isNotBlank() }
                if (message != null) message else context.getString(R.string.import_failed)
            }
            is IOException, is ZipException -> context.getString(R.string.import_error_read_file)
            else -> {
                val detail = throwable.message?.takeIf { it.isNotBlank() }
                if (detail != null) {
                    context.getString(R.string.import_failed_detail, detail)
                } else {
                    context.getString(R.string.import_failed)
                }
            }
        }
    }
}
