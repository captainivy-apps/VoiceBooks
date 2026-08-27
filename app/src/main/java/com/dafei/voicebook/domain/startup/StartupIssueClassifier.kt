package com.dafei.voicebook.domain.startup

import android.database.sqlite.SQLiteException
import java.io.IOException

object StartupIssueClassifier {
    fun classify(throwable: Throwable): StartupIssue {
        val category = if (isLibraryIssue(throwable)) {
            StartupIssueCategory.LIBRARY
        } else {
            StartupIssueCategory.GENERAL
        }
        val userMessage = if (category == StartupIssueCategory.LIBRARY) {
            "检测到书库数据异常，请前往设置页重建书库。"
        } else {
            "启动时发生异常，请稍后重试。"
        }
        return StartupIssue(
            category = category,
            userMessage = userMessage,
            debugMessage = throwable.message,
            throwable = throwable,
        )
    }

    private fun isLibraryIssue(throwable: Throwable): Boolean {
        val chain = generateSequence(throwable) { it.cause }.toList()
        return chain.any { cause ->
            cause is SQLiteException ||
                cause is IOException ||
                cause.message.orEmpty().contains("books", ignoreCase = true) ||
                cause.message.orEmpty().contains("voicebook.db", ignoreCase = true) ||
                cause.message.orEmpty().contains("migration", ignoreCase = true) ||
                cause.message.orEmpty().contains("no such table", ignoreCase = true) ||
                cause.message.orEmpty().contains("contentMd5", ignoreCase = true)
        }
    }
}

