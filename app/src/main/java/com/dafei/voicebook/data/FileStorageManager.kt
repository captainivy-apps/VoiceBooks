package com.dafei.voicebook.data

import android.content.Context
import java.io.File
import java.util.UUID

class FileStorageManager(private val context: Context) {
    val booksDir: File get() = File(context.filesDir, "books").apply { mkdirs() }
    val coversDir: File get() = File(context.filesDir, "covers").apply { mkdirs() }
    val ttsModelsDir: File get() = File(context.filesDir, "tts_models").apply { mkdirs() }
    val textDir: File get() = File(context.filesDir, "text").apply { mkdirs() }

    fun newBookId(): String = UUID.randomUUID().toString()

    fun bookSourceFile(bookId: String, extension: String): File {
        return File(booksDir, "$bookId.$extension")
    }

    fun bookTextFile(bookId: String): File = File(textDir, "$bookId.txt")

    fun bookCoverFile(bookId: String): File = File(coversDir, "$bookId.jpg")

    fun ttsModelDir(modelId: String): File = File(ttsModelsDir, modelId).apply { mkdirs() }

    fun deleteBookFiles(bookId: String) {
        booksDir.listFiles()?.filter { it.name.startsWith(bookId) }?.forEach { it.delete() }
        File(textDir, "$bookId.txt").delete()
        File(coversDir, "$bookId.jpg").delete()
    }

    fun deleteBooksFiles(bookIds: List<String>) {
        bookIds.forEach { deleteBookFiles(it) }
    }
}
