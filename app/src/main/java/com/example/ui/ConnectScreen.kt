package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.SavedToken
import com.example.data.model.RepoSummary
import com.example.ui.theme.CodeTextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
  uiState: GitFilesUiState,
  onTokenChange: (String) -> Unit,
  onRepoUrlChange: (String) -> Unit,
  onBranchChange: (String) -> Unit,
  onUploadBatchSizeChange: (String) -> Unit,
  onSelectUploadBatchSizePreset: (Int) -> Unit,
  onConnect: () -> Unit,
  onOpenRepoPicker: () -> Unit,
  onDismissRepoPicker: () -> Unit,
  onSelectRepoFromPicker: (RepoSummary) -> Unit,
  onOpenCreateRepo: () -> Unit,
  onDismissCreateRepo: () -> Unit,
  onCreateRepo: (name: String, description: String, isPrivate: Boolean, autoInit: Boolean) -> Unit,
  onRequestToggleRepoVisibility: (RepoSummary) -> Unit,
  onDismissToggleRepoVisibility: () -> Unit,
  onConfirmToggleRepoVisibility: () -> Unit,
  onRequestDeleteRepo: (RepoSummary) -> Unit,
  onDismissDeleteRepo: () -> Unit,
  onConfirmDeleteRepo: () -> Unit,
  onOpenSavedTokens: () -> Unit,
  onDismissSavedTokens: () -> Unit,
  onOpenAddToken: () -> Unit,
  onDismissAddToken: () -> Unit,
  onSaveNewToken: (label: String, token: String) -> Unit,
  onSelectSavedToken: (SavedToken) -> Unit,
  onDeleteSavedToken: (SavedToken) -> Unit,
  onClearStatus: () -> Unit,
  onBackToBrowser: () -> Unit,
  onDisconnect: () -> Unit,
) {
  var showToken by rememberSaveable { mutableStateOf(false) }
  val hasActiveSession = uiState.session != null
  val snackbarHostState = remember { SnackbarHostState() }

  LaunchedEffect(uiState.statusMessage) {
    val msg = uiState.statusMessage
    if (msg != null) {
      snackbarHostState.showSnackbar(msg)
      onClearStatus()
    }
  }

  if (hasActiveSession) {
    BackHandler { onBackToBrowser() }
  }

  Scaffold(
    contentWindowInsets = WindowInsets.safeDrawing,
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      if (hasActiveSession) {
        TopAppBar(
          title = { Text(stringResource(R.string.connect_title)) },
          navigationIcon = {
            IconButton(
              onClick = onBackToBrowser,
              modifier = Modifier.testTag("settings_back_button"),
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
              )
            }
          },
        )
      }
    },
  ) { innerPadding ->
    Box(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .imePadding()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp, vertical = 24.dp),
      contentAlignment = Alignment.Center,
    ) {
      Column(
        modifier = Modifier.fillMaxWidth().widthIn(max = 520.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Box(
            modifier =
              Modifier.size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.Rounded.FolderCopy,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
          Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
          )
        }

        Spacer(modifier = Modifier.height(4.dp))

        OutlinedTextField(
          value = uiState.tokenInput,
          onValueChange = onTokenChange,
          label = { Text(stringResource(R.string.label_github_token)) },
          placeholder = { Text(stringResource(R.string.hint_github_token)) },
          leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
          trailingIcon = {
            IconButton(
              onClick = { showToken = !showToken },
              modifier = Modifier.testTag("toggle_token_visibility"),
            ) {
              Icon(
                imageVector = if (showToken) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                contentDescription = if (showToken) "Hide token" else "Show token",
              )
            }
          },
          visualTransformation =
            if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
          singleLine = true,
          textStyle = CodeTextStyle,
          keyboardOptions =
            KeyboardOptions(
              keyboardType = KeyboardType.Password,
              imeAction = ImeAction.Next,
            ),
          modifier = Modifier.fillMaxWidth().testTag("token_input"),
        )

        Row(
          modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          uiState.savedTokens.forEach { saved ->
            val selected = saved.token == uiState.tokenInput.trim()
            FilterChip(
              selected = selected,
              onClick = { onSelectSavedToken(saved) },
              label = { Text(saved.label) },
              leadingIcon =
                if (selected) {
                  {
                    Icon(
                      imageVector = Icons.Rounded.Check,
                      contentDescription = null,
                      modifier = Modifier.size(16.dp),
                    )
                  }
                } else {
                  null
                },
              modifier = Modifier.testTag("saved_token_chip_${saved.id}"),
            )
          }
          AssistChip(
            onClick = onOpenAddToken,
            label = { Text(stringResource(R.string.action_add_token)) },
            leadingIcon = {
              Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
            },
            modifier = Modifier.testTag("add_token_chip"),
          )
          if (uiState.savedTokens.isNotEmpty()) {
            AssistChip(
              onClick = onOpenSavedTokens,
              label = { Text("${stringResource(R.string.action_manage_tokens)} (${uiState.savedTokens.size})") },
              leadingIcon = {
                Icon(
                  imageVector = Icons.Rounded.Key,
                  contentDescription = null,
                  modifier = Modifier.size(16.dp),
                )
              },
              modifier = Modifier.testTag("manage_tokens_chip"),
            )
          }
        }

        OutlinedTextField(
          value = uiState.repoUrlInput,
          onValueChange = onRepoUrlChange,
          label = { Text(stringResource(R.string.label_repo_url)) },
          placeholder = { Text(stringResource(R.string.hint_repo_url)) },
          leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
          trailingIcon = {
            IconButton(
              onClick = onOpenRepoPicker,
              enabled = !uiState.isConnecting && !uiState.isLoadingRepos,
              modifier = Modifier.testTag("pick_repo_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.FolderOpen,
                contentDescription = "Select repository",
              )
            }
          },
          singleLine = true,
          textStyle = CodeTextStyle,
          keyboardOptions =
            KeyboardOptions(
              keyboardType = KeyboardType.Uri,
              imeAction = ImeAction.Next,
            ),
          modifier = Modifier.fillMaxWidth().testTag("repo_url_input"),
        )

        OutlinedTextField(
          value = uiState.branchInput,
          onValueChange = onBranchChange,
          label = { Text(stringResource(R.string.label_branch_optional)) },
          placeholder = { Text(stringResource(R.string.hint_branch_optional)) },
          singleLine = true,
          textStyle = CodeTextStyle,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
          keyboardActions = KeyboardActions(onDone = { if (!uiState.isConnecting) onConnect() }),
          modifier = Modifier.fillMaxWidth().testTag("branch_input"),
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            value = uiState.uploadBatchSizeInput,
            onValueChange = onUploadBatchSizeChange,
            label = { Text(stringResource(R.string.label_upload_batch_size)) },
            placeholder = { Text(stringResource(R.string.hint_upload_batch_size)) },
            supportingText = { Text(stringResource(R.string.helper_upload_batch_size)) },
            singleLine = true,
            textStyle = CodeTextStyle,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth().testTag("upload_batch_size_input"),
          )
          val selectedBatch = uiState.uploadBatchSizeInput.toIntOrNull()
          Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            listOf(10, 25, 50, 100, 150, 200).forEach { preset ->
              val isSelected = selectedBatch == preset
              FilterChip(
                selected = isSelected,
                onClick = { onSelectUploadBatchSizePreset(preset) },
                label = { Text("$preset", style = CodeTextStyle) },
                leadingIcon =
                  if (isSelected) {
                    {
                      Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                      )
                    }
                  } else {
                    null
                  },
                modifier = Modifier.testTag("batch_preset_chip_$preset"),
              )
            }
          }
        }

        if (uiState.connectionError != null) {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().testTag("connection_error_box"),
          ) {
            Text(
              text = uiState.connectionError,
              color = MaterialTheme.colorScheme.onErrorContainer,
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.padding(14.dp),
            )
          }
        }

        Button(
          onClick = onConnect,
          enabled = !uiState.isConnecting && !uiState.isManagingRepo,
          modifier = Modifier.fillMaxWidth().height(50.dp).testTag("connect_button"),
          shape = RoundedCornerShape(12.dp),
        ) {
          if (uiState.isConnecting || uiState.isManagingRepo) {
            CircularProgressIndicator(
              modifier = Modifier.size(20.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(modifier = Modifier.width(10.dp))
          }
          Text(stringResource(R.string.action_connect))
        }

        OutlinedButton(
          onClick = onOpenCreateRepo,
          enabled = !uiState.isConnecting && !uiState.isManagingRepo,
          modifier = Modifier.fillMaxWidth().height(48.dp).testTag("new_repo_button"),
          shape = RoundedCornerShape(12.dp),
        ) {
          Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(stringResource(R.string.action_new_repo))
        }

        if (hasActiveSession) {
          OutlinedButton(
            onClick = onDisconnect,
            enabled = !uiState.isConnecting && !uiState.isManagingRepo,
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("disconnect_button"),
            shape = RoundedCornerShape(12.dp),
          ) {
            Text(stringResource(R.string.action_disconnect))
          }
        }
      }
    }
  }

  if (uiState.showRepoPicker) {
    AlertDialog(
      onDismissRequest = onDismissRepoPicker,
      title = {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text("Repositories")
          TextButton(
            onClick = {
              onDismissRepoPicker()
              onOpenCreateRepo()
            },
            enabled = !uiState.isManagingRepo,
            modifier = Modifier.testTag("picker_new_repo_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.Add,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("New")
          }
        }
      },
      text = {
        Column(modifier = Modifier.fillMaxWidth()) {
          if (uiState.isManagingRepo) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
          }
          if (!uiState.isLoadingRepos && uiState.repoPickerNotice != null) {
            Surface(
              color = MaterialTheme.colorScheme.secondaryContainer,
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
              Text(
                text = uiState.repoPickerNotice,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(10.dp),
              )
            }
          }
          when {
            uiState.isLoadingRepos -> {
              Box(
                modifier = Modifier.fillMaxWidth().height(120.dp),
                contentAlignment = Alignment.Center,
              ) {
                CircularProgressIndicator()
              }
            }
            uiState.accessibleRepos.isEmpty() -> {
              Text(
                text = "No repositories accessible to this token.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            else -> {
              LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(uiState.accessibleRepos, key = { it.fullName }) { repo ->
                  var menuExpanded by remember { mutableStateOf(false) }
                  Row(
                    modifier =
                      Modifier.fillMaxWidth()
                        .clickable { onSelectRepoFromPicker(repo) }
                        .padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                  ) {
                    Icon(
                      imageVector = if (repo.isPrivate) Icons.Rounded.Lock else Icons.Rounded.Public,
                      contentDescription = if (repo.isPrivate) "Private" else "Public",
                      tint =
                        if (repo.isPrivate) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.size(18.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                      Text(
                        text = repo.fullName,
                        style = CodeTextStyle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                      )
                      Text(
                        text =
                          "${repo.defaultBranch} • ${if (repo.isPrivate) "Private" else "Public"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                    Box {
                      IconButton(
                        onClick = { menuExpanded = true },
                        enabled = !uiState.isManagingRepo,
                        modifier = Modifier.testTag("repo_picker_menu_${repo.fullName}"),
                      ) {
                        Icon(
                          imageVector = Icons.Rounded.MoreVert,
                          contentDescription = "Repository actions",
                        )
                      }
                      DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                      ) {
                        DropdownMenuItem(
                          text = {
                            Text(
                              if (repo.isPrivate) stringResource(R.string.action_make_public)
                              else stringResource(R.string.action_make_private)
                            )
                          },
                          leadingIcon = {
                            Icon(
                              imageVector =
                                if (repo.isPrivate) Icons.Rounded.Public else Icons.Rounded.Lock,
                              contentDescription = null,
                            )
                          },
                          onClick = {
                            menuExpanded = false
                            onRequestToggleRepoVisibility(repo)
                          },
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
                            menuExpanded = false
                            onRequestDeleteRepo(repo)
                          },
                        )
                      }
                    }
                  }
                  HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                  )
                }
              }
            }
          }
        }
      },
      confirmButton = {
        TextButton(onClick = onDismissRepoPicker) {
          Text(stringResource(R.string.action_cancel))
        }
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
