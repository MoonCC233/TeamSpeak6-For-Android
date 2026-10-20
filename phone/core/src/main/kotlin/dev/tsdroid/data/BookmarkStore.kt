package dev.tsdroid.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bookmarks")

class BookmarkStore(private val context: Context) {

    companion object {
        private val KEY_BOOKMARKS = stringPreferencesKey("bookmarks_json")
        private val KEY_AUTO_RECONNECT = booleanPreferencesKey("auto_reconnect")
        private val KEY_LAST_BOOKMARK_ADDRESS = stringPreferencesKey("last_bookmark_address")
    }

    val bookmarks: Flow<List<ServerBookmark>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_BOOKMARKS] ?: "[]"
        parseBookmarks(json)
    }

    val autoReconnect: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_RECONNECT] ?: false
    }

    suspend fun setAutoReconnect(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_RECONNECT] = enabled
        }
    }

    suspend fun save(bookmarks: List<ServerBookmark>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BOOKMARKS] = serializeBookmarks(bookmarks)
        }
    }

    suspend fun add(bookmark: ServerBookmark) {
        context.dataStore.edit { prefs ->
            val current = parseBookmarks(prefs[KEY_BOOKMARKS] ?: "[]")
            prefs[KEY_BOOKMARKS] = serializeBookmarks(current + bookmark)
        }
    }

    suspend fun remove(index: Int) {
        context.dataStore.edit { prefs ->
            val current = parseBookmarks(prefs[KEY_BOOKMARKS] ?: "[]").toMutableList()
            if (index in current.indices) {
                current.removeAt(index)
                prefs[KEY_BOOKMARKS] = serializeBookmarks(current)
            }
        }
    }

    suspend fun replace(index: Int, bookmark: ServerBookmark) {
        context.dataStore.edit { prefs ->
            val current = parseBookmarks(prefs[KEY_BOOKMARKS] ?: "[]").toMutableList()
            if (index in current.indices) {
                // Preserve serverName and iconId from the old bookmark if not set
                val old = current[index]
                current[index] = bookmark.copy(
                    serverName = bookmark.serverName ?: old.serverName,
                    iconId = if (bookmark.iconId != 0L) bookmark.iconId else old.iconId,
                )
                prefs[KEY_BOOKMARKS] = serializeBookmarks(current)
            }
        }
    }

    /** Save the address of the last connected bookmark (for auto-reconnect). */
    suspend fun saveLastBookmarkAddress(address: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_BOOKMARK_ADDRESS] = address
        }
    }

    val lastBookmarkAddress: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_BOOKMARK_ADDRESS] ?: ""
    }

    suspend fun updateServerInfo(address: String, serverName: String, iconId: Long) {
        context.dataStore.edit { prefs ->
            val current = parseBookmarks(prefs[KEY_BOOKMARKS] ?: "[]")
            val updated = current.map { b ->
                if (b.address == address) b.copy(serverName = serverName, iconId = iconId) else b
            }
            prefs[KEY_BOOKMARKS] = serializeBookmarks(updated)
        }
    }

    private fun parseBookmarks(json: String): List<ServerBookmark> {
        if (json.isBlank() || json == "[]") return emptyList()

        val array = try {
            JSONArray(json)
        } catch (_: Exception) {
            // A legacy value with an unescaped backslash that forms no JSON escape — a
            // Windows path such as "C:\users", say — is not valid JSON and never gets here.
            return parseBookmarksLegacy(json)
        }

        // Legacy records quote iconId as a string and spell absent values as the literal
        // "null". Reading those as JSON would silently corrupt any value containing a
        // backslash, because "\t" in "C:\temp" is a *valid* JSON escape that turns into a
        // tab. Detect the old shape and use the parser written for it instead.
        if (isLegacyArray(array)) return parseBookmarksLegacy(json)

        return (0 until array.length()).mapNotNull { index ->
            val obj = array.optJSONObject(index) ?: return@mapNotNull null
            try {
                ServerBookmark(
                    name = obj.optString("name", ""),
                    address = obj.optString("address", ""),
                    nickname = obj.optString("nickname", ""),
                    password = obj.optionalString("password"),
                    channel = obj.optionalString("channel"),
                    serverName = obj.optionalString("serverName"),
                    iconId = obj.optLong("iconId", 0L),
                )
            } catch (_: Exception) {
                // A single malformed entry must not discard the user's other servers.
                null
            }
        }
    }

    /**
     * Every legacy record wrote `"iconId":"7"` with the number quoted, whereas
     * [serializeBookmarks] emits a real JSON number. That makes the two shapes
     * distinguishable without guessing.
     */
    private fun isLegacyArray(array: JSONArray): Boolean {
        for (index in 0 until array.length()) {
            val obj = array.optJSONObject(index) ?: continue
            if (obj.opt("iconId") is String) return true
        }
        return false
    }

    /**
     * Legacy format — `[{"name":"x","password":"null",…}]` with only `"` escaped. Kept so
     * bookmarks saved by earlier builds survive the migration to real JSON.
     */
    private fun parseBookmarksLegacy(json: String): List<ServerBookmark> {
        return try {
            json.removeSurrounding("[", "]").split("},{").mapNotNull { entry ->
                val clean = entry.removePrefix("{").removeSuffix("}")
                val fields = mutableMapOf<String, String>()
                for (pair in clean.split("\",\"")) {
                    val kv = pair.replace("\"", "").split(":", limit = 2)
                    if (kv.size == 2) fields[kv[0]] = kv[1]
                }
                try {
                    ServerBookmark(
                        name = fields["name"] ?: "",
                        address = fields["address"] ?: "",
                        nickname = fields["nickname"] ?: "",
                        password = fields["password"]?.takeIf { it != "null" },
                        channel = fields["channel"]?.takeIf { it != "null" },
                        serverName = fields["serverName"]?.takeIf { it != "null" },
                        iconId = fields["iconId"]?.toLongOrNull() ?: 0,
                    )
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * An absent value is `null` when the key is missing, holds a real JSON null, or — in a
     * legacy record — holds the literal string "null".
     */
    private fun JSONObject.optionalString(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").takeIf { it.isNotBlank() && it != "null" }
    }

    private fun serializeBookmarks(bookmarks: List<ServerBookmark>): String {
        val array = JSONArray()
        for (bookmark in bookmarks) {
            array.put(
                JSONObject().apply {
                    put("name", bookmark.name)
                    put("address", bookmark.address)
                    put("nickname", bookmark.nickname)
                    put("password", bookmark.password ?: JSONObject.NULL)
                    put("channel", bookmark.channel ?: JSONObject.NULL)
                    put("serverName", bookmark.serverName ?: JSONObject.NULL)
                    put("iconId", bookmark.iconId)
                },
            )
        }
        return array.toString()
    }
}
