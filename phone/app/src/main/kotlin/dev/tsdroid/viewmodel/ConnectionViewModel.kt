package dev.tsdroid.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.tsdroid.bridge.MAX_NICKNAME_COLLISION_ATTEMPTS
import dev.tsdroid.bridge.hasNicknameCollision
import dev.tsdroid.bridge.nicknameWithCollisionSuffix
import dev.tsdroid.han.R
import dev.tsdroid.data.BookmarkStore
import dev.tsdroid.data.ServerBookmark
import dev.tsdroid.service.TsConnectionService
import dev.tslib.Channel
import dev.tslib.ChannelTree
import dev.tslib.Client
import dev.tslib.ConnectionState
import dev.tslib.Identity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ConnectionViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "ConnectionViewModel"
        private const val MIN_NICKNAME_LENGTH = 3
        /** Survit aux recréations du ViewModel dans le même processus. */
        private var autoReconnectAttempted = false

        /**
         * Whether this process has already decided what to do about the session the service is
         * running. Deliberately per process rather than per screen: backing out of the server screen
         * must not be undone by the connection screen composing again.
         */
        private var sessionResumeHandled = false
    }

    private val bookmarkStore = BookmarkStore(application)

    val bookmarks: StateFlow<List<ServerBookmark>> = bookmarkStore.bookmarks
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val autoReconnect: StateFlow<Boolean> = bookmarkStore.autoReconnect
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val address = MutableStateFlow("")
    val nickname = MutableStateFlow("")
    val password = MutableStateFlow("")
    val channel = MutableStateFlow("")

    /** Index du favori en cours d'édition, ou -1 si ajout. */
    private val _editingIndex = MutableStateFlow(-1)
    val editingIndex: StateFlow<Int> = _editingIndex.asStateFlow()

    val browsedChannels = MutableStateFlow<List<Channel>>(emptyList())
    val isBrowsing = MutableStateFlow(false)
    val showChannelPicker = MutableStateFlow(false)

    private val _bookmarkIcons = MutableStateFlow<Map<Long, ImageBitmap>>(emptyMap())
    val bookmarkIcons: StateFlow<Map<Long, ImageBitmap>> = _bookmarkIcons.asStateFlow()

    init {
        // Load bookmark icons from disk cache
        viewModelScope.launch {
            bookmarkStore.bookmarks.collect { list ->
                loadBookmarkIcons(list)
            }
        }
    }

    /**
     * Reads the cached server icons off disk.
     *
     * On [Dispatchers.IO]: this runs as soon as the connection screen appears — the first screen after
     * the splash — and file reads plus bitmap decodes were landing on exactly those frames.
     */
    private suspend fun loadBookmarkIcons(bookmarks: List<ServerBookmark>) {
        val newIcons = withContext(Dispatchers.IO) {
            val iconsDir = File(getApplication<Application>().cacheDir, "icons")
            if (!iconsDir.exists()) return@withContext emptyMap()
            val found = mutableMapOf<Long, ImageBitmap>()
            for (b in bookmarks) {
                if (b.iconId == 0L) continue
                if (_bookmarkIcons.value.containsKey(b.iconId)) continue
                val file = File(iconsDir, b.iconId.toString())
                if (file.exists() && file.length() > 0) {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        found[b.iconId] = bitmap.asImageBitmap()
                    }
                }
            }
            found
        }
        if (newIcons.isNotEmpty()) {
            _bookmarkIcons.value = _bookmarkIcons.value + newIcons
        }
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<Int> = _connectionState.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var connectJob: kotlinx.coroutines.Job? = null
    private var cloneBypassIdentity: Identity? = null

    private data class ConnectionInput(
        val address: String,
        val nickname: String
    )

    /**
     * Validates and normalizes the server address and nickname before connection.
     *
     * @return Validated connection input, or `null` if validation fails.
     */
    private fun getValidatedConnectionInput(): ConnectionInput? {
        val addr = address.value.trim()
        val nick = nickname.value.trim()

        val errorRes = when {
            addr.isEmpty() || nick.isEmpty() ->
                R.string.error_address_nickname_required

            nick.length < MIN_NICKNAME_LENGTH ->
                R.string.error_nickname_too_short

            else -> null
        }

        if (errorRes != null) {
            _error.value = getApplication<Application>().getString(errorRes)
            return null
        }

        return ConnectionInput(
            address = addr,
            nickname = nick
        )
    }

    fun connect(onConnected: () -> Unit) {
        val input = getValidatedConnectionInput() ?: return
        val addr = input.address
        val nick = input.nickname

        val existingService = TsConnectionService.instance
        if (existingService?.hasActiveConnection(addr) == true) {
            _connectionState.value = ConnectionState.CONNECTED
            _error.value = null
            onConnected()
            return
        }

        _connectionState.value = ConnectionState.CONNECTING
        _error.value = null

        val context = getApplication<Application>()

        // Check for overlay permission before starting the service
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            _connectionState.value = ConnectionState.DISCONNECTED
            _error.value = context.getString(R.string.error_overlay_permission_required)
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }

        try {
            val intent = Intent(context, TsConnectionService::class.java)
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.DISCONNECTED
            _error.value = e.message ?: getApplication<Application>().getString(R.string.connection_failed)
            return
        }

        // Cancel any previous connection attempt to avoid stale collectors
        connectJob?.cancel()

        // Wait for the service instance to be available
        connectJob = viewModelScope.launch {
            var attempts = 0
            while (TsConnectionService.instance == null && attempts < 50) {
                kotlinx.coroutines.delay(100)
                attempts++
            }

            val service = TsConnectionService.instance
            if (service == null) {
                _connectionState.value = ConnectionState.DISCONNECTED
                _error.value = getApplication<Application>().getString(R.string.error_service_start_failed)
                return@launch
            }

            try {
                val identity = getOrCreateIdentity()
                // NOTE: this identity is deliberately NOT closed here. The service
                // keeps it in SessionParams and borrows the native handle again on
                // every auto-reconnect attempt; Identity.borrow() throws once
                // close() has been called, so closing it would silently kill
                // auto-reconnect. Destruction is deferred by the borrow protocol
                // until the last client releases it.
                val pw = password.value.trim().takeIf { it.isNotEmpty() }
                var connectionFailure = service.connect(addr, identity, nick, pw)
                if (connectionFailure?.isTooManyClonesFailure() == true) {
                    Log.w(TAG, "Too many clones for saved identity; retrying with a temporary identity")
                    connectionFailure = service.connect(addr, getCloneBypassIdentity(), nick, pw)
                }

                if (connectionFailure == null) {
                    _connectionState.value = ConnectionState.CONNECTED
                    onConnected()
                } else {
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _error.value = connectionFailure.message
                        ?: getApplication<Application>().getString(R.string.connection_failed)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.DISCONNECTED
                _error.value = e.message ?: getApplication<Application>().getString(R.string.connection_failed)
            }
        }
    }

    fun resumeExistingConnection(onConnected: () -> Unit): Boolean {
        val service = TsConnectionService.instance ?: return false
        if (!service.hasActiveConnection()) return false

        _connectionState.value = ConnectionState.CONNECTED
        _error.value = null
        onConnected()
        return true
    }

    /**
     * [resumeExistingConnection], but only the first time this process asks.
     *
     * Jumping straight into a session that the service is still running is what the app wants on
     * start-up. It is exactly what it must not do when the user comes back to this screen with the
     * back gesture while the session keeps running — they would be thrown straight back into the
     * server screen and could never reach the rest of the app.
     */
    fun resumeExistingConnectionOnce(onConnected: () -> Unit): Boolean {
        if (sessionResumeHandled) return false
        sessionResumeHandled = true
        return resumeExistingConnection(onConnected)
    }

    /**
     * The service owns the live session, so while the user stays on this screen it is the only thing
     * that knows whether the session is still up. Keeps [connectionState] — and with it the "return
     * to session" affordance — honest.
     */
    fun observeServiceState(state: Int) {
        // A connect started from this screen owns the state until it settles: the service's client
        // still reports its previous state for a moment, and letting that through would flash the
        // "connected" affordance off in the middle of a connection attempt.
        if (_connectionState.value == ConnectionState.CONNECTING) return
        if (state != _connectionState.value) _connectionState.value = state
    }

    fun connectBookmark(bookmark: ServerBookmark, onConnected: () -> Unit) {
        address.value = bookmark.address
        nickname.value = bookmark.nickname
        password.value = bookmark.password ?: ""
        channel.value = bookmark.channel ?: ""
        viewModelScope.launch {
            try {
                bookmarkStore.saveLastBookmarkAddress(bookmark.address)
            } catch (_: Exception) {
                // Ignore address save failure; connection may still proceed.
            }
        }
        connect(onConnected)
    }

    fun tryAutoReconnect(onConnected: () -> Unit) {
        if (autoReconnectAttempted) return
        autoReconnectAttempted = true
        // Never stack a second connection on top of a live one: if the service is already running a
        // session, the only sensible move is to jump back into it, and only on a fresh start.
        if (TsConnectionService.instance?.hasActiveConnection() == true) {
            resumeExistingConnectionOnce(onConnected)
            return
        }
        viewModelScope.launch {
            val lastAddr = bookmarkStore.lastBookmarkAddress.first()
            if (lastAddr.isEmpty()) return@launch
            val list = bookmarkStore.bookmarks.first()
            val bookmark = list.find { it.address == lastAddr } ?: return@launch
            connectBookmark(bookmark, onConnected)
        }
    }

    fun setAutoReconnect(enabled: Boolean) {
        viewModelScope.launch { bookmarkStore.setAutoReconnect(enabled) }
    }

    fun saveBookmark() {
        val addr = address.value.trim()
        val nick = nickname.value.trim()
        if (addr.isEmpty()) return
        val bookmark = ServerBookmark(
            name = addr.substringBefore(":"),
            address = addr,
            nickname = nick,
            password = password.value.trim().takeIf { it.isNotEmpty() },
            channel = channel.value.trim().takeIf { it.isNotEmpty() },
        )
        viewModelScope.launch {
            val idx = _editingIndex.value
            if (idx >= 0) {
                bookmarkStore.replace(idx, bookmark)
            } else {
                bookmarkStore.add(bookmark)
            }
            clearFields()
        }
    }

    fun removeBookmark(index: Int) {
        viewModelScope.launch { bookmarkStore.remove(index) }
    }

    fun editBookmark(bookmark: ServerBookmark, index: Int) {
        address.value = bookmark.address
        nickname.value = bookmark.nickname
        password.value = bookmark.password ?: ""
        channel.value = bookmark.channel ?: ""
        _editingIndex.value = index
    }

    fun cancelEdit() {
        _editingIndex.value = -1
        clearFields()
    }

    private fun clearFields() {
        _editingIndex.value = -1
        address.value = ""
        nickname.value = ""
        password.value = ""
        channel.value = ""
    }

    fun browseChannels() {
        val input = getValidatedConnectionInput() ?: return
        val addr = input.address
        val nick = input.nickname

        isBrowsing.value = true
        _error.value = null

        viewModelScope.launch {
            try {
                val channels = withContext(Dispatchers.IO) {
                    // Probe with a throwaway identity: browsing must not sign
                    // in with the user's persistent identity — the server log
                    // records the quick joins, and an active session with the
                    // same identity risks a toomanyclones kick
                    val identity = Identity()
                    try {
                        val pw = password.value.trim().takeIf { it.isNotEmpty() }
                        var lastFailure: Throwable? = null

                        for (attempt in 0 until MAX_NICKNAME_COLLISION_ATTEMPTS) {
                            val candidateNick = nicknameWithCollisionSuffix(nick, attempt)
                            var client: Client? = null
                            try {
                                try {
                                    identity.setNickname(candidateNick)
                                } catch (e: Throwable) {
                                    if (e is CancellationException) throw e
                                    Log.w(TAG, "Failed to update identity nickname before browsing", e)
                                }
                                val c = Client(addr, identity, candidateNick, pw, null)
                                client = c
                                c.waitConnected()
                                // Pump events until channels are available (or timeout)
                                val deadline = System.currentTimeMillis() + 5000
                                while (System.currentTimeMillis() < deadline) {
                                    c.processEvents()
                                    val raw = c.channels
                                    if (raw != null && raw.isNotEmpty()) break
                                    Thread.sleep(20)
                                }

                                if (hasNicknameCollision(c.users, c.clientId, candidateNick)) {
                                    lastFailure = IllegalStateException("Nickname already in use: $candidateNick")
                                    disconnectAndClose(c)
                                    client = null
                                    continue
                                }

                                val ch = c.channels?.filterNotNull() ?: emptyList()
                                disconnectAndClose(c)
                                client = null
                                return@withContext ch
                            } catch (e: Throwable) {
                                if (e is CancellationException) throw e
                                lastFailure = e
                            } finally {
                                client?.let { closeQuietly(it) }
                            }
                        }

                        throw Exception(
                            "Browse failed after trying unique nicknames",
                            lastFailure,
                        )
                    } finally {
                        identity.close()
                    }
                }
                browsedChannels.value = channels
                showChannelPicker.value = true
            } catch (e: Exception) {
                _error.value = e.message ?: getApplication<Application>().getString(R.string.browse_failed)
            } finally {
                isBrowsing.value = false
            }
        }
    }

    fun selectChannel(channelId: Long) {
        val channels = browsedChannels.value
        val tree = ChannelTree.fromChannels(channels.toTypedArray())
        val path = tree.pathTo(channelId)
        tree.close()
        channel.value = path.joinToString("/") { it.name }
        showChannelPicker.value = false
    }

    fun clearError() {
        _error.value = null
    }

    private fun disconnectAndClose(client: Client) {
        try {
            client.disconnect()
            val flushEnd = System.currentTimeMillis() + 500
            while (System.currentTimeMillis() < flushEnd) {
                client.processEvents()
                Thread.sleep(20)
            }
        } catch (_: Throwable) {
        } finally {
            closeQuietly(client)
        }
    }

    private fun closeQuietly(client: Client) {
        try {
            client.close()
        } catch (_: Throwable) {
        }
    }

    /**
     * The saved identity, or a fresh one on first use.
     *
     * On [Dispatchers.IO]: this reads — or generates and writes — the key file, and it is on the way
     * into a connection, including the automatic one a start with auto-reconnect does.
     */
    private suspend fun getOrCreateIdentity(): Identity = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        val identityFile = File(context.filesDir, "identity.ini")
        if (identityFile.exists()) {
            Identity.load(identityFile.absolutePath)
        } else {
            val identity = Identity()
            identity.save(identityFile.absolutePath)
            identity
        }
    }

    private fun getCloneBypassIdentity(): Identity {
        return cloneBypassIdentity ?: Identity().also {
            cloneBypassIdentity = it
        }
    }

    private fun Throwable.isTooManyClonesFailure(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            val message = current.message?.lowercase().orEmpty()
            if ("toomanyclones" in message || "too many clones" in message) {
                return true
            }
            current = current.cause
        }
        return false
    }
}
