package com.dafei.voicebook.ui.library

import android.Manifest
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.dafei.voicebook.R
import com.dafei.voicebook.data.db.BookWithProgress
import com.dafei.voicebook.data.model.ImportStatus
import com.dafei.voicebook.data.model.SortBy
import com.dafei.voicebook.data.model.SortOrder
import com.dafei.voicebook.domain.import.EbookUrlHelper
import com.dafei.voicebook.util.ProgressFormat
import com.dafei.voicebook.ui.layout.CompactTopBar
import com.dafei.voicebook.ui.layout.isLandscape
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BookDeleteActionWidth = 72.dp

private enum class BookSwipeState { Closed, Open }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenBook: (String) -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showSortMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showImportSheet by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var urlInput by remember { mutableStateOf("") }
    var swipedBookId by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val importSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            swipedBookId = null
        }
    }

    val dismissSwipe = { swipedBookId = null }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val items = uris.mapNotNull { uri ->
            val fileName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            } ?: "book.epub"
            if (!fileName.lowercase().endsWith(".epub")) {
                return@mapNotNull null
            }
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {}
            uri to fileName
        }
        if (items.isEmpty()) {
            com.dafei.voicebook.ui.AppToast.show(
                context,
                context.getString(R.string.import_error_epub_only),
            )
            return@rememberLauncherForActivityResult
        }
        viewModel.importBooks(items)
    }

    val launchFilePicker = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        filePicker.launch(arrayOf("application/epub+zip"))
    }

    Scaffold(
        topBar = {
            if (isLandscape()) {
                CompactTopBar(
                    title = stringResource(R.string.library_title),
                    actions = {
                        LibraryTopBarActions(
                            uiState = uiState,
                            showSortMenu = showSortMenu,
                            onShowSortMenu = { showSortMenu = it },
                            onDismissSwipe = dismissSwipe,
                            onSelectAll = { viewModel.selectAll(uiState.books) },
                            onShowDeleteDialog = { showDeleteDialog = true },
                            onToggleSelectionMode = viewModel::toggleSelectionMode,
                            onSetSort = viewModel::setSort,
                        )
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.library_title)) },
                    actions = {
                        LibraryTopBarActions(
                            uiState = uiState,
                            showSortMenu = showSortMenu,
                            onShowSortMenu = { showSortMenu = it },
                            onDismissSwipe = dismissSwipe,
                            onSelectAll = { viewModel.selectAll(uiState.books) },
                            onShowDeleteDialog = { showDeleteDialog = true },
                            onToggleSelectionMode = viewModel::toggleSelectionMode,
                            onSetSort = viewModel::setSort,
                        )
                    },
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    dismissSwipe()
                    showImportSheet = true
                },
            ) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.upload_book))
            }
        },
    ) { padding ->
        if (uiState.books.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.no_books), style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                items(uiState.books, key = { it.id }) { book ->
                    BookListItem(
                        book = book,
                        selectionMode = uiState.selectionMode,
                        selected = uiState.selectedIds.contains(book.id),
                        swipeRevealed = swipedBookId == book.id,
                        onSwipeRevealedChange = { revealed ->
                            swipedBookId = if (revealed) book.id else null
                        },
                        onDismissSwipe = dismissSwipe,
                        onToggleSelect = { viewModel.toggleSelection(book.id) },
                        onClick = {
                            if (swipedBookId != null) {
                                dismissSwipe()
                                return@BookListItem
                            }
                            if (uiState.selectionMode) {
                                viewModel.toggleSelection(book.id)
                            } else if (book.importStatus == ImportStatus.COMPLETED.name) {
                                onOpenBook(book.id)
                            }
                        },
                        onDelete = { viewModel.deleteBook(book.id) },
                        onRetry = { viewModel.retryImport(book.id) },
                    )
                }
                item {
                    Spacer(
                        modifier = Modifier
                            .height(80.dp)
                            .fillMaxWidth()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) { dismissSwipe() },
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.confirm_delete)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSelected()
                    showDeleteDialog = false
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showImportSheet) {
        ModalBottomSheet(
            onDismissRequest = { showImportSheet = false },
            sheetState = importSheetState,
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.import_from_local)) },
                    onClick = {
                        showImportSheet = false
                        launchFilePicker()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.import_from_url)) },
                    onClick = {
                        showImportSheet = false
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                        urlInput = if (clipText != null && EbookUrlHelper.isValidHttpUrl(clipText)) {
                            clipText
                        } else {
                            ""
                        }
                        showUrlDialog = true
                    },
                )
            }
        }
    }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text(stringResource(R.string.import_url_title)) },
            text = {
                OutlinedTextField(
                    value = urlInput,
                    onValueChange = { urlInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.import_url_hint)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUrlDialog = false
                        viewModel.importBookFromUrl(urlInput)
                        urlInput = ""
                    },
                ) { Text(stringResource(R.string.import_url_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun BookListItem(
    book: BookWithProgress,
    selectionMode: Boolean,
    selected: Boolean,
    swipeRevealed: Boolean,
    onSwipeRevealedChange: (Boolean) -> Unit,
    onDismissSwipe: () -> Unit,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
) {
    if (selectionMode) {
        BookListItemContent(
            book = book,
            selectionMode = true,
            selected = selected,
            onToggleSelect = onToggleSelect,
            onClick = onClick,
            onRetry = onRetry,
        )
        return
    }

    val density = LocalDensity.current
    val actionWidthPx = with(density) { BookDeleteActionWidth.toPx() }
    val swipeState = remember(density) {
        AnchoredDraggableState(
            initialValue = BookSwipeState.Closed,
            positionalThreshold = { distance -> distance * 0.5f },
            velocityThreshold = { with(density) { 80.dp.toPx() } },
            animationSpec = tween(durationMillis = 200),
        )
    }

    LaunchedEffect(swipeRevealed) {
        val target = if (swipeRevealed) BookSwipeState.Open else BookSwipeState.Closed
        if (swipeState.currentValue != target) {
            swipeState.animateTo(target)
        }
    }

    LaunchedEffect(swipeState.currentValue) {
        when (swipeState.currentValue) {
            BookSwipeState.Open -> if (!swipeRevealed) onSwipeRevealedChange(true)
            BookSwipeState.Closed -> if (swipeRevealed) onSwipeRevealedChange(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .onSizeChanged { size ->
                val maxOffset = min(actionWidthPx, size.width.toFloat())
                swipeState.updateAnchors(
                    DraggableAnchors {
                        BookSwipeState.Closed at 0f
                        BookSwipeState.Open at -maxOffset
                    },
                )
            },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(BookDeleteActionWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.error),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(
                onClick = {
                    onDelete()
                    onDismissSwipe()
                },
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.onError,
                )
            }
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(swipeState.requireOffset().roundToInt(), 0) }
                .fillMaxWidth()
                .anchoredDraggable(
                    state = swipeState,
                    orientation = Orientation.Horizontal,
                )
                .background(MaterialTheme.colorScheme.surface),
        ) {
            BookListItemContent(
                book = book,
                selectionMode = false,
                selected = false,
                onToggleSelect = onToggleSelect,
                onClick = {
                    if (swipeRevealed) {
                        onDismissSwipe()
                    } else {
                        onClick()
                    }
                },
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun BookListItemContent(
    book: BookWithProgress,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelect: () -> Unit,
    onClick: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Checkbox(checked = selected, onCheckedChange = { onToggleSelect() })
            Spacer(modifier = Modifier.width(8.dp))
        }

        val coverModel = book.coverPath?.let { File(it) }
        AsyncImage(
            model = coverModel ?: R.drawable.default_cover,
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop,
            placeholder = painterResource(R.drawable.default_cover),
            error = painterResource(R.drawable.default_cover),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                book.author.ifBlank { stringResource(R.string.empty_author) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (book.importStatus) {
                ImportStatus.PENDING.name, ImportStatus.PROCESSING.name -> {
                    val importProgress = book.importProgress.coerceIn(0, 100)
                    LinearProgressIndicator(
                        progress = { importProgress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                    Text(
                        text = when {
                            book.importStatus == ImportStatus.PENDING.name && importProgress == 0 ->
                                if (!book.sourceUrl.isNullOrBlank()) {
                                    stringResource(R.string.import_stage_downloading)
                                } else {
                                    stringResource(R.string.import_pending)
                                }
                            else -> stringResource(
                                R.string.import_progress_stage,
                                importStageLabel(importProgress, book.sourceUrl),
                                importProgress,
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                ImportStatus.FAILED.name -> {
                    Text(
                        book.importError ?: stringResource(R.string.import_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                else -> {
                    val progress = book.currentSentenceIndex ?: 0
                    val total = book.sentenceCount.coerceAtLeast(1)
                    LinearProgressIndicator(
                        progress = { progress.toFloat() / total.toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                    Text(
                        "$progress / $total ${ProgressFormat.percentSuffix(progress, total)}",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (book.importStatus == ImportStatus.COMPLETED.name) {
                Text(
                    text = book.lastPlayedTime?.let { lastPlayed ->
                        stringResource(R.string.last_played_time, formatDate(lastPlayed))
                    } ?: stringResource(R.string.never_played),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (book.importStatus == ImportStatus.FAILED.name) {
            IconButton(onClick = onRetry) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.retry_import))
            }
        }
    }
}

private fun formatDate(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}

@Composable
private fun importStageLabel(progress: Int, sourceUrl: String?): String {
    val isUrlImport = !sourceUrl.isNullOrBlank()
    return when {
        isUrlImport && progress < 5 -> stringResource(R.string.import_stage_downloading)
        progress < 50 -> stringResource(R.string.import_stage_parsing)
        progress < 75 -> stringResource(R.string.import_stage_splitting)
        else -> stringResource(R.string.import_stage_indexing)
    }
}

@Composable
private fun LibraryTopBarActions(
    uiState: LibraryUiState,
    showSortMenu: Boolean,
    onShowSortMenu: (Boolean) -> Unit,
    onDismissSwipe: () -> Unit,
    onSelectAll: () -> Unit,
    onShowDeleteDialog: () -> Unit,
    onToggleSelectionMode: () -> Unit,
    onSetSort: (SortBy, SortOrder) -> Unit,
) {
    if (uiState.selectionMode) {
        TextButton(onClick = onSelectAll) {
            Text(stringResource(R.string.select_all))
        }
        IconButton(onClick = onShowDeleteDialog) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.batch_delete))
        }
        TextButton(onClick = onToggleSelectionMode) {
            Text(stringResource(R.string.cancel))
        }
    } else {
        IconButton(onClick = { onDismissSwipe(); onShowSortMenu(true) }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null)
        }
        IconButton(onClick = { onDismissSwipe(); onToggleSelectionMode() }) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.batch_delete))
        }
        DropdownMenu(
            expanded = showSortMenu,
            onDismissRequest = { onShowSortMenu(false); onDismissSwipe() },
        ) {
            DropdownMenuItem(
                text = { Text("${stringResource(R.string.sort_by_upload)} · ${stringResource(R.string.sort_desc)}") },
                onClick = {
                    onSetSort(SortBy.UPLOAD_TIME, SortOrder.DESC)
                    onShowSortMenu(false)
                },
            )
            DropdownMenuItem(
                text = { Text("${stringResource(R.string.sort_by_upload)} · ${stringResource(R.string.sort_asc)}") },
                onClick = {
                    onSetSort(SortBy.UPLOAD_TIME, SortOrder.ASC)
                    onShowSortMenu(false)
                },
            )
            DropdownMenuItem(
                text = { Text("${stringResource(R.string.sort_by_last_played)} · ${stringResource(R.string.sort_desc)}") },
                onClick = {
                    onSetSort(SortBy.LAST_PLAYED, SortOrder.DESC)
                    onShowSortMenu(false)
                },
            )
            DropdownMenuItem(
                text = { Text("${stringResource(R.string.sort_by_last_played)} · ${stringResource(R.string.sort_asc)}") },
                onClick = {
                    onSetSort(SortBy.LAST_PLAYED, SortOrder.ASC)
                    onShowSortMenu(false)
                },
            )
        }
    }
}
