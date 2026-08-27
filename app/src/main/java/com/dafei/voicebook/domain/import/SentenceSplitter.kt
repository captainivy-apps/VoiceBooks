package com.dafei.voicebook.domain.import

import com.dafei.voicebook.data.model.SentenceChunk
import com.dafei.voicebook.data.model.SentenceIndexSlice
import java.io.File

/**
 * Port of audio_books/comm/scan_text.go ScanTextFileBySentence logic.
 */
object SentenceSplitter {
    private const val PREVIEW_MAX_CHARS = 120

    private val sentenceDelims = setOf('。', '！', '？', '.', '!', '?', '；', ';', '\n')

    fun split(text: String): List<SentenceChunk> {
        if (text.isEmpty()) return emptyList()

        val bytes = text.toByteArray(Charsets.UTF_8)
        val runes = text.toCharArray()
        val offsets = scanSentenceByteOffsets(text, runes, bytes.size)

        val chunks = mutableListOf<SentenceChunk>()
        for (idx in 0 until offsets.size - 1) {
            val start = offsets[idx]
            val end = offsets[idx + 1]
            if (start >= end) continue
            val sentenceText = String(bytes, start, end - start, Charsets.UTF_8).trim()
            if (sentenceText.isNotEmpty()) {
                chunks.add(
                    SentenceChunk(
                        index = chunks.size,
                        byteOffsetStart = start,
                        byteOffsetEnd = end,
                        text = sentenceText,
                    ),
                )
            }
        }
        return chunks
    }

    fun splitForImport(text: String): List<SentenceIndexSlice> {
        if (text.isEmpty()) return emptyList()

        val bytes = text.toByteArray(Charsets.UTF_8)
        return buildImportSlices(bytes, text.toCharArray(), text)
    }

    fun splitForImport(file: File): List<SentenceIndexSlice> {
        val bytes = file.readBytes()
        if (bytes.isEmpty()) return emptyList()

        val text = String(bytes, Charsets.UTF_8)
        return buildImportSlices(bytes, text.toCharArray(), text)
    }

    private fun buildImportSlices(
        bytes: ByteArray,
        runes: CharArray,
        text: String,
    ): List<SentenceIndexSlice> {
        val offsets = scanSentenceByteOffsets(text, runes, bytes.size)
        val slices = mutableListOf<SentenceIndexSlice>()
        for (idx in 0 until offsets.size - 1) {
            val start = offsets[idx]
            val end = offsets[idx + 1]
            if (start >= end) continue
            val trimmed = String(bytes, start, end - start, Charsets.UTF_8).trim()
            if (trimmed.isNotEmpty()) {
                slices.add(
                    SentenceIndexSlice(
                        index = slices.size,
                        byteOffsetStart = start,
                        byteOffsetEnd = end,
                        textPreview = trimmed.take(PREVIEW_MAX_CHARS),
                    ),
                )
            }
        }
        return slices
    }

    private fun scanSentenceByteOffsets(
        text: String,
        runes: CharArray,
        totalBytes: Int,
    ): IntArray {
        val offsets = mutableListOf<Int>()
        var i = 0
        var byteOffset = 0

        while (i < runes.size) {
            offsets.add(byteOffset)
            var j = i
            while (j < runes.size) {
                if (isSentenceDelim(runes[j])) {
                    if (runes[j] == '.' && j > 0 && j + 1 < runes.size &&
                        runes[j - 1].isDigit() && runes[j + 1].isDigit()
                    ) {
                        j++
                        continue
                    }
                    if ((runes[j] == '%' || runes[j] == '％') && j > 0) {
                        var k = j - 1
                        while (k >= 0 && (runes[k].isDigit() || runes[k] == '.')) k--
                        if (k != j - 1) {
                            j++
                            continue
                        }
                    }
                    j++
                    break
                }
                j++
            }
            val sentLen = j - i
            if (sentLen == 0) break
            byteOffset += segmentUtf8ByteLength(text, i, j)
            i = j
        }

        if (offsets.isEmpty() || offsets.last() != totalBytes) {
            offsets.add(totalBytes)
        }
        return offsets.toIntArray()
    }

    private fun isSentenceDelim(c: Char): Boolean = c in sentenceDelims

    private fun segmentUtf8ByteLength(text: String, start: Int, end: Int): Int {
        return text.substring(start, end).toByteArray(Charsets.UTF_8).size
    }
}
