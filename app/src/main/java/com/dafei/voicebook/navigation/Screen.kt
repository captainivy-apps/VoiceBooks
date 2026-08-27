package com.dafei.voicebook.navigation

sealed class Screen(val route: String) {
    data object Library : Screen("library")
    data object Settings : Screen("settings")
    data object Player : Screen("player/{bookId}") {
        fun createRoute(bookId: String) = "player/$bookId"
    }

    companion object {
        const val SETTINGS_ARG_RECOVERY = "startupRecovery"
        fun settingsRoute(startupRecovery: Boolean): String {
            return "${Settings.route}?$SETTINGS_ARG_RECOVERY=$startupRecovery"
        }
    }
}
