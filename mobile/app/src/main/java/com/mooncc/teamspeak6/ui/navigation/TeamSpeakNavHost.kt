package com.mooncc.teamspeak6.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mooncc.teamspeak6.ui.screen.bookmarks.BookmarksScreen
import com.mooncc.teamspeak6.ui.screen.server.ServerScreen
import com.mooncc.teamspeak6.ui.screen.settings.SettingsScreen

@Composable
fun TeamSpeakNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = Routes.BOOKMARKS) {
        composable(Routes.BOOKMARKS) {
            BookmarksScreen(
                onConnect = { bookmark ->
                    navController.navigate(Routes.serverForBookmark(bookmark.id))
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(
            route = "${Routes.SERVER}?bookmarkId={bookmarkId}",
            arguments = listOf(
                navArgument("bookmarkId") {
                    type = NavType.LongType
                    defaultValue = 0L
                },
            ),
        ) { entry ->
            val bookmarkId = entry.arguments?.getLong("bookmarkId") ?: 0L
            ServerScreen(
                bookmarkId = bookmarkId,
                onDisconnected = {
                    navController.popBackStack(Routes.BOOKMARKS, inclusive = false)
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
