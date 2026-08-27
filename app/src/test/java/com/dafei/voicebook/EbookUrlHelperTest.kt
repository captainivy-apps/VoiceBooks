package com.dafei.voicebook

import com.dafei.voicebook.domain.import.EbookUrlHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EbookUrlHelperTest {
    @Test
    fun isValidHttpUrl_acceptsHttpAndHttps() {
        assertTrue(EbookUrlHelper.isValidHttpUrl("http://example.com/book.epub"))
        assertTrue(EbookUrlHelper.isValidHttpUrl("https://example.com/book.epub"))
        assertTrue(EbookUrlHelper.isValidHttpUrl("  https://example.com/book.epub  "))
    }

    @Test
    fun isValidHttpUrl_rejectsOtherSchemes() {
        assertFalse(EbookUrlHelper.isValidHttpUrl("ftp://example.com/book.epub"))
        assertFalse(EbookUrlHelper.isValidHttpUrl("not-a-url"))
        assertFalse(EbookUrlHelper.isValidHttpUrl(""))
    }

    @Test
    fun extensionFromUrl_readsPathExtension() {
        assertEquals("epub", EbookUrlHelper.extensionFromUrl("https://example.com/books/demo.epub"))
        assertNull(EbookUrlHelper.extensionFromUrl("https://example.com/book.txt?token=abc"))
        assertNull(EbookUrlHelper.extensionFromUrl("https://example.com/download"))
        assertNull(EbookUrlHelper.extensionFromUrl("https://example.com/book.pdf"))
    }

    @Test
    fun fileNameFromUrlPath_stripsQueryAndDecodes() {
        assertEquals(
            "my book.epub",
            EbookUrlHelper.fileNameFromUrlPath("https://example.com/files/my%20book.epub?sig=1"),
        )
        assertEquals("book.epub", EbookUrlHelper.fileNameFromUrlPath("https://example.com/"))
    }

    @Test
    fun resolveFileName_prefersContentDisposition() {
        assertEquals(
            "downloaded.epub",
            EbookUrlHelper.resolveFileName(
                url = "https://example.com/file?id=1",
                contentDisposition = "attachment; filename=\"downloaded.epub\"",
            ),
        )
        assertEquals(
            "utf8 name.epub",
            EbookUrlHelper.resolveFileName(
                url = "https://example.com/file",
                contentDisposition = "attachment; filename*=UTF-8''utf8%20name.epub",
            ),
        )
    }

    @Test
    fun isSupportedExtension_allowsEpubOnly() {
        assertTrue(EbookUrlHelper.isSupportedExtension("epub"))
        assertFalse(EbookUrlHelper.isSupportedExtension("AZW3"))
        assertFalse(EbookUrlHelper.isSupportedExtension("txt"))
        assertFalse(EbookUrlHelper.isSupportedExtension("pdf"))
    }
}
