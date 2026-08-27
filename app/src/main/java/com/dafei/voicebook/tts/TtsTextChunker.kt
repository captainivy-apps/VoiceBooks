package com.dafei.voicebook.tts

import com.dafei.voicebook.domain.import.SentenceSplitter

/** Split long paragraphs into TTS-safe chunks without changing engines. */
object TtsTextChunker {
    private val secondaryDelims = setOf('，', '、', ',', '；', ';', '：', ':', ' ')

    fun chunk(text: String, maxChars: Int): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (maxChars <= 0 || trimmed.length <= maxChars) return listOf(trimmed)

        val sentences = SentenceSplitter.split(trimmed)
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
        if (sentences.size > 1) {
            return sentences.flatMap { chunk(it, maxChars) }
        }

        return splitLongSegment(trimmed, maxChars)
    }

    private fun splitLongSegment(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)

        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val remaining = text.length - start
            if (remaining <= maxChars) {
                text.substring(start).trim().takeIf { it.isNotEmpty() }?.let(result::add)
                break
            }
            val windowEnd = start + maxChars
            var splitAt = -1
            for (j in windowEnd downTo start + 1) {
                if (text[j] in secondaryDelims) {
                    splitAt = j + 1
                    break
                }
            }
            if (splitAt <= start) splitAt = windowEnd
            text.substring(start, splitAt).trim().takeIf { it.isNotEmpty() }?.let(result::add)
            start = splitAt
        }
        return result
    }
}
