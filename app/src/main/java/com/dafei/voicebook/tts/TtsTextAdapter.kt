package com.dafei.voicebook.tts

/**
 * Adapts book text to the active TTS model language.
 * Chinese-only models cannot synthesize pure Latin words (Sherpa reports OOV and returns empty audio).
 */
object TtsTextAdapter {
    fun adaptForModel(text: String, modelLanguage: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val lang = modelLanguage.lowercase()
        val supportsEn = "en" in lang
        val supportsZh = "zh" in lang || "yue" in lang

        return when {
            supportsEn && supportsZh -> trimmed
            supportsZh && !supportsEn -> stripLatinWords(trimmed).takeIf { hasCjkOrDigit(it) }
            supportsEn && !supportsZh -> stripCjk(trimmed).takeIf { hasLatinLetter(it) }
            else -> trimmed
        }
    }

    private fun stripLatinWords(text: String): String {
        val result = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (isLatinLetter(char)) {
                while (index < text.length) {
                    val current = text[index]
                    if (isLatinLetter(current) || current == '\'' || current == '-' || current == '.') {
                        index++
                    } else {
                        break
                    }
                }
                continue
            }
            result.append(char)
            index++
        }
        return result.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun stripCjk(text: String): String {
        val result = StringBuilder(text.length)
        for (char in text) {
            if (!isCjk(char)) result.append(char)
        }
        return result.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun hasCjkOrDigit(text: String): Boolean =
        text.any { isCjk(it) || it.isDigit() }

    private fun hasLatinLetter(text: String): Boolean =
        text.any(::isLatinLetter)

    private fun isLatinLetter(char: Char): Boolean =
        char in 'A'..'Z' || char in 'a'..'z'

    private fun isCjk(char: Char): Boolean =
        char.code in 0x4E00..0x9FFF ||
            char.code in 0x3400..0x4DBF ||
            char.code in 0xF900..0xFAFF
}
