package com.dafei.voicebook

import com.dafei.voicebook.tts.TtsTextAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsTextAdapterTest {
    @Test
    fun chineseModelSkipsPureEnglish() {
        assertNull(TtsTextAdapter.adaptForModel("Cover", "zh"))
        assertNull(TtsTextAdapter.adaptForModel("  Hello World  ", "zh"))
    }

    @Test
    fun chineseModelKeepsChineseAndStripsLatin() {
        assertEquals("第一章", TtsTextAdapter.adaptForModel("第一章 Cover", "zh"))
        assertEquals("这是测试。", TtsTextAdapter.adaptForModel("这是 Cover 测试。", "zh"))
        assertEquals("第章", TtsTextAdapter.adaptForModel("第Cover章", "zh"))
    }

    @Test
    fun bilingualModelKeepsEnglish() {
        assertEquals("Cover", TtsTextAdapter.adaptForModel("Cover", "zh+en"))
    }

    @Test
    fun englishModelSkipsPureChinese() {
        assertNull(TtsTextAdapter.adaptForModel("你好世界", "en"))
    }

    @Test
    fun englishModelKeepsLatin() {
        assertEquals("Hello world", TtsTextAdapter.adaptForModel("Hello world", "en"))
    }
}
