package dev.tsdroid.ui.screen

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/**
 * [initialAnimeBackground] is what startup read from the settings before it let the app in. The
 * screens take it as the initial value of their own wallpaper setting so the first frame they draw
 * already has the background the splash prepared, instead of an empty one waiting for DataStore.
 */
@Composable
fun AppNavigation(initialAnimeBackground: Boolean) {
    val navController = rememberNavController()

    // Back to the connection screen. It is deliberately kept on the back stack while a session is
    // running — the session lives in TsConnectionService, not in the server screen — so leaving the
    // server screen never has to drop the connection, and the back gesture keeps working.
    val backToHome: () -> Unit = {
        if (!navController.popBackStack(route = "connection", inclusive = false)) {
            navController.navigate("connection")
        }
    }

    NavHost(navController = navController, startDestination = "connection") {
        composable("connection") {
            ConnectionScreen(
                onConnected = {
                    navController.navigate("server") { launchSingleTop = true }
                },
                onNavigateToAbout = { navController.navigate("about") },
                initialAnimeBackground = initialAnimeBackground,
            )
        }
        composable("server") {
            ServerScreen(
                onBackToHome = backToHome,
                onNavigateToAbout = { navController.navigate("about") },
                initialAnimeBackground = initialAnimeBackground,
            )
        }
        composable("about") {
            AboutScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
