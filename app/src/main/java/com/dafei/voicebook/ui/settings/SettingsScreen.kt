package com.dafei.voicebook.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dafei.voicebook.R
import com.dafei.voicebook.data.db.TtsModelEntity
import com.dafei.voicebook.data.model.TtsDownloadState
import com.dafei.voicebook.data.model.TtsModelBenchmark
import com.dafei.voicebook.data.model.TtsModelFamily
import com.dafei.voicebook.tts.BuiltInTtsModels
import com.dafei.voicebook.tts.EngineSwitchState
import com.dafei.voicebook.tts.TtsLanguageGroup
import com.dafei.voicebook.tts.TtsLanguageGroups
import com.dafei.voicebook.ui.layout.isLandscape

@Composable
fun SettingsScreen(
    startupRecoveryMode: Boolean = false,
    viewModel: SettingsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val isApplying = uiState.engineSwitchState == EngineSwitchState.Applying
    val showDiscardDialog = remember { mutableStateOf(false) }
    val showRebuildDialog = remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshBatteryOptimizationStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    BackHandler(enabled = isApplying) {
        // Block back while applying
    }

    Scaffold(
        bottomBar = {
            if (uiState.selectedTab == 1) {
                TtsConfirmBar(
                    hasPendingChanges = uiState.hasPendingChanges,
                    isBusy = uiState.engineSwitchState != EngineSwitchState.Idle,
                    onApply = viewModel::applyPendingTtsSettings,
                    onDiscard = { showDiscardDialog.value = true },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
                TabRow(
                    selectedTabIndex = uiState.selectedTab,
                    modifier = if (isLandscape()) Modifier.height(40.dp) else Modifier,
                ) {
                    Tab(
                        selected = uiState.selectedTab == 0,
                        onClick = {
                            if (!isApplying) viewModel.setSelectedTab(0)
                        },
                        enabled = !isApplying,
                        text = { Text(stringResource(R.string.general_settings)) },
                    )
                    Tab(
                        selected = uiState.selectedTab == 1,
                        onClick = { viewModel.setSelectedTab(1) },
                        text = { Text(stringResource(R.string.tts_management)) },
                    )
                }

                when (uiState.selectedTab) {
                    0 -> GeneralSettingsTab(
                        keepScreenOn = uiState.keepScreenOn,
                        onKeepScreenOnChange = viewModel::setKeepScreenOn,
                        isIgnoringBatteryOptimizations = uiState.isIgnoringBatteryOptimizations,
                        onOpenBatteryOptimizationSettings = viewModel::openBatteryOptimizationSettings,
                        previewText = uiState.previewText,
                        onPreviewTextChange = viewModel::setPreviewText,
                        previewEnabled = uiState.engineSwitchState != EngineSwitchState.Applying,
                        uploadServerEnabled = uiState.uploadServerEnabled,
                        onUploadServerEnabledChange = viewModel::setUploadServerEnabled,
                        uploadServerAddress = uiState.uploadServerAddress,
                        onCopyUploadServerAddress = viewModel::copyUploadServerAddress,
                        rebuildRunning = uiState.rebuildRunning,
                        rebuildStage = uiState.rebuildStage,
                        rebuildTotalFiles = uiState.rebuildTotalFiles,
                        rebuildProcessedFiles = uiState.rebuildProcessedFiles,
                        rebuildSuccessCount = uiState.rebuildSuccessCount,
                        rebuildFailedCount = uiState.rebuildFailedCount,
                        rebuildCurrentFileName = uiState.rebuildCurrentFileName,
                        startupRecoveryMode = startupRecoveryMode,
                        onStartRebuild = { showRebuildDialog.value = true },
                    )
                    1 -> TtsManagementTab(
                        uiState = uiState,
                        onFamilySelected = viewModel::setSelectedFamily,
                        onLanguageFilterSelected = viewModel::setLanguageFilter,
                        onDownload = viewModel::downloadModel,
                        onSelectDefaultDraft = viewModel::selectDefaultDraft,
                        onDelete = viewModel::deleteModel,
                        onSpeakerChange = viewModel::selectSpeakerDraft,
                        onPreview = viewModel::previewModel,
                        formatSize = viewModel::formatSize,
                        formatBenchmark = viewModel::formatBenchmark,
                        benchmarkKey = viewModel::benchmarkKey,
                    )
                }
            }

            if (isApplying) {
                ApplyingOverlay()
            }
        }
    }

    if (showDiscardDialog.value) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDiscardDialog.value = false },
            title = { Text(stringResource(R.string.tts_discard_title)) },
            text = { Text(stringResource(R.string.tts_discard_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.discardPendingTtsSettings()
                        showDiscardDialog.value = false
                    },
                ) {
                    Text(stringResource(R.string.tts_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog.value = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showRebuildDialog.value) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showRebuildDialog.value = false },
            title = { Text(stringResource(R.string.rebuild_library_title)) },
            text = { Text(stringResource(R.string.rebuild_library_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.rebuildLibrary()
                        showRebuildDialog.value = false
                    },
                ) {
                    Text(stringResource(R.string.rebuild_library_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRebuildDialog.value = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ApplyingOverlay() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.tts_applying_engine),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun TtsConfirmBar(
    hasPendingChanges: Boolean,
    isBusy: Boolean,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onDiscard,
                enabled = hasPendingChanges && !isBusy,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.tts_discard_changes))
            }
            Button(
                onClick = onApply,
                enabled = hasPendingChanges && !isBusy,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.tts_apply_engine))
            }
        }
    }
}

@Composable
private fun GeneralSettingsTab(
    keepScreenOn: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    isIgnoringBatteryOptimizations: Boolean,
    onOpenBatteryOptimizationSettings: () -> Unit,
    previewText: String,
    onPreviewTextChange: (String) -> Unit,
    previewEnabled: Boolean,
    uploadServerEnabled: Boolean,
    onUploadServerEnabledChange: (Boolean) -> Unit,
    uploadServerAddress: String?,
    onCopyUploadServerAddress: (String) -> Unit,
    rebuildRunning: Boolean,
    rebuildStage: com.dafei.voicebook.data.repository.BookRepository.RebuildStage,
    rebuildTotalFiles: Int,
    rebuildProcessedFiles: Int,
    rebuildSuccessCount: Int,
    rebuildFailedCount: Int,
    rebuildCurrentFileName: String?,
    startupRecoveryMode: Boolean,
    onStartRebuild: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.keep_screen_on),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(checked = keepScreenOn, onCheckedChange = onKeepScreenOnChange)
        }

        UploadServerSection(
            enabled = uploadServerEnabled,
            onEnabledChange = onUploadServerEnabledChange,
            serverAddress = uploadServerAddress,
            onCopyAddress = onCopyUploadServerAddress,
        )

        BatteryOptimizationSection(
            isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
            onOpenSettings = onOpenBatteryOptimizationSettings,
        )

        TtsPreviewSection(
            previewText = previewText,
            onPreviewTextChange = onPreviewTextChange,
            enabled = previewEnabled,
        )

        LibraryRebuildSection(
            running = rebuildRunning,
            stage = rebuildStage,
            totalFiles = rebuildTotalFiles,
            processedFiles = rebuildProcessedFiles,
            successCount = rebuildSuccessCount,
            failedCount = rebuildFailedCount,
            currentFileName = rebuildCurrentFileName,
            startupRecoveryMode = startupRecoveryMode,
            onStartRebuild = onStartRebuild,
        )
    }
}

@Composable
private fun LibraryRebuildSection(
    running: Boolean,
    stage: com.dafei.voicebook.data.repository.BookRepository.RebuildStage,
    totalFiles: Int,
    processedFiles: Int,
    successCount: Int,
    failedCount: Int,
    currentFileName: String?,
    startupRecoveryMode: Boolean,
    onStartRebuild: () -> Unit,
) {
    val sectionColors = if (startupRecoveryMode) {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    } else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    }
    val sectionContentColor = if (startupRecoveryMode) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = sectionColors,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.rebuild_library_title),
                style = MaterialTheme.typography.titleMedium,
                color = sectionContentColor,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.rebuild_library_desc),
                style = MaterialTheme.typography.bodySmall,
                color = sectionContentColor.copy(alpha = 0.85f),
            )
            if (startupRecoveryMode) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.startup_rebuild_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            val buttonContentColor = if (startupRecoveryMode) {
                MaterialTheme.colorScheme.onError
            } else {
                MaterialTheme.colorScheme.onPrimary
            }
            Button(
                onClick = onStartRebuild,
                enabled = !running,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                colors = if (startupRecoveryMode) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = buttonContentColor,
                    )
                } else {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = buttonContentColor,
                    )
                },
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    tint = buttonContentColor,
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.rebuild_library_button),
                    style = MaterialTheme.typography.bodyLarge,
                    color = buttonContentColor,
                )
            }
            if (running || stage == com.dafei.voicebook.data.repository.BookRepository.RebuildStage.COMPLETED) {
                Spacer(modifier = Modifier.height(12.dp))
                val progress = if (totalFiles > 0) processedFiles.toFloat() / totalFiles.toFloat() else 0f
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.rebuild_library_progress_count, processedFiles, totalFiles),
                    style = MaterialTheme.typography.bodySmall,
                    color = sectionContentColor,
                )
                Text(
                    text = stringResource(R.string.rebuild_library_result_count, successCount, failedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = sectionContentColor,
                )
                currentFileName?.let {
                    Text(
                        text = stringResource(R.string.rebuild_library_current_file, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = sectionContentColor.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

@Composable
private fun UploadServerSection(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    serverAddress: String?,
    onCopyAddress: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.upload_server_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.upload_server_manual_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.upload_server_enable),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
            )
            Switch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        if (enabled) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.upload_server_address),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            val addressText = serverAddress ?: stringResource(R.string.upload_server_starting)
            Text(
                text = addressText,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = serverAddress != null) {
                        serverAddress?.let(onCopyAddress)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = if (serverAddress != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun BatteryOptimizationSection(
    isIgnoringBatteryOptimizations: Boolean,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = stringResource(R.string.battery_optimization_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(
                if (isIgnoringBatteryOptimizations) {
                    R.string.battery_optimization_status_allowed
                } else {
                    R.string.battery_optimization_status_restricted
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isIgnoringBatteryOptimizations) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.battery_optimization_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!isIgnoringBatteryOptimizations) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(onClick = onOpenSettings) {
                Text(stringResource(R.string.battery_optimization_open_settings))
            }
        }
    }
}

@Composable
private fun TtsManagementTab(
    uiState: SettingsUiState,
    onFamilySelected: (TtsModelFamily) -> Unit,
    onLanguageFilterSelected: (String?) -> Unit,
    onDownload: (String, Boolean) -> Unit,
    onSelectDefaultDraft: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSpeakerChange: (String, Int) -> Unit,
    onPreview: (String) -> Unit,
    formatSize: (Long) -> String,
    formatBenchmark: (TtsModelBenchmark) -> String,
    benchmarkKey: (String, Int) -> String,
) {
    val familyModels = uiState.ttsModels.filter { model ->
        BuiltInTtsModels.infoFor(model.id)?.family == uiState.selectedFamily
    }
    val languageGroups = TtsLanguageGroups.groupModels(familyModels)
    val availableGroups = TtsLanguageGroups.distinctGroups(familyModels)
    val languageFilter = uiState.selectedLanguageFilter
    val familyIndex = BuiltInTtsModels.FAMILIES.indexOf(uiState.selectedFamily).coerceAtLeast(0)
    val isBusy = uiState.engineSwitchState != EngineSwitchState.Idle

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = familyIndex) {
            BuiltInTtsModels.FAMILIES.forEach { family ->
                Tab(
                    selected = uiState.selectedFamily == family,
                    onClick = { onFamilySelected(family) },
                    enabled = !isBusy,
                    text = {
                        Text(
                            when (family) {
                                TtsModelFamily.VITS -> stringResource(R.string.tts_family_vits)
                                TtsModelFamily.KOKORO -> stringResource(R.string.tts_family_kokoro)
                                TtsModelFamily.KITTEN -> stringResource(R.string.tts_family_kitten)
                            },
                        )
                    },
                )
            }
        }

        if (uiState.engineSwitchError != null) {
            Text(
                text = uiState.engineSwitchError,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            item(key = "language-filters") {
                TtsLanguageFilterRow(
                    availableGroups = availableGroups,
                    selectedGroupId = languageFilter,
                    enabled = !isBusy,
                    onFilterSelected = onLanguageFilterSelected,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            languageGroups.forEach { (group, models) ->
                if (languageFilter != null && languageFilter != group.id) return@forEach

                item(key = "header-${group.id}") {
                    val downloaded = models.count { it.downloadState == TtsDownloadState.DOWNLOADED.name }
                    TtsLanguageSectionHeader(
                        title = stringResource(group.labelRes),
                        downloaded = downloaded,
                        total = models.size,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                items(models, key = { it.id }) { model ->
                    val pendingSpeaker = uiState.pendingSpeakerIds[model.id] ?: model.speakerId
                    val isSelectedDefault = uiState.pendingDefaultModelId == model.id
                    val showPendingApply = isSelectedDefault && uiState.hasPendingChanges
                    val benchmark = uiState.benchmarks[benchmarkKey(model.id, pendingSpeaker)]
                    TtsModelCard(
                        model = model,
                        isSelectedDefault = isSelectedDefault,
                        showPendingApply = showPendingApply,
                        pendingSpeakerId = pendingSpeaker,
                        progress = uiState.downloadProgress[model.id],
                        benchmark = benchmark,
                        isPreviewing = uiState.previewingModelId == model.id &&
                            uiState.engineSwitchState == EngineSwitchState.Previewing,
                        isBusy = isBusy,
                        onDownload = { useMirror -> onDownload(model.id, useMirror) },
                        onSelectDefault = { onSelectDefaultDraft(model.id) },
                        onDelete = { onDelete(model.id) },
                        onSpeakerChange = { speakerId -> onSpeakerChange(model.id, speakerId) },
                        onPreview = { onPreview(model.id) },
                        formatSize = formatSize,
                        formatBenchmark = formatBenchmark,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TtsLanguageFilterRow(
    availableGroups: List<TtsLanguageGroup>,
    selectedGroupId: String?,
    enabled: Boolean,
    onFilterSelected: (String?) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 12.dp),
    ) {
        FilterChip(
            selected = selectedGroupId == null,
            onClick = { if (enabled) onFilterSelected(null) },
            enabled = enabled,
            label = { Text(stringResource(R.string.tts_language_all)) },
        )
        availableGroups.forEach { group ->
            FilterChip(
                selected = selectedGroupId == group.id,
                onClick = { if (enabled) onFilterSelected(group.id) },
                enabled = enabled,
                label = { Text(stringResource(group.labelRes)) },
            )
        }
    }
}

@Composable
private fun TtsLanguageSectionHeader(
    title: String,
    downloaded: Int,
    total: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.tts_language_section_count, downloaded, total),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TtsPreviewSection(
    previewText: String,
    onPreviewTextChange: (String) -> Unit,
    enabled: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.tts_preview_section),
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = previewText,
            onValueChange = onPreviewTextChange,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.tts_preview_hint)) },
            minLines = 3,
            maxLines = 8,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TtsModelCard(
    model: TtsModelEntity,
    isSelectedDefault: Boolean,
    showPendingApply: Boolean,
    pendingSpeakerId: Int,
    progress: Float?,
    benchmark: TtsModelBenchmark?,
    isPreviewing: Boolean,
    isBusy: Boolean,
    onDownload: (Boolean) -> Unit,
    onSelectDefault: () -> Unit,
    onDelete: () -> Unit,
    onSpeakerChange: (Int) -> Unit,
    onPreview: () -> Unit,
    formatSize: (Long) -> String,
    formatBenchmark: (TtsModelBenchmark) -> String,
) {
    val modelInfo = BuiltInTtsModels.infoFor(model.id)
    val speakerCount = modelInfo?.speakerCount ?: 1
    val hasMirrorDownload = modelInfo?.mirrorDownloadUrl != null
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(model.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        formatSize(model.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (model.isDefault && !showPendingApply) {
                        Text(
                            stringResource(R.string.default_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (showPendingApply) {
                        Text(
                            stringResource(R.string.tts_pending_default),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }

            if (benchmark != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = formatBenchmark(benchmark),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (model.downloadState) {
                TtsDownloadState.DOWNLOADING.name -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { progress ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.downloading), style = MaterialTheme.typography.labelSmall)
                }
                TtsDownloadState.DOWNLOADED.name -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(
                            selected = isSelectedDefault,
                            onClick = onSelectDefault,
                            enabled = !isBusy,
                        )
                        Text(
                            text = stringResource(R.string.tts_select_engine),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onPreview, enabled = !isBusy || isPreviewing) {
                            if (isPreviewing) {
                                Text(stringResource(R.string.tts_preview_stop))
                            } else {
                                Text(stringResource(R.string.tts_preview))
                            }
                        }
                        TextButton(onClick = onDelete, enabled = !isBusy) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                    if (speakerCount > 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.voice),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        if (speakerCount <= 10) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 4.dp),
                            ) {
                                for (id in 0 until speakerCount) {
                                    FilterChip(
                                        selected = pendingSpeakerId == id,
                                        onClick = { if (!isBusy) onSpeakerChange(id) },
                                        enabled = !isBusy,
                                        label = { Text(stringResource(R.string.speaker_label, id)) },
                                    )
                                }
                            }
                        } else {
                            var speakerInput by remember(model.id, pendingSpeakerId) {
                                mutableStateOf(pendingSpeakerId.toString())
                            }
                            OutlinedTextField(
                                value = speakerInput,
                                onValueChange = { value ->
                                    if (isBusy) return@OutlinedTextField
                                    speakerInput = value.filter { it.isDigit() }.take(4)
                                    speakerInput.toIntOrNull()?.let { id ->
                                        if (id in 0 until speakerCount) {
                                            onSpeakerChange(id)
                                        }
                                    }
                                },
                                enabled = !isBusy,
                                label = { Text(stringResource(R.string.speaker_id_hint, speakerCount - 1)) },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                            )
                        }
                    }
                }
                TtsDownloadState.FAILED.name -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        model.downloadError ?: stringResource(R.string.tts_download_failed),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (hasMirrorDownload) {
                        Row {
                            TextButton(onClick = { onDownload(false) }, enabled = !isBusy) {
                                Text(stringResource(R.string.retry_download))
                            }
                            TextButton(onClick = { onDownload(true) }, enabled = !isBusy) {
                                Text(stringResource(R.string.mirror_download))
                            }
                        }
                    } else {
                        TextButton(onClick = { onDownload(false) }, enabled = !isBusy) {
                            Text(stringResource(R.string.retry_download))
                        }
                    }
                }
                else -> {
                    if (hasMirrorDownload) {
                        Row {
                            TextButton(onClick = { onDownload(false) }, enabled = !isBusy) {
                                Text(stringResource(R.string.download))
                            }
                            TextButton(onClick = { onDownload(true) }, enabled = !isBusy) {
                                Text(stringResource(R.string.mirror_download))
                            }
                        }
                    } else {
                        TextButton(onClick = { onDownload(false) }, enabled = !isBusy) {
                            Text(stringResource(R.string.download))
                        }
                    }
                }
            }
        }
    }
}
