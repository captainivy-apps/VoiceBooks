package com.dafei.voicebook.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dafei.voicebook.R
import com.dafei.voicebook.VoicebookApp
import com.dafei.voicebook.data.db.BookWithProgress
import com.dafei.voicebook.data.model.ImportResult
import com.dafei.voicebook.data.model.ImportStatus
import com.dafei.voicebook.data.model.SortBy
import com.dafei.voicebook.data.model.SortOrder
import com.dafei.voicebook.domain.import.EbookUrlHelper
import com.dafei.voicebook.ui.AppToast
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryUiState(
    val books: List<BookWithProgress> = emptyList(),
    val sortBy: SortBy = SortBy.LAST_PLAYED,
    val sortOrder: SortOrder = SortOrder.DESC,
    val selectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as VoicebookApp
    private val bookRepository = app.bookRepository
    private val settingsRepository = app.settingsRepository

    private val _selectionMode = MutableStateFlow(false)
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    private val _sortBy = MutableStateFlow(SortBy.LAST_PLAYED)
    private val _sortOrder = MutableStateFlow(SortOrder.DESC)

    val uiState: StateFlow<LibraryUiState> = combine(
        _sortBy,
        _sortOrder,
        _selectionMode,
        _selectedIds,
    ) { sortBy, sortOrder, selectionMode, selectedIds ->
        SortState(sortBy, sortOrder, selectionMode, selectedIds)
    }.flatMapLatest { sortState ->
        bookRepository.observeBooks(sortState.sortBy, sortState.sortOrder).map { books ->
            LibraryUiState(
                books = books,
                sortBy = sortState.sortBy,
                sortOrder = sortState.sortOrder,
                selectionMode = sortState.selectionMode,
                selectedIds = sortState.selectedIds,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUiState())

    private data class SortState(
        val sortBy: SortBy,
        val sortOrder: SortOrder,
        val selectionMode: Boolean,
        val selectedIds: Set<String>,
    )

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _sortBy.value = settings.sortBy
                _sortOrder.value = settings.sortOrder
            }
        }
    }

    fun importBook(uri: Uri, fileName: String) {
        importBooks(listOf(uri to fileName))
    }

    fun importBooks(items: List<Pair<Uri, String>>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val batchMd5s = mutableSetOf<String>()
            var started = 0
            var duplicates = 0
            var failed = 0
            for ((uri, fileName) in items) {
                when (val result = bookRepository.importFromUri(uri, fileName, batchMd5s)) {
                    is ImportResult.Started -> started++
                    is ImportResult.Duplicate -> {
                        duplicates++
                        AppToast.show(
                            getApplication(),
                            getApplication<Application>().getString(R.string.import_duplicate_skipped, result.existingTitle),
                        )
                    }
                    is ImportResult.Failed -> failed++
                }
            }
            val context = getApplication<Application>()
            if (items.size > 1) {
                AppToast.show(
                    context,
                    context.getString(R.string.import_batch_summary, started, duplicates, failed),
                )
            }
        }
    }

    fun importBookFromUrl(url: String) {
        val trimmedUrl = url.trim()
        if (!EbookUrlHelper.isValidHttpUrl(trimmedUrl)) {
            AppToast.show(getApplication(), getApplication<Application>().getString(R.string.import_error_invalid_url))
            return
        }
        if (EbookUrlHelper.extensionFromUrl(trimmedUrl) != EbookUrlHelper.SUPPORTED_EXTENSION) {
            AppToast.show(getApplication(), getApplication<Application>().getString(R.string.import_error_epub_only))
            return
        }
        viewModelScope.launch {
            when (val result = bookRepository.importFromUrl(trimmedUrl)) {
                is ImportResult.Duplicate -> AppToast.show(
                    getApplication(),
                    getApplication<Application>().getString(R.string.import_duplicate_skipped, result.existingTitle),
                )
                is ImportResult.Failed -> AppToast.show(getApplication(), result.message)
                is ImportResult.Started -> Unit
            }
        }
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            bookRepository.deleteBook(bookId)
        }
    }

    fun retryImport(bookId: String) {
        viewModelScope.launch {
            bookRepository.retryImport(bookId)
        }
    }

    fun toggleSelectionMode() {
        _selectionMode.value = !_selectionMode.value
        if (!_selectionMode.value) _selectedIds.value = emptySet()
    }

    fun toggleSelection(bookId: String) {
        _selectedIds.value = if (_selectedIds.value.contains(bookId)) {
            _selectedIds.value - bookId
        } else {
            _selectedIds.value + bookId
        }
    }

    fun selectAll(books: List<BookWithProgress>) {
        _selectedIds.value = books.map { it.id }.toSet()
    }

    fun deleteSelected() {
        viewModelScope.launch {
            bookRepository.deleteBooks(_selectedIds.value.toList())
            _selectedIds.value = emptySet()
            _selectionMode.value = false
        }
    }

    fun setSort(sortBy: SortBy, sortOrder: SortOrder) {
        viewModelScope.launch {
            _sortBy.value = sortBy
            _sortOrder.value = sortOrder
            settingsRepository.setSort(sortBy, sortOrder)
        }
    }
}
