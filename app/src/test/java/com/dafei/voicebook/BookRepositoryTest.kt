package com.dafei.voicebook

import com.dafei.voicebook.data.model.BookFormat
import com.dafei.voicebook.data.repository.BookRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class BookRepositoryTest {
    @Test
    fun extensionToFormat_mapsKnownExtensions() {
        assertEquals(BookFormat.EPUB, BookRepository.extensionToFormat("epub"))
        assertEquals(BookFormat.UNKNOWN, BookRepository.extensionToFormat("txt"))
        assertEquals(BookFormat.UNKNOWN, BookRepository.extensionToFormat("mobi"))
        assertEquals(BookFormat.UNKNOWN, BookRepository.extensionToFormat("azw"))
        assertEquals(BookFormat.UNKNOWN, BookRepository.extensionToFormat("azw3"))
        assertEquals(BookFormat.UNKNOWN, BookRepository.extensionToFormat("pdf"))
    }
}
