package com.dafei.voicebook.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object FileMd5Hasher {
    private const val BUFFER_SIZE = 8192

    fun md5Of(file: File): String = file.inputStream().use { md5Of(it) }

    fun md5Of(inputStream: InputStream): String {
        val digest = MessageDigest.getInstance("MD5")
        val buffer = ByteArray(BUFFER_SIZE)
        var read: Int
        while (inputStream.read(buffer).also { read = it } != -1) {
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
