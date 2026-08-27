package com.dafei.voicebook

import com.dafei.voicebook.domain.import.SentenceSplitter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis

class SentenceSplitterTest {
    @Test
    fun splitsChineseSentences() {
        val text = "你好世界。这是第二句！还有第三句？"
        val chunks = SentenceSplitter.split(text)
        assertEquals(3, chunks.size)
        assertTrue(chunks[0].text.contains("你好世界"))
    }

    @Test
    fun doesNotSplitDecimalNumbers() {
        val text = "价格是3.14元。结束。"
        val chunks = SentenceSplitter.split(text)
        assertEquals(2, chunks.size)
        assertTrue(chunks[0].text.contains("3.14"))
    }

    @Test
    fun emptyTextReturnsEmpty() {
        assertTrue(SentenceSplitter.split("").isEmpty())
        assertTrue(SentenceSplitter.splitForImport("").isEmpty())
    }

    @Test
    fun splitForImportMatchesSplitOffsetsAndPreview() {
        val text = "Hello world. 你好世界。Second sentence!"
        val chunks = SentenceSplitter.split(text)
        val slices = SentenceSplitter.splitForImport(text)

        assertEquals(chunks.size, slices.size)
        chunks.zip(slices).forEach { (chunk, slice) ->
            assertEquals(chunk.index, slice.index)
            assertEquals(chunk.byteOffsetStart, slice.byteOffsetStart)
            assertEquals(chunk.byteOffsetEnd, slice.byteOffsetEnd)
            assertEquals(chunk.text.take(120), slice.textPreview)
        }
    }

    @Test
    fun splitForImportFromFileMatchesStringSplit() {
        val text = "Line one.\nLine two. 第三行。"
        val file = File.createTempFile("sentence-split", ".txt")
        file.deleteOnExit()
        file.writeText(text, Charsets.UTF_8)

        val fromFile = SentenceSplitter.splitForImport(file)
        val fromString = SentenceSplitter.splitForImport(text)

        assertEquals(fromString.size, fromFile.size)
        fromString.zip(fromFile).forEach { (a, b) ->
            assertEquals(a.byteOffsetStart, b.byteOffsetStart)
            assertEquals(a.byteOffsetEnd, b.byteOffsetEnd)
            assertEquals(a.textPreview, b.textPreview)
        }
    }

    @Test
    fun handlesEmojiAndMixedScripts() {
        val text = "Hello 👋 world. 中文句子。Done!"
        val chunks = SentenceSplitter.split(text)
        val slices = SentenceSplitter.splitForImport(text)

        assertTrue(chunks.isNotEmpty())
        assertEquals(chunks.size, slices.size)
        chunks.zip(slices).forEach { (chunk, slice) ->
            assertEquals(chunk.byteOffsetStart, slice.byteOffsetStart)
            assertEquals(chunk.byteOffsetEnd, slice.byteOffsetEnd)
        }
    }

    @Test
    fun splitMatchesLegacyImplementation() {
        val samples = listOf(
            "你好世界。这是第二句！还有第三句？",
            "价格是3.14元。结束。",
            "Hello 👋 world. Mixed 中英文。",
            "A".repeat(500) + "。B".repeat(500) + "。",
            "  \n  spaced.  trimmed.  ",
        )
        for (text in samples) {
            val legacyChunks = legacySplit(text)
            val newChunks = SentenceSplitter.split(text)
            assertEquals("chunk count for length ${text.length}", legacyChunks.size, newChunks.size)
            legacyChunks.zip(newChunks).forEachIndexed { index, (legacy, actual) ->
                assertEquals("index at $index", legacy.index, actual.index)
                assertEquals("start at $index", legacy.byteOffsetStart, actual.byteOffsetStart)
                assertEquals("end at $index", legacy.byteOffsetEnd, actual.byteOffsetEnd)
                assertEquals("text at $index", legacy.text, actual.text)
            }
        }
    }

    @Test
    fun splitForImportPreviewIsCappedAt120Chars() {
        val longSentence = "测".repeat(200) + "。"
        val slices = SentenceSplitter.splitForImport(longSentence)
        assertEquals(1, slices.size)
        assertEquals(120, slices[0].textPreview.length)
    }

    @Test
    fun splitForImportLargeTextCompletesWithinTimeBudget() {
        val paragraph = "这是一段测试文本，用于模拟长篇小说导入时的分句压力。" +
            "The quick brown fox jumps over the lazy dog. "
        val text = buildString {
            repeat(800) {
                append(paragraph)
            }
        }
        assertTrue(text.length >= 200_000)

        val elapsedMs = measureTimeMillis {
            val slices = SentenceSplitter.splitForImport(text)
            assertTrue(slices.size > 100)
        }
        assertTrue("splitForImport took ${elapsedMs}ms", elapsedMs < 2_000)
    }

    private fun legacyByteOffsetForCharIndex(text: String, charIndex: Int): Int {
        return text.substring(0, charIndex).toByteArray(Charsets.UTF_8).size
    }

    private fun legacySplit(text: String): List<LegacyChunk> {
        if (text.isEmpty()) return emptyList()

        val bytes = text.toByteArray(Charsets.UTF_8)
        val runes = text.toCharArray()
        val offsets = legacyScanSentenceByteOffsets(text, bytes.size)

        val chunks = mutableListOf<LegacyChunk>()
        for (idx in 0 until offsets.size - 1) {
            val start = offsets[idx]
            val end = offsets[idx + 1]
            if (start >= end) continue
            val sentenceText = String(bytes, start, end - start, Charsets.UTF_8).trim()
            if (sentenceText.isNotEmpty()) {
                chunks.add(
                    LegacyChunk(
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

    private fun legacyScanSentenceByteOffsets(text: String, totalBytes: Int): IntArray {
        val sentenceDelims = setOf('。', '！', '？', '.', '!', '?', '；', ';', '\n')
        val runes = text.toCharArray()
        val offsets = mutableListOf<Int>()
        var i = 0

        while (i < runes.size) {
            offsets.add(legacyByteOffsetForCharIndex(text, i))
            var j = i
            while (j < runes.size) {
                if (runes[j] in sentenceDelims) {
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
            if (j - i == 0) break
            i = j
        }

        if (offsets.isEmpty() || offsets.last() != totalBytes) {
            offsets.add(totalBytes)
        }
        return offsets.toIntArray()
    }

    private data class LegacyChunk(
        val index: Int,
        val byteOffsetStart: Int,
        val byteOffsetEnd: Int,
        val text: String,
    )
}
