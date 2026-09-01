package com.mooncc.teamspeak9.ui.screen.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mooncc.teamspeak9.data.local.SettingsStore
import com.mooncc.teamspeak9.domain.model.Bookmark
import com.mooncc.teamspeak9.domain.repository.BookmarkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BookmarkEditorState(
    val isOpen: Boolean = false,
    val bookmark: Bookmark = blankBookmark(),
    val isNew: Boolean = true,
) {
    companion object {
        fun blankBookmark(defaultNickname: String = "AndroidUser") = Bookmark(
            label = "",
            host = "",
            nickname = defaultNickname,
        )
    }
}

private fun blankBookmark(): Bookmark = BookmarkEditorState.blankBookmark()

@HiltViewModel
class BookmarksViewModel @Inject constructor(
    private val bookmarkRepository: BookmarkRepository,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    val bookmarks: StateFlow<List<Bookmark>> = bookmarkRepository.observeBookmarks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _editor = MutableStateFlow(BookmarkEditorState())
    val editor: StateFlow<BookmarkEditorState> = _editor.asStateFlow()

    fun startCreate() {
        viewModelScope.launch {
            val nickname = settingsStore.settings.first().defaultNickname
            _editor.value = BookmarkEditorState(
                isOpen = true,
                bookmark = BookmarkEditorState.blankBookmark(nickname),
                isNew = true,
            )
        }
    }

    fun startEdit(bookmark: Bookmark) {
        _editor.value = BookmarkEditorState(isOpen = true, bookmark = bookmark, isNew = false)
    }

    fun updateDraft(transform: (Bookmark) -> Bookmark) {
        _editor.value = _editor.value.copy(bookmark = transform(_editor.value.bookmark))
    }

    fun dismissEditor() {
        _editor.value = BookmarkEditorState()
    }

    /** Persists the draft and reports the resulting id, so the caller can connect. */
    fun save(onSaved: (Long) -> Unit = {}) {
        val draft = _editor.value.bookmark
        if (draft.label.isBlank() || draft.host.isBlank()) return
        viewModelScope.launch {
            val id = bookmarkRepository.saveBookmark(draft)
            _editor.value = BookmarkEditorState()
            onSaved(if (draft.id == 0L) id else draft.id)
        }
    }

    fun delete(bookmark: Bookmark) {
        viewModelScope.launch { bookmarkRepository.deleteBookmark(bookmark.id) }
    }
}
