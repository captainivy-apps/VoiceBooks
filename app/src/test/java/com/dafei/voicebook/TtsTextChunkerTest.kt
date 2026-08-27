package com.dafei.voicebook

import com.dafei.voicebook.tts.TtsTextChunker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsTextChunkerTest {
    @Test
    fun returnsSingleChunkForShortText() {
        val chunks = TtsTextChunker.chunk("你好世界。", maxChars = 400)
        assertEquals(listOf("你好世界。"), chunks)
    }

    @Test
    fun splitsLongTextBySentenceDelimiters() {
        val part1 = "这是一段比较长的测试文本。".repeat(35)
        val part2 = "第二句开始。"
        val chunks = TtsTextChunker.chunk(part1 + part2, maxChars = 400)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 400 })
        assertEquals(part1 + part2, chunks.joinToString(""))
    }

    @Test
    fun splitsVeryLongSegmentWithoutDelimiters() {
        val text = "字".repeat(900)
        val chunks = TtsTextChunker.chunk(text, maxChars = 400)
        assertTrue(chunks.size >= 3)
        assertTrue(chunks.all { it.length <= 400 })
        assertEquals(text, chunks.joinToString(""))
    }
}
