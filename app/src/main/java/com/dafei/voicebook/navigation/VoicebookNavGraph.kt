package com.dafei.voicebook.navigation

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dafei.voicebook.R
import com.dafei.voicebook.VoicebookApp
import com.dafei.voicebook.domain.startup.StartupIssue
import com.dafei.voicebook.domain.playback.PlaybackController
import com.dafei.voicebook.domain.playback.PlaybackState
import com.dafei.voicebook.ui.layout.AppBottomNavigationBar
import com.dafei.voicebook.ui.layout.AppNavDestination
import com.dafei.voicebook.ui.layout.AppSideNavigationRail
import com.dafei.voicebook.ui.layout.isLandscape
import com.dafei.voicebook.ui.AppToast
import com.dafei.voicebook.ui.library.LibraryScreen
import com.dafei.voicebook.ui.player.PlayerScreen
import com.dafei.voicebook.ui.settings.SettingsScreen

@Composable
fun VoicebookNavGraph(
    navController: NavHostController = rememberNavController(),
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val playback by PlaybackController.state.collectAsState()
    val context = LocalContext.current
    val app = context.applicationContext as? VoicebookApp
    val ttsNavBlocked by (app?.ttsSettingsGate?.isBlocking?.collectAsState() ?: remember { mutableStateOf(false) })
    val startupIssue by (app?.startupIssue?.collectAsState() ?: remember { mutableStateOf<StartupIssue?>(null) })
    var startupDialogDismissed by remember { mutableStateOf(false) }
    val landscape = isLandscape()
    val showBottomBar = currentRoute == Screen.Library.route ||
        currentRoute?.startsWith(Screen.Settings.route) == true

    val navDestinations = remember {
        listOf(
            AppNavDestination(
                route = Screen.Library.route,
                icon = Icons.Default.Home,
                label = "", // filled below
            ),
            AppNavDestination(
                route = Screen.Settings.route,
                icon = Icons.Default.Settings,
                label = "",
            ),
        )
    }
    val libraryTitle = stringResource(R.string.library_title)
    val settingsTitle = stringResource(R.string.settings_title)
    val destinations = remember(libraryTitle, settingsTitle) {
        listOf(
            navDestinations[0].copy(label = libraryTitle),
            navDestinations[1].copy(label = settingsTitle),
        )
    }
    val navigateToTab: (String) -> Unit = { route ->
        runCatching {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    LaunchedEffect(playback.bookId, playback.playbackState, currentRoute) {
        if (PlaybackController.isPlayerScreenDismissed()) return@LaunchedEffect
        val bookId = playback.bookId
        if (bookId.isEmpty()) return@LaunchedEffect
        val sessionActive = playback.playbackState == PlaybackState.LOADING ||
            playback.playbackState == PlaybackState.PLAYING ||
            playback.playbackState == PlaybackState.PAUSED ||
            playback.playbackState == PlaybackState.ERROR
        if (!sessionActive) return@LaunchedEffect

        val playerRoute = Screen.Player.createRoute(bookId)
        if (currentRoute == playerRoute) return@LaunchedEffect

        navController.navigate(playerRoute) {
            launchSingleTop = true
        }
    }

    LaunchedEffect(startupIssue) {
        if (startupIssue == null) {
            startupDialogDismissed = false
            return@LaunchedEffect
        }
        if (startupIssue?.isLibraryIssue == false) {
            AppToast.show(context, startupIssue?.userMessage ?: "")
            app?.clearStartupIssue()
            return@LaunchedEffect
        }
        if (startupIssue?.isLibraryIssue == true &&
            app?.shouldShowLibraryRecoveryPrompt() == true &&
            currentRoute?.startsWith(Screen.Settings.route) != true
        ) {
            navController.navigate(Screen.settingsRoute(startupRecovery = true)) {
                launchSingleTop = true
            }
        }
    }

    val navHost: @Composable (Modifier) -> Unit = { modifier ->
        NavHost(
            navController = navController,
            startDestination = Screen.Library.route,
            modifier = modifier,
        ) {
            composable(Screen.Library.route) {
                LibraryScreen(
                    onOpenBook = { bookId ->
                        PlaybackController.clearPlayerScreenDismissed()
                        navController.navigate(Screen.Player.createRoute(bookId))
                    },
                )
            }
            composable(
                route = "${Screen.Settings.route}?${Screen.SETTINGS_ARG_RECOVERY}={${Screen.SETTINGS_ARG_RECOVERY}}",
                arguments = listOf(
                    navArgument(Screen.SETTINGS_ARG_RECOVERY) {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) { backStackEntry ->
                val fromRecovery = backStackEntry.arguments?.getBoolean(Screen.SETTINGS_ARG_RECOVERY) ?: false
                SettingsScreen(startupRecoveryMode = fromRecovery)
            }
            composable(
                route = Screen.Player.route,
                arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val bookId = backStackEntry.arguments?.getString("bookId") ?: return@composable
                PlayerScreen(
                    bookId = bookId,
                    onBack = {
                        if (!navController.popBackStack()) {
                            navController.navigate(Screen.Library.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    },
                )
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.navigationBars.only(
            WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal,
        ),
        bottomBar = {
            if (showBottomBar && !landscape) {
                AppBottomNavigationBar(
                    destinations = destinations,
                    currentRoute = currentRoute,
                    navBlocked = ttsNavBlocked,
                    onNavigate = navigateToTab,
                )
            }
        },
    ) { padding ->
        if (showBottomBar && landscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                navHost(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                AppSideNavigationRail(
                    destinations = destinations,
                    currentRoute = currentRoute,
                    navBlocked = ttsNavBlocked,
                    onNavigate = navigateToTab,
                )
            }
        } else {
            navHost(Modifier.padding(padding))
        }
    }

    val shouldShowLibraryRecoveryDialog =
        startupIssue?.isLibraryIssue == true &&
            !startupDialogDismissed &&
            (app?.shouldShowLibraryRecoveryPrompt() == true)
    if (shouldShowLibraryRecoveryDialog) {
        AlertDialog(
            onDismissRequest = {
                startupDialogDismissed = true
                app?.markLibraryRecoveryPromptShown()
            },
            title = { Text(stringResource(R.string.startup_library_issue_title)) },
            text = { Text(stringResource(R.string.startup_library_issue_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        startupDialogDismissed = true
                        app?.markLibraryRecoveryPromptShown()
                        navController.navigate(Screen.settingsRoute(startupRecovery = true)) {
                            launchSingleTop = true
                        }
                        app?.clearStartupIssue()
                    },
                ) {
                    Text(stringResource(R.string.startup_library_issue_action))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        startupDialogDismissed = true
                        app?.markLibraryRecoveryPromptShown()
                    },
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}
