package com.dafei.voicebook.ui.player

import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.dafei.voicebook.util.ProgressFormat
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.dafei.voicebook.MainActivity
import com.dafei.voicebook.R
import com.dafei.voicebook.data.db.SentenceIndexEntity
import com.dafei.voicebook.domain.playback.PlaybackState
import com.dafei.voicebook.domain.playback.TtsLoadingStage
import com.dafei.voicebook.ui.AppToast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException

private val LandscapeHeaderHeight = 32.dp

private enum class PlayerControlsLayout {
    Portrait,
    Landscape,
}

private data class LandscapeLayoutMetrics(
    val contentHeight: Dp,
    val controlPanelWidth: Dp,
    val horizontalPadding: Dp,
    val playButtonSize: Dp,
    val secondaryButtonSize: Dp,
    val playIconSize: Dp,
    val secondaryIconSize: Dp,
    val sectionSpacing: Dp,
)

@Composable
private fun rememberLandscapeLayoutMetrics(
    maxWidth: Dp,
    maxHeight: Dp,
): LandscapeLayoutMetrics {
    return remember(maxWidth, maxHeight) {
        val sizing = when {
            maxHeight < 260.dp -> LandscapeSizing(
                playButtonSize = 48.dp,
                secondaryButtonSize = 40.dp,
                playIconSize = 36.dp,
                secondaryIconSize = 28.dp,
                sectionSpacing = 4.dp,
                horizontalPadding = 8.dp,
            )
            maxHeight < 320.dp -> LandscapeSizing(
                playButtonSize = 56.dp,
                secondaryButtonSize = 44.dp,
                playIconSize = 42.dp,
                secondaryIconSize = 32.dp,
                sectionSpacing = 6.dp,
                horizontalPadding = 10.dp,
            )
            else -> LandscapeSizing(
                playButtonSize = 64.dp,
                secondaryButtonSize = 48.dp,
                playIconSize = 48.dp,
                secondaryIconSize = 36.dp,
                sectionSpacing = 8.dp,
                horizontalPadding = 12.dp,
            )
        }
        LandscapeLayoutMetrics(
            contentHeight = maxHeight,
            controlPanelWidth = maxWidth / 3f,
            horizontalPadding = sizing.horizontalPadding,
            playButtonSize = sizing.playButtonSize,
            secondaryButtonSize = sizing.secondaryButtonSize,
            playIconSize = sizing.playIconSize,
            secondaryIconSize = sizing.secondaryIconSize,
            sectionSpacing = sizing.sectionSpacing,
        )
    }
}

private data class LandscapeSizing(
    val playButtonSize: Dp,
    val secondaryButtonSize: Dp,
    val playIconSize: Dp,
    val secondaryIconSize: Dp,
    val sectionSpacing: Dp,
    val horizontalPadding: Dp,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(
    bookId: String,
    onBack: () -> Unit,
) {
    val viewModel: PlayerViewModel = viewModel(
        factory = PlayerViewModelFactory(
            LocalContext.current.applicationContext as android.app.Application,
            bookId,
        ),
    )
    val uiState by viewModel.uiState.collectAsState()
    var showSettingsSheet by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(pageCount = { 3 })
    val context = LocalContext.current
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var sliderProgress by remember { mutableFloatStateOf(0f) }
    var isSeekDragging by remember { mutableStateOf(false) }
    val isSeekPending = uiState.pendingSeekIndex != null

    val leavePlayer = {
        viewModel.stopPlayback()
        onBack()
    }

    BackHandler(onBack = leavePlayer)

    SideEffect {
        val activity = context as? MainActivity
        if (uiState.keepScreenOn) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val displayIndex = when {
        isSeekDragging -> {
            (sliderProgress * uiState.totalSentences).toInt()
                .coerceIn(0, (uiState.totalSentences - 1).coerceAtLeast(0))
        }
        isSeekPending -> uiState.pendingSeekIndex
            ?: uiState.currentSentenceIndex
        else -> uiState.currentSentenceIndex
    }

    val progress = if (uiState.totalSentences > 0) {
        uiState.currentSentenceIndex.toFloat() / uiState.totalSentences.toFloat()
    } else {
        0f
    }

    LaunchedEffect(progress, isSeekDragging, isSeekPending, uiState.pendingSeekIndex, uiState.currentSentenceIndex) {
        if (!isSeekDragging && !isSeekPending) {
            sliderProgress = progress
        } else if (isSeekPending && !isSeekDragging) {
            val targetIndex = uiState.pendingSeekIndex
            if (targetIndex != null && uiState.currentSentenceIndex == targetIndex) {
                sliderProgress = progress
            } else if (targetIndex != null && uiState.totalSentences > 0) {
                sliderProgress = targetIndex.toFloat() / uiState.totalSentences.toFloat()
            }
        }
    }

    val onSliderChange: (Float) -> Unit = { value ->
        isSeekDragging = true
        viewModel.cancelPendingSeek()
        sliderProgress = value
    }

    val onSliderFinished: () -> Unit = {
        isSeekDragging = false
        val index = (sliderProgress * uiState.totalSentences).toInt()
            .coerceIn(0, (uiState.totalSentences - 1).coerceAtLeast(0))
        if (index != uiState.currentSentenceIndex || uiState.pendingSeekIndex != null) {
            viewModel.scheduleSeekToSentence(index)
        }
    }

    LaunchedEffect(isLandscape) {
        if (isLandscape && pagerState.currentPage != 1) {
            pagerState.animateScrollToPage(1)
        }
    }

    Scaffold(
        contentWindowInsets = if (isLandscape) {
            WindowInsets(0.dp)
        } else {
            ScaffoldDefaults.contentWindowInsets
        },
        topBar = {
            if (!isLandscape) {
                PlayerTopAppBar(
                    bookTitle = uiState.bookTitle,
                    bookAuthor = uiState.bookAuthor,
                    onBack = leavePlayer,
                    onSettings = { showSettingsSheet = true },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (isLandscape) {
                LandscapePlayerContent(
                    bookTitle = uiState.bookTitle,
                    pagerState = pagerState,
                    coverPath = uiState.coverPath,
                    bookInfo = uiState.bookInfo,
                    sentences = uiState.sentences,
                    displayIndex = displayIndex,
                    currentSentenceText = uiState.sentences.getOrNull(displayIndex)?.textPreview
                        ?: uiState.currentSentenceText,
                    sliderProgress = sliderProgress,
                    totalSentences = uiState.totalSentences,
                    isPlaying = uiState.isPlayingForUi,
                    onBack = leavePlayer,
                    onSettings = { showSettingsSheet = true },
                    onSliderChange = onSliderChange,
                    onSliderFinished = onSliderFinished,
                    onRewind = viewModel::rewind,
                    onTogglePlayPause = viewModel::togglePlayPause,
                    onForward = viewModel::forward,
                    canRewind = uiState.canRewind,
                    canForward = uiState.canForward,
                    isRefreshingMetadata = uiState.isRefreshingMetadata,
                    onRefreshMetadata = viewModel::refreshBookMetadata,
                )
            } else {
                PortraitPlayerContent(
                    pagerState = pagerState,
                    coverPath = uiState.coverPath,
                    bookInfo = uiState.bookInfo,
                    sentences = uiState.sentences,
                    displayIndex = displayIndex,
                    currentSentenceText = uiState.sentences.getOrNull(displayIndex)?.textPreview
                        ?: uiState.currentSentenceText,
                    sliderProgress = sliderProgress,
                    totalSentences = uiState.totalSentences,
                    isPlaying = uiState.isPlayingForUi,
                    onSliderChange = onSliderChange,
                    onSliderFinished = onSliderFinished,
                    onRewind = viewModel::rewind,
                    onTogglePlayPause = viewModel::togglePlayPause,
                    onForward = viewModel::forward,
                    canRewind = uiState.canRewind,
                    canForward = uiState.canForward,
                    isRefreshingMetadata = uiState.isRefreshingMetadata,
                    onRefreshMetadata = viewModel::refreshBookMetadata,
                )
            }

            if (uiState.loadingStage != TtsLoadingStage.NONE) {
                PlaybackLoadingOverlay(loadingStage = uiState.loadingStage)
            }

            if (uiState.playbackState == PlaybackState.ERROR) {
                PlaybackErrorOverlay(
                    message = uiState.lastError ?: stringResource(R.string.tts_init_failed),
                    onRetry = viewModel::startPlayback,
                )
            }
        }
    }

    if (showSettingsSheet) {
        PlaybackSettingsSheet(
            isLandscape = isLandscape,
            currentSpeed = uiState.speed,
            sleepTimerRemainingMs = uiState.sleepTimerRemainingMs,
            sleepUntilEndOfBook = uiState.sleepUntilEndOfBook,
            sleepTimerMinutes = uiState.sleepTimerMinutes,
            onDismiss = { showSettingsSheet = false },
            onSpeedCommit = viewModel::scheduleSpeedApply,
            onSleepTimer = viewModel::setSleepTimer,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerTopAppBar(
    bookTitle: String,
    bookAuthor: String,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(bookTitle, maxLines = 1)
                Text(
                    bookAuthor,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
        },
        actions = {
            IconButton(onClick = onSettings) {
                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.playback_settings))
            }
        },
    )
}

@Composable
private fun PlayerLandscapeHeader(
    bookTitle: String,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(LandscapeHeaderHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier.size(LandscapeHeaderHeight),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
        }
        Text(
            text = bookTitle,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
        )
        IconButton(
            onClick = onSettings,
            modifier = Modifier.size(LandscapeHeaderHeight),
        ) {
            Icon(
                Icons.Default.Settings,
                contentDescription = stringResource(R.string.playback_settings),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PortraitPlayerContent(
    pagerState: PagerState,
    coverPath: String?,
    bookInfo: BookDisplayInfo,
    sentences: List<SentenceIndexEntity>,
    displayIndex: Int,
    currentSentenceText: String,
    sliderProgress: Float,
    totalSentences: Int,
    isPlaying: Boolean,
    onSliderChange: (Float) -> Unit,
    onSliderFinished: () -> Unit,
    onRewind: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onForward: () -> Unit,
    canRewind: Boolean,
    canForward: Boolean,
    isRefreshingMetadata: Boolean,
    onRefreshMetadata: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PlayerPagerSection(
            pagerState = pagerState,
            coverPath = coverPath,
            bookInfo = bookInfo,
            sentences = sentences,
            displayIndex = displayIndex,
            currentSentenceText = currentSentenceText,
            totalSentences = totalSentences,
            isLandscape = false,
            isRefreshingMetadata = isRefreshingMetadata,
            onRefreshMetadata = onRefreshMetadata,
            modifier = Modifier.weight(1f),
        )

        PlayerControlsSection(
            sliderProgress = sliderProgress,
            displayIndex = displayIndex,
            totalSentences = totalSentences,
            isPlaying = isPlaying,
            layout = PlayerControlsLayout.Portrait,
            onSliderChange = onSliderChange,
            onSliderFinished = onSliderFinished,
            onRewind = onRewind,
            onTogglePlayPause = onTogglePlayPause,
            onForward = onForward,
            canRewind = canRewind,
            canForward = canForward,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LandscapePlayerContent(
    bookTitle: String,
    pagerState: PagerState,
    coverPath: String?,
    bookInfo: BookDisplayInfo,
    sentences: List<SentenceIndexEntity>,
    displayIndex: Int,
    currentSentenceText: String,
    sliderProgress: Float,
    totalSentences: Int,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onSliderChange: (Float) -> Unit,
    onSliderFinished: () -> Unit,
    onRewind: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onForward: () -> Unit,
    canRewind: Boolean,
    canForward: Boolean,
    isRefreshingMetadata: Boolean,
    onRefreshMetadata: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val metrics = rememberLandscapeLayoutMetrics(maxWidth = maxWidth, maxHeight = maxHeight)

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = metrics.horizontalPadding),
        ) {
            Box(
                modifier = Modifier
                    .weight(2f)
                    .fillMaxHeight()
                    .padding(end = 4.dp),
            ) {
                PlayerPagerSection(
                    pagerState = pagerState,
                    coverPath = coverPath,
                    bookInfo = bookInfo,
                    sentences = sentences,
                    displayIndex = displayIndex,
                    currentSentenceText = currentSentenceText,
                    totalSentences = totalSentences,
                    isLandscape = true,
                    isRefreshingMetadata = isRefreshingMetadata,
                    onRefreshMetadata = onRefreshMetadata,
                    modifier = Modifier.fillMaxSize(),
                )
                PlayerLandscapeHeader(
                    bookTitle = bookTitle,
                    onBack = onBack,
                    onSettings = onSettings,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
                )
            }

            VerticalDivider(
                modifier = Modifier.fillMaxHeight(),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            PlayerControlsSection(
                sliderProgress = sliderProgress,
                displayIndex = displayIndex,
                totalSentences = totalSentences,
                isPlaying = isPlaying,
                layout = PlayerControlsLayout.Landscape,
                landscapeMetrics = metrics,
                onSliderChange = onSliderChange,
                onSliderFinished = onSliderFinished,
                onRewind = onRewind,
                onTogglePlayPause = onTogglePlayPause,
                onForward = onForward,
                canRewind = canRewind,
                canForward = canForward,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .statusBarsPadding()
                    .padding(start = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerPagerSection(
    pagerState: PagerState,
    coverPath: String?,
    bookInfo: BookDisplayInfo,
    sentences: List<SentenceIndexEntity>,
    displayIndex: Int,
    currentSentenceText: String,
    totalSentences: Int,
    isLandscape: Boolean,
    isRefreshingMetadata: Boolean,
    onRefreshMetadata: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isLandscape) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight(),
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                when (page) {
                    0 -> CoverPage(coverPath = coverPath, isLandscape = true)
                    1 -> LandscapeSubtitlePage(
                        sentences = sentences,
                        currentIndex = displayIndex,
                        currentText = currentSentenceText,
                    )
                    2 -> BookInfoPage(
                        bookInfo = bookInfo,
                        displayIndex = displayIndex,
                        totalSentences = totalSentences,
                        isLandscape = true,
                        isRefreshingMetadata = isRefreshingMetadata,
                        onRefreshMetadata = onRefreshMetadata,
                    )
                }
            }
            PagerDotIndicator(
                pageCount = pagerState.pageCount,
                currentPage = pagerState.currentPage,
                compact = true,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 4.dp),
            )
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) { page ->
                when (page) {
                    0 -> CoverPage(coverPath = coverPath, isLandscape = false)
                    1 -> SubtitlePage(
                        sentences = sentences,
                        currentIndex = displayIndex,
                        currentText = currentSentenceText,
                        isLandscape = false,
                    )
                    2 -> BookInfoPage(
                        bookInfo = bookInfo,
                        displayIndex = displayIndex,
                        totalSentences = totalSentences,
                        isLandscape = false,
                        isRefreshingMetadata = isRefreshingMetadata,
                        onRefreshMetadata = onRefreshMetadata,
                    )
                }
            }

            PagerDotIndicator(
                pageCount = pagerState.pageCount,
                currentPage = pagerState.currentPage,
                compact = false,
            )
        }
    }
}

@Composable
private fun PlayerControlsSection(
    sliderProgress: Float,
    displayIndex: Int,
    totalSentences: Int,
    isPlaying: Boolean,
    layout: PlayerControlsLayout,
    onSliderChange: (Float) -> Unit,
    onSliderFinished: () -> Unit,
    onRewind: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onForward: () -> Unit,
    canRewind: Boolean,
    canForward: Boolean,
    modifier: Modifier = Modifier,
    landscapeMetrics: LandscapeLayoutMetrics? = null,
) {
    val sectionSpacing = when (layout) {
        PlayerControlsLayout.Portrait -> 8.dp
        PlayerControlsLayout.Landscape -> landscapeMetrics?.sectionSpacing ?: 8.dp
    }

    val controlsContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (layout == PlayerControlsLayout.Portrait) {
                Spacer(modifier = Modifier.height(12.dp))
            }

            Slider(
                value = sliderProgress,
                onValueChange = onSliderChange,
                onValueChangeFinished = onSliderFinished,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "${displayIndex + 1} / $totalSentences ${ProgressFormat.percentSuffix(sliderProgress)}",
                style = MaterialTheme.typography.labelSmall,
            )

            Spacer(modifier = Modifier.height(sectionSpacing))

            if (layout == PlayerControlsLayout.Landscape && landscapeMetrics != null) {
                TransportControls(
                    isPlaying = isPlaying,
                    onRewind = onRewind,
                    onTogglePlayPause = onTogglePlayPause,
                    onForward = onForward,
                    canRewind = canRewind,
                    canForward = canForward,
                    playButtonSize = landscapeMetrics.playButtonSize,
                    secondaryButtonSize = landscapeMetrics.secondaryButtonSize,
                    playIconSize = landscapeMetrics.playIconSize,
                    secondaryIconSize = landscapeMetrics.secondaryIconSize,
                )
            } else {
                TransportControls(
                    isPlaying = isPlaying,
                    onRewind = onRewind,
                    onTogglePlayPause = onTogglePlayPause,
                    onForward = onForward,
                    canRewind = canRewind,
                    canForward = canForward,
                )
            }

            if (layout == PlayerControlsLayout.Portrait) {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    if (layout == PlayerControlsLayout.Landscape) {
        Box(
            modifier = modifier.fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            controlsContent()
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            controlsContent()
        }
    }
}

@Composable
private fun TransportControls(
    isPlaying: Boolean,
    onRewind: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onForward: () -> Unit,
    canRewind: Boolean,
    canForward: Boolean,
    playButtonSize: Dp = 64.dp,
    secondaryButtonSize: Dp = 48.dp,
    playIconSize: Dp = 48.dp,
    secondaryIconSize: Dp = 36.dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransportButton(
            onClick = onRewind,
            icon = Icons.Default.FastRewind,
            contentDescription = stringResource(R.string.rewind),
            iconSize = secondaryIconSize,
            buttonSize = secondaryButtonSize,
            enabled = canRewind,
        )
        TransportButton(
            onClick = onTogglePlayPause,
            icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (isPlaying) {
                stringResource(R.string.pause)
            } else {
                stringResource(R.string.play)
            },
            iconSize = playIconSize,
            buttonSize = playButtonSize,
        )
        TransportButton(
            onClick = onForward,
            icon = Icons.Default.FastForward,
            contentDescription = stringResource(R.string.forward),
            iconSize = secondaryIconSize,
            buttonSize = secondaryButtonSize,
            enabled = canForward,
        )
    }
}

@Composable
private fun TransportButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    iconSize: Dp,
    buttonSize: Dp = 48.dp,
    enabled: Boolean = true,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(buttonSize),
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = if (enabled) {
                LocalContentColor.current
            } else {
                LocalContentColor.current.copy(alpha = 0.38f)
            },
        )
    }
}

@Composable
private fun PlaybackLoadingOverlay(loadingStage: TtsLoadingStage) {
    val message = when (loadingStage) {
        TtsLoadingStage.LOADING_BOOK -> stringResource(R.string.tts_startup_loading_book)
        TtsLoadingStage.INIT_ENGINE -> stringResource(R.string.tts_startup_init_engine)
        TtsLoadingStage.INIT_SHERPA -> stringResource(R.string.tts_loading_sherpa)
        TtsLoadingStage.READING_TEXT -> stringResource(R.string.tts_startup_reading_text)
        TtsLoadingStage.SYNTHESIZING -> stringResource(R.string.tts_startup_synthesizing)
        TtsLoadingStage.OUTPUTTING_AUDIO -> stringResource(R.string.tts_startup_outputting_audio)
        TtsLoadingStage.NONE -> stringResource(R.string.tts_startup_loading_book)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PlaybackErrorOverlay(
    message: String,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                text = stringResource(R.string.tts_playback_error, message),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.tts_retry_playback))
            }
        }
    }
}

@Composable
private fun PagerDotIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Row(
        modifier = modifier.padding(vertical = if (compact) 2.dp else 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val isSelected = index == currentPage
            Box(
                modifier = Modifier
                    .size(if (isSelected) 7.dp else 5.dp)
                    .clip(CircleShape)
                    .background(
                        if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        },
                    ),
            )
        }
    }
}

@Composable
private fun CoverPage(
    coverPath: String?,
    isLandscape: Boolean,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = if (isLandscape) Alignment.TopCenter else Alignment.Center,
    ) {
        AsyncImage(
            model = coverPath?.let { File(it) } ?: R.drawable.default_cover,
            contentDescription = null,
            modifier = Modifier
                .then(
                    if (isLandscape) {
                        Modifier
                            .fillMaxHeight(1f)
                            .fillMaxWidth(0.7f)
                    } else {
                        Modifier.fillMaxWidth(0.75f)
                    },
                )
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Fit,
            placeholder = painterResource(R.drawable.default_cover),
            error = painterResource(R.drawable.default_cover),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookInfoPage(
    bookInfo: BookDisplayInfo,
    displayIndex: Int,
    totalSentences: Int,
    isLandscape: Boolean,
    isRefreshingMetadata: Boolean,
    onRefreshMetadata: () -> Unit,
) {
    val scrollState = rememberScrollState()
    val horizontalPadding = if (isLandscape) 12.dp else 16.dp
    val verticalPadding = if (isLandscape) 8.dp else 16.dp
    val unknownMetadata = stringResource(R.string.metadata_unknown)
    val neverPlayed = stringResource(R.string.never_played)
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copyAllSuccess = stringResource(R.string.copy_to_clipboard_success)

    val titleLabel = stringResource(R.string.book_title_label)
    val authorLabel = stringResource(R.string.book_author_label)
    val summaryLabel = stringResource(R.string.book_summary)
    val publisherLabel = stringResource(R.string.book_publisher)
    val publishedDateLabel = stringResource(R.string.book_published_date)
    val languageLabel = stringResource(R.string.book_language)
    val isbnLabel = stringResource(R.string.book_isbn)
    val subjectsLabel = stringResource(R.string.book_subjects)
    val formatLabel = stringResource(R.string.book_format)
    val sentenceCountLabel = stringResource(R.string.book_sentence_count, bookInfo.sentenceCount)
    val progressLabel = stringResource(R.string.reading_progress)
    val uploadTimeLabel = stringResource(R.string.upload_time)
    val lastPlayedLabel = stringResource(R.string.last_played)
    val sourceUrlLabel = stringResource(R.string.book_source_url)

    val progressTotal = totalSentences.coerceAtLeast(bookInfo.sentenceCount.coerceAtLeast(1))
    val progressValue = "${displayIndex + 1} / $progressTotal ${ProgressFormat.percentSuffix(displayIndex, progressTotal)}"
    val isbnValue = bookInfo.isbn.ifBlank { unknownMetadata }
    val subjectsValue = bookInfo.subjects.ifBlank { unknownMetadata }
    val lastPlayedValue = bookInfo.lastPlayedTime?.let { formatPlayerDate(it) } ?: neverPlayed

    fun copyText(text: String, toastMessage: String) {
        if (text.isBlank()) return
        clipboard.setText(AnnotatedString(text))
        AppToast.show(context, toastMessage)
    }

    fun copyField(value: String, fieldLabel: String) {
        copyText(value, context.getString(R.string.copy_field_success, fieldLabel))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .verticalScroll(scrollState)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.book_info),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        val allText = buildBookInfoCopyText(
                            bookInfo = bookInfo,
                            displayIndex = displayIndex,
                            totalSentences = totalSentences,
                            unknownMetadata = unknownMetadata,
                            neverPlayed = neverPlayed,
                            labels = BookInfoCopyLabels(
                                title = titleLabel,
                                author = authorLabel,
                                summary = summaryLabel,
                                publisher = publisherLabel,
                                publishedDate = publishedDateLabel,
                                language = languageLabel,
                                isbn = isbnLabel,
                                subjects = subjectsLabel,
                                format = formatLabel,
                                sentenceCount = sentenceCountLabel,
                                readingProgress = progressLabel,
                                uploadTime = uploadTimeLabel,
                                lastPlayed = lastPlayedLabel,
                                sourceUrl = sourceUrlLabel,
                            ),
                        )
                        copyText(allText, copyAllSuccess)
                    },
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.copy_book_info),
                    )
                }
                if (isRefreshingMetadata) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = onRefreshMetadata) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh_metadata),
                        )
                    }
                }
            }
        }

        Text(
            text = bookInfo.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.combinedClickable(
                onClick = {},
                onLongClick = { copyField(bookInfo.title, titleLabel) },
            ),
        )

        if (bookInfo.author.isNotBlank()) {
            Text(
                text = bookInfo.author,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { copyField(bookInfo.author, authorLabel) },
                ),
            )
        }

        if (bookInfo.summary.isNotBlank()) {
            BookInfoRow(
                label = summaryLabel,
                value = bookInfo.summary,
                multiline = true,
                onCopyValue = { copyField(bookInfo.summary, summaryLabel) },
            )
        }

        if (bookInfo.publisher.isNotBlank()) {
            BookInfoRow(
                label = publisherLabel,
                value = bookInfo.publisher,
                onCopyValue = { copyField(bookInfo.publisher, publisherLabel) },
            )
        }

        if (bookInfo.publishedDate.isNotBlank()) {
            BookInfoRow(
                label = publishedDateLabel,
                value = bookInfo.publishedDate,
                onCopyValue = { copyField(bookInfo.publishedDate, publishedDateLabel) },
            )
        }

        if (bookInfo.language.isNotBlank()) {
            BookInfoRow(
                label = languageLabel,
                value = bookInfo.language,
                onCopyValue = { copyField(bookInfo.language, languageLabel) },
            )
        }

        BookInfoRow(
            label = isbnLabel,
            value = isbnValue,
            onCopyValue = { copyField(isbnValue, isbnLabel) },
        )

        BookInfoRow(
            label = subjectsLabel,
            value = subjectsValue,
            multiline = true,
            onCopyValue = { copyField(subjectsValue, subjectsLabel) },
        )

        BookInfoRow(
            label = formatLabel,
            value = bookInfo.format.name,
            onCopyValue = { copyField(bookInfo.format.name, formatLabel) },
        )

        BookInfoRow(
            label = sentenceCountLabel,
            value = "",
            showValue = false,
        )

        BookInfoRow(
            label = progressLabel,
            value = progressValue,
            onCopyValue = { copyField(progressValue, progressLabel) },
        )

        if (bookInfo.uploadTime > 0L) {
            val uploadTimeValue = formatPlayerDate(bookInfo.uploadTime)
            BookInfoRow(
                label = uploadTimeLabel,
                value = uploadTimeValue,
                onCopyValue = { copyField(uploadTimeValue, uploadTimeLabel) },
            )
        }

        BookInfoRow(
            label = lastPlayedLabel,
            value = lastPlayedValue,
            onCopyValue = { copyField(lastPlayedValue, lastPlayedLabel) },
        )

        if (!bookInfo.sourceUrl.isNullOrBlank()) {
            BookInfoRow(
                label = sourceUrlLabel,
                value = bookInfo.sourceUrl,
                multiline = true,
                onCopyValue = { copyField(bookInfo.sourceUrl, sourceUrlLabel) },
            )
        }
    }
}

private data class BookInfoCopyLabels(
    val title: String,
    val author: String,
    val summary: String,
    val publisher: String,
    val publishedDate: String,
    val language: String,
    val isbn: String,
    val subjects: String,
    val format: String,
    val sentenceCount: String,
    val readingProgress: String,
    val uploadTime: String,
    val lastPlayed: String,
    val sourceUrl: String,
)

private fun buildBookInfoCopyText(
    bookInfo: BookDisplayInfo,
    displayIndex: Int,
    totalSentences: Int,
    unknownMetadata: String,
    neverPlayed: String,
    labels: BookInfoCopyLabels,
): String {
    val progressTotal = totalSentences.coerceAtLeast(bookInfo.sentenceCount.coerceAtLeast(1))
    val progressValue = "${displayIndex + 1} / $progressTotal ${ProgressFormat.percentSuffix(displayIndex, progressTotal)}"
    val isbnValue = bookInfo.isbn.ifBlank { unknownMetadata }
    val subjectsValue = bookInfo.subjects.ifBlank { unknownMetadata }
    val lastPlayedValue = bookInfo.lastPlayedTime?.let { formatPlayerDate(it) } ?: neverPlayed

    return buildString {
        appendCopyLine(labels.title, bookInfo.title)
        if (bookInfo.author.isNotBlank()) appendCopyLine(labels.author, bookInfo.author)
        if (bookInfo.summary.isNotBlank()) appendCopyLine(labels.summary, bookInfo.summary)
        if (bookInfo.publisher.isNotBlank()) appendCopyLine(labels.publisher, bookInfo.publisher)
        if (bookInfo.publishedDate.isNotBlank()) appendCopyLine(labels.publishedDate, bookInfo.publishedDate)
        if (bookInfo.language.isNotBlank()) appendCopyLine(labels.language, bookInfo.language)
        appendCopyLine(labels.isbn, isbnValue)
        appendCopyLine(labels.subjects, subjectsValue)
        appendCopyLine(labels.format, bookInfo.format.name)
        append(labels.sentenceCount)
        append('\n')
        appendCopyLine(labels.readingProgress, progressValue)
        if (bookInfo.uploadTime > 0L) {
            appendCopyLine(labels.uploadTime, formatPlayerDate(bookInfo.uploadTime))
        }
        appendCopyLine(labels.lastPlayed, lastPlayedValue)
        if (!bookInfo.sourceUrl.isNullOrBlank()) {
            appendCopyLine(labels.sourceUrl, bookInfo.sourceUrl)
        }
    }.trimEnd()
}

private fun StringBuilder.appendCopyLine(label: String, value: String) {
    if (value.isNotBlank()) {
        append(label)
        append('：')
        append(value)
        append('\n')
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookInfoRow(
    label: String,
    value: String,
    multiline: Boolean = false,
    showValue: Boolean = true,
    onCopyValue: (() -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showValue) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (multiline) Int.MAX_VALUE else 1,
                overflow = if (multiline) TextOverflow.Visible else TextOverflow.Ellipsis,
                modifier = if (onCopyValue != null) {
                    Modifier.combinedClickable(
                        onClick = {},
                        onLongClick = onCopyValue,
                    )
                } else {
                    Modifier
                },
            )
        }
    }
}

private fun formatPlayerDate(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}

@Composable
private fun SubtitlePage(
    sentences: List<SentenceIndexEntity>,
    currentIndex: Int,
    currentText: String,
    isLandscape: Boolean = false,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(currentIndex, currentText, sentences.size) {
        if (sentences.isEmpty()) return@LaunchedEffect
        val targetIndex = currentIndex.coerceIn(0, sentences.lastIndex)
        try {
            listState.scrollToItem(targetIndex)
        } catch (_: CancellationException) {
            // Playback or composition changed while scrolling.
        }
    }

    LazyColumn(
        state = listState,
        userScrollEnabled = false,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(12.dp)),
        contentPadding = PaddingValues(
            horizontal = 16.dp,
            vertical = if (isLandscape) 4.dp else 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(if (isLandscape) 4.dp else 8.dp),
    ) {
        itemsIndexed(
            items = sentences,
            key = { _, sentence -> "${sentence.bookId}_${sentence.sentenceIndex}" },
        ) { index, sentence ->
            val isCurrent = index == currentIndex
            Text(
                text = if (isCurrent && currentText.isNotBlank()) currentText else sentence.textPreview,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = if (isCurrent) 20.sp else 16.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                ),
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                },
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LandscapeSubtitlePage(
    sentences: List<SentenceIndexEntity>,
    currentIndex: Int,
    currentText: String,
) {
    if (sentences.isEmpty()) return

    val windowStart = currentIndex
    val windowEnd = (currentIndex + 4).coerceAtMost(sentences.lastIndex)
    val windowItems = sentences.subList(windowStart, windowEnd + 1)
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topInset = statusBarTop + LandscapeHeaderHeight + 2.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                RoundedCornerShape(12.dp),
            ),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = topInset, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            windowItems.forEachIndexed { offset, sentence ->
                val index = windowStart + offset
                val isCurrent = index == currentIndex
                Text(
                    text = if (isCurrent && currentText.isNotBlank()) currentText else sentence.textPreview,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontSize = if (isCurrent) 20.sp else 16.sp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    ),
                    color = if (isCurrent) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    },
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun PlaybackSpeedSection(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    onSpeedCommit: () -> Unit,
) {
    Text(stringResource(R.string.speed))
    Slider(
        value = speed,
        onValueChange = onSpeedChange,
        onValueChangeFinished = onSpeedCommit,
        valueRange = 0.5f..3.0f,
    )
    Text("${"%.1f".format(speed)}x")
}

@Composable
private fun SleepTimerChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSection(
    sleepTimerRemainingMs: Long,
    sleepUntilEndOfBook: Boolean,
    sleepTimerMinutes: Int,
    customMinutesText: String,
    onCustomMinutesTextChange: (String) -> Unit,
    onSleepTimer: (Int, Boolean) -> Unit,
    compact: Boolean = false,
    isLandscape: Boolean = false,
) {
    Text(stringResource(R.string.sleep_timer))

    val statusText = when {
        sleepTimerRemainingMs > 0 -> {
            stringResource(
                R.string.sleep_timer_remaining,
                formatSleepTimerRemaining(sleepTimerRemainingMs),
            )
        }
        sleepUntilEndOfBook -> stringResource(R.string.sleep_timer_end_of_book_active)
        else -> stringResource(R.string.sleep_timer_off)
    }
    Text(
        text = statusText,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))

    val presetMinutes = setOf(15, 30, 60)
    val isTimedActive = sleepTimerRemainingMs > 0
    val isOff = !sleepUntilEndOfBook && !isTimedActive && sleepTimerMinutes == 0

    if (compact) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SleepTimerChip(
                label = stringResource(R.string.sleep_15min),
                selected = isTimedActive && sleepTimerMinutes == 15,
                onClick = { onSleepTimer(15, false) },
            )
            SleepTimerChip(
                label = stringResource(R.string.sleep_30min),
                selected = isTimedActive && sleepTimerMinutes == 30,
                onClick = { onSleepTimer(30, false) },
            )
            SleepTimerChip(
                label = stringResource(R.string.sleep_60min),
                selected = isTimedActive && sleepTimerMinutes == 60,
                onClick = { onSleepTimer(60, false) },
            )
            SleepTimerChip(
                label = stringResource(R.string.sleep_end_of_book),
                selected = sleepUntilEndOfBook,
                onClick = { onSleepTimer(0, true) },
            )
            SleepTimerChip(
                label = stringResource(R.string.sleep_off),
                selected = isOff,
                onClick = { onSleepTimer(0, false) },
            )
        }
    } else if (isLandscape) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SleepTimerChip(
                label = stringResource(R.string.sleep_15min),
                selected = isTimedActive && sleepTimerMinutes == 15,
                onClick = { onSleepTimer(15, false) },
            )
            SleepTimerChip(
                label = stringResource(R.string.sleep_30min),
                selected = isTimedActive && sleepTimerMinutes == 30,
                onClick = { onSleepTimer(30, false) },
            )
            SleepTimerChip(
                label = stringResource(R.string.sleep_60min),
                selected = isTimedActive && sleepTimerMinutes == 60,
                onClick = { onSleepTimer(60, false) },
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SleepTimerChip(
                label = stringResource(R.string.sleep_end_of_book),
                selected = sleepUntilEndOfBook,
                onClick = { onSleepTimer(0, true) },
            )
            SleepTimerChip(
                label = stringResource(R.string.sleep_off),
                selected = isOff,
                onClick = { onSleepTimer(0, false) },
            )
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SleepTimerChip(
                label = stringResource(R.string.sleep_15min),
                selected = isTimedActive && sleepTimerMinutes == 15,
                onClick = { onSleepTimer(15, false) },
            )
            SleepTimerChip(
                label = stringResource(R.string.sleep_30min),
                selected = isTimedActive && sleepTimerMinutes == 30,
                onClick = { onSleepTimer(30, false) },
            )
            SleepTimerChip(
                label = stringResource(R.string.sleep_60min),
                selected = isTimedActive && sleepTimerMinutes == 60,
                onClick = { onSleepTimer(60, false) },
            )
        }
        SleepTimerChip(
            label = stringResource(R.string.sleep_end_of_book),
            selected = sleepUntilEndOfBook,
            onClick = { onSleepTimer(0, true) },
        )
        SleepTimerChip(
            label = stringResource(R.string.sleep_off),
            selected = isOff,
            onClick = { onSleepTimer(0, false) },
        )
    }

    val isCustomActive = isTimedActive && sleepTimerMinutes !in presetMinutes

    Spacer(modifier = Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (isLandscape) {
            OutlinedTextField(
                value = customMinutesText,
                onValueChange = { value ->
                    if (value.isEmpty() || value.all { it.isDigit() }) {
                        onCustomMinutesTextChange(value)
                    }
                },
                placeholder = { Text(stringResource(R.string.sleep_custom)) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 56.dp),
                isError = customMinutesText.isNotEmpty() &&
                    (customMinutesText.toIntOrNull() == null || customMinutesText.toInt() !in 1..600),
            )
        } else {
            OutlinedTextField(
                value = customMinutesText,
                onValueChange = { value ->
                    if (value.isEmpty() || value.all { it.isDigit() }) {
                        onCustomMinutesTextChange(value)
                    }
                },
                label = { Text(stringResource(R.string.sleep_custom)) },
                modifier = Modifier.weight(1f),
                isError = customMinutesText.isNotEmpty() &&
                    (customMinutesText.toIntOrNull() == null || customMinutesText.toInt() !in 1..600),
            )
        }
        TextButton(
            onClick = {
                val minutes = customMinutesText.toIntOrNull()
                if (minutes != null && minutes in 1..600) {
                    onSleepTimer(minutes, false)
                }
            },
            enabled = customMinutesText.toIntOrNull()?.let { it in 1..600 } == true,
        ) {
            Text("确定")
        }
    }
    if (isCustomActive) {
        Text(
            text = stringResource(R.string.sleep_timer_remaining, formatSleepTimerRemaining(sleepTimerRemainingMs)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private fun formatSleepTimerRemaining(remainingMs: Long): String {
    val totalSeconds = (remainingMs + 999) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PlaybackSettingsSheet(
    isLandscape: Boolean,
    currentSpeed: Float,
    sleepTimerRemainingMs: Long,
    sleepUntilEndOfBook: Boolean,
    sleepTimerMinutes: Int,
    onDismiss: () -> Unit,
    onSpeedCommit: (Float) -> Unit,
    onSleepTimer: (Int, Boolean) -> Unit,
) {
    var speed by remember { mutableFloatStateOf(currentSpeed) }
    var customMinutesText by remember { mutableStateOf("30") }

    LaunchedEffect(currentSpeed) {
        speed = currentSpeed
    }

    if (isLandscape) {
        val configuration = LocalConfiguration.current
        val maxDialogHeight = (configuration.screenHeightDp * 0.9f).dp
        val dialogWidth = (configuration.screenWidthDp * 0.78f).dp.coerceIn(640.dp, 840.dp)

        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 6.dp,
                modifier = Modifier
                    .widthIn(min = 640.dp, max = dialogWidth)
                    .heightIn(max = maxDialogHeight)
                    .imePadding(),
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(
                        stringResource(R.string.playback_settings),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row {
                        Column(
                            modifier = Modifier
                                .weight(0.9f)
                                .padding(end = 16.dp),
                        ) {
                            PlaybackSpeedSection(
                                speed = speed,
                                onSpeedChange = { speed = it },
                                onSpeedCommit = { onSpeedCommit(speed) },
                            )
                        }
                        VerticalDivider(
                            modifier = Modifier.fillMaxHeight(),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        )
                        Column(
                            modifier = Modifier
                                .weight(1.1f)
                                .padding(start = 16.dp),
                        ) {
                            SleepTimerSection(
                                sleepTimerRemainingMs = sleepTimerRemainingMs,
                                sleepUntilEndOfBook = sleepUntilEndOfBook,
                                sleepTimerMinutes = sleepTimerMinutes,
                                customMinutesText = customMinutesText,
                                onCustomMinutesTextChange = { customMinutesText = it },
                                onSleepTimer = onSleepTimer,
                                compact = false,
                                isLandscape = true,
                            )
                        }
                    }
                }
            }
        }
    } else {
        val sheetState = rememberModalBottomSheetState()

        ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    stringResource(R.string.playback_settings),
                    style = MaterialTheme.typography.titleLarge,
                )
                Spacer(modifier = Modifier.height(16.dp))

                PlaybackSpeedSection(
                    speed = speed,
                    onSpeedChange = { speed = it },
                    onSpeedCommit = { onSpeedCommit(speed) },
                )

                Spacer(modifier = Modifier.height(16.dp))

                SleepTimerSection(
                    sleepTimerRemainingMs = sleepTimerRemainingMs,
                    sleepUntilEndOfBook = sleepUntilEndOfBook,
                    sleepTimerMinutes = sleepTimerMinutes,
                    customMinutesText = customMinutesText,
                    onCustomMinutesTextChange = { customMinutesText = it },
                    onSleepTimer = onSleepTimer,
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
