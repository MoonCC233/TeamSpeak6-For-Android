package com.mooncc.teamspeak9.ui.navigation

/**
 * Top level destinations.
 */
object Routes {
    const val BOOKMARKS = "bookmarks"
    const val SERVER = "server"
    const val SETTINGS = "settings"

    fun serverForBookmark(bookmarkId: Long): String = "$SERVER?bookmarkId=$bookmarkId"
}
