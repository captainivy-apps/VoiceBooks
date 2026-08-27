package com.dafei.voicebook

import com.dafei.voicebook.util.ProgressFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressFormatTest {
    @Test
    fun percentSuffix_fromRatio() {
        assertEquals("(50.00%)", ProgressFormat.percentSuffix(0.5f))
    }

    @Test
    fun percentSuffix_fromCurrentAndTotal_zero() {
        assertEquals("(0.00%)", ProgressFormat.percentSuffix(0, 500))
    }

    @Test
    fun percentSuffix_fromCurrentAndTotal_mid() {
        assertEquals("(8.40%)", ProgressFormat.percentSuffix(42, 500))
    }

    @Test
    fun percentSuffix_fromCurrentAndTotal_nearEnd() {
        assertEquals("(99.80%)", ProgressFormat.percentSuffix(499, 500))
    }

    @Test
    fun percentSuffix_clampsRatioAboveOne() {
        assertEquals("(100.00%)", ProgressFormat.percentSuffix(1.5f))
    }

    @Test
    fun percentSuffix_clampsRatioBelowZero() {
        assertEquals("(0.00%)", ProgressFormat.percentSuffix(-0.1f))
    }
}
