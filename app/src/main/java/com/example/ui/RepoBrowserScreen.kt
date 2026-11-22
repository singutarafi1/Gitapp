package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.SavedToken
import com.example.data.model.EntryType
import com.example.data.model.RepoEntry
import com.example.data.model.TransferProgress
import com.example.ui.theme.CodeTextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoBrowserScreen(
  uiState: GitFilesUiState,
  onRefresh: () -> Unit,
  onOpenSettings: () -> Unit,
  onSwitchBranch: (String) -> Unit,
  onNavigateToPath: (String) -> Unit,
  onNavigateUp: () -> Boolean,
  onOpenFile: (RepoEntry) -> Unit,
  onUploadFilesClick: () -> Unit,
  onUploadFolderClick: () -> Unit,
  onCreateNewFile: (name: String, content: String, message: String) -> Unit,
  onCreateNewFolder: (name: String, message: String) -> Unit,
  onReplaceFileClick: (RepoEntry) -> Unit,
  onReplaceFolderClick: (RepoEntry) -> Unit,
  onDownloadFileClick: (RepoEntry) -> Unit,
  onDownloadFolderClick: (RepoEntry) -> Unit,
  onRenameEntry: (RepoEntry, newName: String, message: String) -> Unit,
  onDeleteEntry: (RepoEntry, message: String) -> Unit,
  onExecuteFolderAction: (targetName: String, replaceExisting: Boolean, message: String) -> Unit,
  onDismissPendingFolderAction: () -> Unit,
  onCancelTransfer: () -> Unit,
  onRetryTransfer: () -> Unit,
  onOpenCreateRepo: () -> Unit,
  onDismissCreateRepo: () -> Unit,
  onCreateRepo: (name: String, description: String, isPrivate: Boolean, autoInit: Boolean) -> Unit,
  onRequestToggleCurrentRepoVisibility: () -> Unit,
  onDismissToggleRepoVisibility: () -> Unit,
  onConfirmToggleRepoVisibility: () -> Unit,
  onRequestDeleteCurrentRepo: () -> Unit,
  onDismissDeleteRepo: () -> Unit,
  onConfirmDeleteRepo: () -> Unit,
  onOpenSavedTokens: () -> Unit,
  onDismissSavedTokens: () -> Unit,
  onOpenAddToken: () -> Unit,
  onDismissAddToken: () -> Unit,
  onSaveNewToken: (label: String, token: String) -> Unit,
  onSelectSavedToken: (SavedToken) -> Unit,
  onDeleteSavedToken: (SavedToken) -> Unit,
  onOpenTerminal: (initialPath: String?) -> Unit,
  onClearStatus: () -> Unit,
  onClearError: () -> Unit,
) {
  val session = uiState.session ?: return
  val snackbarHostState = remember { SnackbarHostState() }

  var showNewFileDialog by remember { mutableStateOf(false) }
  var showNewFolderDialog by remember { mutableStateOf(false) }
  var showBranchDialog by remember { mutableStateOf(false) }
  var showRepoActionsMenu by remember { mutableStateOf(false) }
  var renameTarget by remember { mutableStateOf<RepoEntry?>(null) }
  var deleteTarget by remember { mutableStateOf<RepoEntry?>(null) }

  BackHandler(enabled = uiState.currentPath.isNotEmpty()) { onNavigateUp() }

  LaunchedEffect(uiState.statusMessage) {
    val msg = uiState.statusMessage
    if (msg != null) {
      snackbarHostState.showSnackbar(msg)
      onClearStatus()
    }
  }

  val isBusy = uiState.transferProgress != null || uiState.isManagingRepo

  Scaffold(
    contentWindowInsets = WindowInsets.safeDrawing,
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              text = session.fullName,
              style = MaterialTheme.typography.titleLarge,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = "${session.branch} • ${if (session.isPrivate) "Private" else "Public"}",
              style = CodeTextStyle,
              color = MaterialTheme.colorScheme.primary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.clickable { showBranchDialog = true }.testTag("branch_selector"),
            )
          }
        },
        actions = {
          IconButton(
            onClick = onRefresh,
            enabled = !isBusy && !uiState.isLoadingEntries && !uiState.isManagingRepo,
            modifier = Modifier.testTag("refresh_button"),
          ) {
            Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.action_refresh))
          }
          IconButton(
            onClick = onOpenSettings,
            enabled = !isBusy && !uiState.isManagingRepo,
            modifier = Modifier.testTag("settings_button"),
          ) {
            Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.action_settings))
          }
          Box {
            IconButton(
              onClick = { showRepoActionsMenu = true },
              enabled = !isBusy && !uiState.isManagingRepo,
              modifier = Modifier.testTag("repo_actions_menu_button"),
            ) {
              Icon(Icons.Rounded.MoreVert, contentDescription = "Repository actions")
            }
            DropdownMenu(
              expanded = showRepoActionsMenu,
              onDismissRequest = { showRepoActionsMenu = false },
            ) {
              DropdownMenuItem(
                text = {
                  Text(
                    if (session.isPrivate) stringResource(R.string.action_make_public)
                    else stringResource(R.string.action_make_private)
                  )
                },
                leadingIcon = {
                  Icon(
                    imageVector =
                      if (session.isPrivate) Icons.Rounded.Public else Icons.Rounded.Lock,
                    contentDescription = null,
                  )
                },
                onClick = {
                  showRepoActionsMenu = false
                  onRequestToggleCurrentRepoVisibility()
                },
                modifier = Modifier.testTag("menu_toggle_repo_visibility"),
              )
              DropdownMenuItem(
                text = { Text(stringResource(R.string.action_new_repo)) },
                leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                onClick = {
                  showRepoActionsMenu = false
                  onOpenCreateRepo()
                },
                modifier = Modifier.testTag("menu_new_repo"),
              )
              DropdownMenuItem(
                text = { Text(stringResource(R.string.action_terminal)) },
                leadingIcon = { Icon(Icons.Rounded.Terminal, contentDescription = null) },
                onClick = {
                  showRepoActionsMenu = false
                  onOpenTerminal(null)
                },
                modifier = Modifier.testTag("menu_open_terminal"),
              )
              DropdownMenuItem(
                text = { Text(stringResource(R.string.action_switch_token)) },
                leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                onClick = {
                  showRepoActionsMenu = false
                  onOpenSavedTokens()
                },
                modifier = Modifier.testTag("menu_switch_token"),
              )
              DropdownMenuItem(
                text = {
                  Text(
                    stringResource(R.string.action_delete_repo),
                    color = MaterialTheme.colorScheme.error,
                  )
                },
                leadingIcon = {
                  Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                  )
                },
                onClick = {
                  showRepoActionsMenu = false
                  onRequestDeleteCurrentRepo()
                },
                modifier = Modifier.testTag("menu_delete_repo"),
              )
            }
          }
        },
      )
    },
  ) { innerPadding ->
    Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
      BreadcrumbBar(
        repoName = session.repo,
        currentPath = uiState.currentPath,
        onNavigateToPath = onNavigateToPath,
      )

      ActionStrip(
        enabled = !isBusy,
        onUploadFiles = onUploadFilesClick,
        onUploadFolder = onUploadFolderClick,
        onNewFile = { showNewFileDialog = true },
        onNewFolder = { showNewFolderDialog = true },
        onOpenTerminal = { onOpenTerminal(null) },
      )

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      if (uiState.isManagingRepo) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }

      if (uiState.transferProgress != null) {
        TransferProgressBanner(
          progress = uiState.transferProgress,
          onCancel = onCancelTransfer,
        )
      }

      if (uiState.browserError != null) {
        Surface(
          color = MaterialTheme.colorScheme.errorContainer,
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
          shape = RoundedCornerShape(12.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = uiState.browserError,
              color = MaterialTheme.colorScheme.onErrorContainer,
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.weight(1f),
            )
            if (uiState.canRetryTransfer) {
              TextButton(
                onClick = onRetryTransfer,
                enabled = !isBusy,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.testTag("retry_transfer_button"),
              ) {
                Text(
                  text = stringResource(R.string.action_retry),
                  color = MaterialTheme.colorScheme.onErrorContainer,
                )
              }
            }
            IconButton(onClick = onClearError) {
              Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Dismiss error",
                tint = MaterialTheme.colorScheme.onErrorContainer,
              )
            }
          }
        }
      }

      Box(modifier = Modifier.fillMaxSize().weight(1f)) {
        when {
          uiState.isLoadingEntries && uiState.entries.isEmpty() -> {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
          }
          uiState.entries.isEmpty() -> {
            Column(
              modifier = Modifier.align(Alignment.Center).padding(24.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Icon(
                imageVector = Icons.Rounded.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text = stringResource(R.string.empty_folder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          else -> {
            LazyColumn(
              modifier = Modifier.fillMaxSize().testTag("entries_list"),
              contentPadding = PaddingValues(vertical = 4.dp),
            ) {
              items(uiState.entries, key = { it.path }) { entry ->
                RepoEntryRow(
                  entry = entry,
                  enabled = !isBusy,
                  onClick = {
                    if (entry.type == EntryType.DIR) {
                      onNavigateToPath(entry.path)
                    } else {
                      onOpenFile(entry)
                    }
                  },
                  onEdit = { onOpenFile(entry) },
                  onDownload = {
                    if (entry.type == EntryType.DIR) onDownloadFolderClick(entry)
                    else onDownloadFileClick(entry)
                  },
                  onReplace = {
                    if (entry.type == EntryType.DIR) onReplaceFolderClick(entry)
                    else onReplaceFileClick(entry)
                  },
                  onRename = { renameTarget = entry },
                  onDelete = { deleteTarget = entry },
                  onOpenTerminalHere = { onOpenTerminal(entry.path) },
                )
                HorizontalDivider(
                  color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                  modifier = Modifier.padding(start = 56.dp),
                )
              }
            }
          }
        }
      }
    }
  }

  if (showNewFileDialog) {
    NewFileDialog(
      onDismiss = { showNewFileDialog = false },
      onConfirm = { name, content, msg ->
        showNewFileDialog = false
        onCreateNewFile(name, content, msg)
      },
    )
  }

  if (showNewFolderDialog) {
    NewFolderDialog(
      onDismiss = { showNewFolderDialog = false },
      onConfirm = { name, msg ->
        showNewFolderDialog = false
        onCreateNewFolder(name, msg)
      },
    )
  }

  if (showBranchDialog) {
    BranchPickerDialog(
      branches = session.branches,
      currentBranch = session.branch,
      onDismiss = { showBranchDialog = false },
      onSelect = { branch ->
        showBranchDialog = false
        onSwitchBranch(branch)
      },
    )
  }

  renameTarget?.let { target ->
    RenameEntryDialog(
      entry = target,
      onDismiss = { renameTarget = null },
      onConfirm = { newName, msg ->
        renameTarget = null
        onRenameEntry(target, newName, msg)
      },
    )
  }

  deleteTarget?.let { target ->
    DeleteConfirmDialog(
      entry = target,
      onDismiss = { deleteTarget = null },
      onConfirm = { msg ->
        deleteTarget = null
        onDeleteEntry(target, msg)
      },
    )
  }

  uiState.pendingFolderAction?.let { pending ->
    FolderActionConfirmDialog(
      pending = pending,
      currentRepoPath = uiState.currentPath,
      onDismiss = onDismissPendingFolderAction,
      onConfirm = { targetName, replaceExisting, msg ->
        onExecuteFolderAction(targetName, replaceExisting, msg)
      },
    )
  }

  if (uiState.showCreateRepoDialog) {
    CreateRepoDialog(
      onDismiss = onDismissCreateRepo,
      onConfirm = onCreateRepo,
    )
  }

  uiState.repoVisibilityTarget?.let { target ->
    ToggleRepoVisibilityDialog(
      repo = target,
      onDismiss = onDismissToggleRepoVisibility,
      onConfirm = onConfirmToggleRepoVisibility,
    )
  }

  uiState.repoDeleteTarget?.let { target ->
    DeleteRepoConfirmDialog(
      repo = target,
      onDismiss = onDismissDeleteRepo,
      onConfirm = onConfirmDeleteRepo,
    )
  }

  if (uiState.showSavedTokensDialog) {
    SavedTokensDialog(
      savedTokens = uiState.savedTokens,
      activeToken = uiState.tokenInput,
      onDismiss = onDismissSavedTokens,
      onSelect = onSelectSavedToken,
      onDelete = onDeleteSavedToken,
      onOpenAddToken = onOpenAddToken,
    )
  }

  if (uiState.showAddTokenDialog) {
    AddTokenDialog(
      isSaving = uiState.isSavingToken,
      errorMessage = uiState.tokenDialogError,
      onDismiss = onDismissAddToken,
      onSave = onSaveNewToken,
    )
  }
}

@Composable
private fun BreadcrumbBar(
  repoName: String,
  currentPath: String,
  onNavigateToPath: (String) -> Unit,
) {
  val segments = currentPath.split("/").filter { it.isNotEmpty() }
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    AssistChip(
      onClick = { onNavigateToPath("") },
      label = { Text(repoName, style = CodeTextStyle) },
      leadingIcon = {
        Icon(
          imageVector = Icons.Rounded.AccountTree,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
      },
      modifier = Modifier.testTag("breadcrumb_root"),
    )

    var accumulated = ""
    segments.forEach { segment ->
      accumulated = if (accumulated.isEmpty()) segment else "$accumulated/$segment"
      val target = accumulated
      Text(
        text = "/",
        style = CodeTextStyle,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      AssistChip(
        onClick = { onNavigateToPath(target) },
        label = { Text(segment, style = CodeTextStyle) },
      )
    }
  }
}

@Composable
private fun ActionStrip(
  enabled: Boolean,
  onUploadFiles: () -> Unit,
  onUploadFolder: () -> Unit,
  onNewFile: () -> Unit,
  onNewFolder: () -> Unit,
  onOpenTerminal: () -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 16.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    FilledTonalButton(
      onClick = onUploadFiles,
      enabled = enabled,
      contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
      modifier = Modifier.testTag("upload_file_button"),
    ) {
      Icon(Icons.Rounded.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
      Text(stringResource(R.string.action_upload_file))
    }
    FilledTonalButton(
      onClick = onUploadFolder,
      enabled = enabled,
      contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
      modifier = Modifier.testTag("upload_folder_button"),
    ) {
      Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
      Text(stringResource(R.string.action_upload_folder))
    }
    FilledTonalButton(
      onClick = onOpenTerminal,
      enabled = enabled,
      contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
      modifier = Modifier.testTag("action_terminal_button"),
    ) {
      Icon(Icons.Rounded.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
      Text(stringResource(R.string.action_terminal))
    }
    FilledTonalButton(
      onClick = onNewFile,
      enabled = enabled,
      contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
      modifier = Modifier.testTag("new_file_button"),
    ) {
      Icon(Icons.AutoMirrored.Rounded.NoteAdd, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
      Text(stringResource(R.string.action_new_file))
    }
    FilledTonalButton(
      onClick = onNewFolder,
      enabled = enabled,
      contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
      modifier = Modifier.testTag("new_folder_button"),
    ) {
      Icon(Icons.Rounded.CreateNewFolder, contentDescription = null, modifier = Modifier.size(18.dp))
      Spacer(Modifier.width(6.dp))
      Text(stringResource(R.string.action_new_folder))
    }
  }
}

@Composable
private fun TransferProgressBanner(
  progress: TransferProgress,
  onCancel: () -> Unit,
) {
  val sizeText =
    when {
      progress.totalBytes > 0L ->
        "${formatBytes(progress.transferredBytes)} / ${formatBytes(progress.totalBytes)}"
      progress.transferredBytes > 0L -> formatBytes(progress.transferredBytes)
      else -> ""
    }
  val speedText = formatSpeed(progress.bytesPerSecond)
  val elapsedText = formatDuration(progress.elapsedMs / 1000L)
  val etaText = progress.etaSeconds?.let { "~${formatDuration(it)} left" }.orEmpty()
  val rightMetrics =
    buildList {
      if (speedText.isNotEmpty()) add(speedText)
      add(if (etaText.isNotEmpty()) "$elapsedText ($etaText)" else elapsedText)
    }.joinToString(" • ")

  Surface(
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    shape = RoundedCornerShape(12.dp),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(progress.title, style = MaterialTheme.typography.labelLarge)
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (progress.total > 0) {
            Text(
              "${progress.completed}/${progress.total}",
              style = CodeTextStyle,
              color = MaterialTheme.colorScheme.primary,
            )
          }
          TextButton(
            onClick = onCancel,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            modifier = Modifier.testTag("cancel_transfer_button"),
          ) {
            Text(stringResource(R.string.action_cancel))
          }
        }
      }
      if (progress.totalBytes > 0L) {
        val fraction =
          (progress.transferredBytes.toFloat() / progress.totalBytes.toFloat()).coerceIn(0f, 1f)
        LinearProgressIndicator(
          progress = { fraction },
          modifier = Modifier.fillMaxWidth().height(6.dp),
        )
      } else if (progress.total > 0) {
        LinearProgressIndicator(
          progress = { (progress.completed.toFloat() / progress.total.toFloat()).coerceIn(0f, 1f) },
          modifier = Modifier.fillMaxWidth().height(6.dp),
        )
      } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(6.dp))
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = sizeText,
          style = CodeTextStyle,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = rightMetrics,
          style = CodeTextStyle,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (progress.currentItem.isNotEmpty()) {
        Text(
          text = progress.currentItem,
          style = CodeTextStyle,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun RepoEntryRow(
  entry: RepoEntry,
  enabled: Boolean,
  onClick: () -> Unit,
  onEdit: () -> Unit,
  onDownload: () -> Unit,
  onReplace: () -> Unit,
  onRename: () -> Unit,
  onDelete: () -> Unit,
  onOpenTerminalHere: () -> Unit,
) {
  var menuExpanded by remember { mutableStateOf(false) }
  val isDir = entry.type == EntryType.DIR

  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clickable(enabled = enabled, onClick = onClick)
        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)
        .testTag("entry_row_${entry.name}"),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector = if (isDir) Icons.Rounded.Folder else Icons.AutoMirrored.Rounded.InsertDriveFile,
      contentDescription = if (isDir) "Folder" else "File",
      tint = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(24.dp),
    )
    Spacer(Modifier.width(16.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = entry.name,
        style = MaterialTheme.typography.bodyLarge,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (!isDir) {
        Text(
          text = formatBytes(entry.size),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Box {
      IconButton(
        onClick = { menuExpanded = true },
        enabled = enabled,
        modifier = Modifier.testTag("entry_menu_${entry.name}"),
      ) {
        Icon(Icons.Rounded.MoreVert, contentDescription = "Actions")
      }
      DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
        if (isDir) {
          DropdownMenuItem(
            text = { Text(stringResource(R.string.action_open_terminal_here)) },
            leadingIcon = { Icon(Icons.Rounded.Terminal, contentDescription = null) },
            onClick = {
              menuExpanded = false
              onOpenTerminalHere()
            },
          )
        } else {
          DropdownMenuItem(
            text = { Text(stringResource(R.string.action_edit)) },
            leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
            onClick = {
              menuExpanded = false
              onEdit()
            },
          )
        }
        DropdownMenuItem(
          text = { Text(stringResource(R.string.action_download)) },
          leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
          onClick = {
            menuExpanded = false
            onDownload()
          },
        )
        DropdownMenuItem(
          text = {
            Text(
              if (isDir) stringResource(R.string.action_replace_folder)
              else stringResource(R.string.action_replace_file)
            )
          },
          leadingIcon = { Icon(Icons.Rounded.SwapHoriz, contentDescription = null) },
          onClick = {
            menuExpanded = false
            onReplace()
          },
        )
        DropdownMenuItem(
          text = { Text(stringResource(R.string.action_rename)) },
          leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = null) },
          onClick = {
            menuExpanded = false
            onRename()
          },
        )
        DropdownMenuItem(
          text = {
            Text(
              stringResource(R.string.action_delete),
              color = MaterialTheme.colorScheme.error,
            )
          },
          leadingIcon = {
            Icon(
              Icons.Rounded.Delete,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.error,
            )
          },
          onClick = {
            menuExpanded = false
            onDelete()
          },
        )
      }
    }
  }
}
