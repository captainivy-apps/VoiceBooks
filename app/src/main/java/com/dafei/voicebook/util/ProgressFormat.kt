package com.dafei.voicebook.util

object ProgressFormat {
    /** 将 0.0–1.0 的进度比转为 "(xx.xx%)" */
    fun percentSuffix(ratio: Float): String =
        "(%.2f%%)".format(ratio.coerceIn(0f, 1f) * 100)

    /** 将 current/total 转为 "(xx.xx%)" */
    fun percentSuffix(current: Int, total: Int): String {
        val safeTotal = total.coerceAtLeast(1)
        return percentSuffix(current.toFloat() / safeTotal)
    }
}
