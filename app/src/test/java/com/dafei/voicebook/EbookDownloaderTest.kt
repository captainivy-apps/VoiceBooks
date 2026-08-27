package com.dafei.voicebook

import com.dafei.voicebook.domain.import.EbookDownloadError
import com.dafei.voicebook.domain.import.EbookDownloadResult
import com.dafei.voicebook.domain.import.EbookDownloader
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class EbookDownloaderTest {
    private lateinit var server: MockWebServer
    private lateinit var targetDir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        targetDir = createTempDir(prefix = "ebook-downloader-test")
    }

    @After
    fun tearDown() {
        server.shutdown()
        targetDir.deleteRecursively()
    }

    @Test
    fun download_savesFileAndReturnsResolvedName() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Disposition", "attachment; filename=\"sample.epub\"")
                .setBody("fake-epub-content"),
        )

        val targetFile = File(targetDir, "book.tmp")
        val downloader = EbookDownloader()
        var lastProgress = -1

        val result = downloader.download(
            url = server.url("/book").toString(),
            targetFile = targetFile,
            onProgress = { lastProgress = it },
        )

        assertTrue(result is EbookDownloadResult.Success)
        assertEquals("sample.epub", (result as EbookDownloadResult.Success).fileName)
        assertTrue(targetFile.isFile)
        assertEquals("fake-epub-content", targetFile.readText())
        assertEquals(15, lastProgress)
    }

    @Test
    fun download_returnsHttpErrorForNonSuccessResponse() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        val targetFile = File(targetDir, "book.epub")
        val result = EbookDownloader().download(
            url = server.url("/missing.epub").toString(),
            targetFile = targetFile,
            onProgress = {},
        )

        assertEquals(EbookDownloadResult.Failure(EbookDownloadError.HTTP_ERROR), result)
        assertTrue(!targetFile.exists())
    }

    @Test
    fun download_rejectsUnsupportedExtension() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Disposition", "attachment; filename=\"sample.pdf\"")
                .setBody("pdf"),
        )

        val targetFile = File(targetDir, "book.pdf")
        val result = EbookDownloader().download(
            url = server.url("/book.pdf").toString(),
            targetFile = targetFile,
            onProgress = {},
        )

        assertEquals(EbookDownloadResult.Failure(EbookDownloadError.UNSUPPORTED_FORMAT), result)
        assertTrue(!targetFile.exists())
    }
}
