package com.dafei.voicebook

import com.dafei.voicebook.util.FileMd5Hasher
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class FileMd5HasherTest {
    @Test
    fun md5Of_inputStream_matchesKnownHash() {
        val content = "hello"
        val hash = FileMd5Hasher.md5Of(ByteArrayInputStream(content.toByteArray()))
        assertEquals("5d41402abc4b2a76b9719d911017c592", hash)
    }

    @Test
    fun md5Of_file_matchesInputStreamHash() {
        val file = File.createTempFile("md5-test", ".txt")
        file.writeText("hello world")
        try {
            val fileHash = FileMd5Hasher.md5Of(file)
            val streamHash = FileMd5Hasher.md5Of(file.inputStream())
            assertEquals(streamHash, fileHash)
        } finally {
            file.delete()
        }
    }
}
