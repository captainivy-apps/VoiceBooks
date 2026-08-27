package com.dafei.voicebook.domain.startup

data class StartupIssue(
    val category: StartupIssueCategory,
    val userMessage: String,
    val debugMessage: String? = null,
    val throwable: Throwable? = null,
) {
    val isLibraryIssue: Boolean
        get() = category == StartupIssueCategory.LIBRARY
}

enum class StartupIssueCategory {
    LIBRARY,
    GENERAL,
}

