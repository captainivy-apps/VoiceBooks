package com.dafei.voicebook.tts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class TtsModelDownloader(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun downloadAndExtract(
        url: String,
        targetDir: File,
        onProgress: (Float) -> Unit,
    ): TtsModelDownloadResult = withContext(Dispatchers.IO) {
        targetDir.mkdirs()
        val tempArchive = File(targetDir.parentFile, "${targetDir.name}.tar.bz2")

        try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext TtsModelDownloadResult.Failure(
                        "服务器返回错误：HTTP ${response.code}",
                    )
                }
                val body = response.body
                    ?: return@withContext TtsModelDownloadResult.Failure("服务器响应为空")
                val total = body.contentLength()
                var downloaded = 0L

                body.byteStream().use { input ->
                    FileOutputStream(tempArchive).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                onProgress(downloaded.toFloat() / total.toFloat())
                            }
                        }
                    }
                }
            }

            extractTarBz2(tempArchive, targetDir)
            tempArchive.delete()
            onProgress(1f)
            TtsModelDownloadResult.Success
        } catch (e: CancellationException) {
            tempArchive.delete()
            throw e
        } catch (e: Exception) {
            tempArchive.delete()
            TtsModelDownloadResult.Failure(e.message ?: "网络连接失败")
        }
    }

    private fun extractTarBz2(archive: File, targetDir: File) {
        BufferedInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))).use { bzIn ->
            TarArchiveInputStream(bzIn).use { tarIn ->
                var entry = tarIn.nextEntry
                while (entry != null) {
                    val relativePath = tarEntryRelativePath(entry.name)
                    if (relativePath.isNotEmpty()) {
                        val outFile = File(targetDir, relativePath)
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                tarIn.copyTo(out)
                            }
                        }
                    }
                    entry = tarIn.nextEntry
                }
            }
        }
    }

    /** Strip the archive's top-level folder, e.g. vits-melo-tts-zh_en/dict/... -> dict/... */
    private fun tarEntryRelativePath(entryName: String): String {
        val normalized = entryName.removePrefix("./").trimEnd('/')
        val parts = normalized.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        if (parts.size == 1) return parts[0]
        return parts.drop(1).joinToString("/")
    }
}
