package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.EntryType
import com.example.data.model.RepoEntry
import com.example.ui.theme.CodeTextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileEditorScreen(
  uiState: GitFilesUiState,
  onTextChange: (String) -> Unit,
  onSaveCommit: (String) -> Unit,
  onDownload: (RepoEntry) -> Unit,
  onReplace: (RepoEntry) -> Unit,
  onDelete: (RepoEntry, String) -> Unit,
  onBack: () -> Unit,
  onClearStatus: () -> Unit,
) {
  val doc = uiState.openedDocument
  val snackbarHostState = remember { SnackbarHostState() }
  var showCommitDialog by remember { mutableStateOf(false) }
  var showDeleteDialog by remember { mutableStateOf(false) }
  var showDiscardDialog by remember { mutableStateOf(false) }

  val hasUnsavedChanges =
    doc != null && doc.textContent != null && uiState.editorText != doc.textContent

  val handleBack = {
    if (hasUnsavedChanges) {
      showDiscardDialog = true
    } else {
      onBack()
    }
  }

  BackHandler { handleBack() }

  LaunchedEffect(uiState.statusMessage) {
    val msg = uiState.statusMessage
    if (msg != null) {
      snackbarHostState.showSnackbar(msg)
      onClearStatus()
    }
  }

  val entryFromDoc =
    doc?.let {
      RepoEntry(
        name = it.name,
        path = it.path,
        sha = it.sha,
        size = it.size,
        type = EntryType.FILE,
      )
    }

  Scaffold(
    contentWindowInsets = WindowInsets.safeDrawing,
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      TopAppBar(
        title = {
          Column {
            Text(
              text = (doc?.name ?: "File") + if (hasUnsavedChanges) " *" else "",
              style = MaterialTheme.typography.titleLarge,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            if (doc != null) {
              Text(
                text = doc.path,
                style = CodeTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        },
        navigationIcon = {
          IconButton(
            onClick = handleBack,
            modifier = Modifier.testTag("editor_back_button"),
          ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
          }
        },
        actions = {
          if (entryFromDoc != null) {
            IconButton(
              onClick = { onDownload(entryFromDoc) },
              modifier = Modifier.testTag("editor_download_button"),
            ) {
              Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.action_download))
            }
            IconButton(
              onClick = { onReplace(entryFromDoc) },
              modifier = Modifier.testTag("editor_replace_button"),
            ) {
              Icon(Icons.Rounded.SwapHoriz, contentDescription = stringResource(R.string.action_replace_file))
            }
            IconButton(
              onClick = { showDeleteDialog = true },
              modifier = Modifier.testTag("editor_delete_button"),
            ) {
              Icon(
                Icons.Rounded.Delete,
                contentDescription = stringResource(R.string.action_delete),
                tint = MaterialTheme.colorScheme.error,
              )
            }
            if (doc.textContent != null) {
              IconButton(
                onClick = { showCommitDialog = true },
                enabled = hasUnsavedChanges && !uiState.isSavingDocument,
                modifier = Modifier.testTag("editor_save_button"),
              ) {
                Icon(Icons.Rounded.Save, contentDescription = stringResource(R.string.action_save))
              }
            }
          }
        },
      )
    },
  ) { innerPadding ->
    Column(modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding()) {
      if (uiState.transferProgress != null) {
        androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
      }
      if (uiState.browserError != null) {
        Surface(
          color = MaterialTheme.colorScheme.errorContainer,
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          shape = MaterialTheme.shapes.medium,
        ) {
          Text(
            text = uiState.browserError,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
          )
        }
      }

      when {
        uiState.isLoadingDocument || uiState.isSavingDocument -> {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
          }
        }
        doc == null -> {
          Box(modifier = Modifier.fillMaxSize())
        }
        doc.isBinary || doc.isTooLargeForEditor || doc.textContent == null -> {
          Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center,
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Text(doc.name, style = MaterialTheme.typography.titleLarge)
              Text(
                text = formatBytes(doc.size),
                style = CodeTextStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text =
                  if (doc.isTooLargeForEditor) "File exceeds 1.5 MB editor limit"
                  else stringResource(R.string.binary_file_notice),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (entryFromDoc != null) {
                  FilledTonalButton(onClick = { onDownload(entryFromDoc) }) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_download))
                  }
                  OutlinedButton(onClick = { onReplace(entryFromDoc) }) {
                    Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_replace_file))
                  }
                }
              }
            }
          }
        }
        else -> {
          val lineCount = remember(uiState.editorText) { uiState.editorText.count { it == '\n' } + 1 }
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          Box(
            modifier =
              Modifier.weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(16.dp),
          ) {
            BasicTextField(
              value = uiState.editorText,
              onValueChange = onTextChange,
              textStyle = CodeTextStyle.copy(color = MaterialTheme.colorScheme.onBackground),
              cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
              modifier = Modifier.fillMaxSize().testTag("file_editor_textfield"),
            )
          }
          HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
          Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "$lineCount lines • ${formatBytes(uiState.editorText.encodeToByteArray().size.toLong())}",
              style = CodeTextStyle,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hasUnsavedChanges) {
              Button(
                onClick = { showCommitDialog = true },
                modifier = Modifier.testTag("editor_commit_footer_button"),
              ) {
                Text(stringResource(R.string.action_save))
              }
            }
          }
        }
      }
    }
  }

  if (showCommitDialog && doc != null) {
    CommitFileDialog(
      fileName = doc.name,
      onDismiss = { showCommitDialog = false },
      onConfirm = { msg ->
        showCommitDialog = false
        onSaveCommit(msg)
      },
    )
  }

  if (showDeleteDialog && entryFromDoc != null) {
    DeleteConfirmDialog(
      entry = entryFromDoc,
      onDismiss = { showDeleteDialog = false },
      onConfirm = { msg ->
        showDeleteDialog = false
        onDelete(entryFromDoc, msg)
      },
    )
  }

  if (showDiscardDialog) {
    AlertDialog(
      onDismissRequest = { showDiscardDialog = false },
      title = { Text("Discard unsaved changes?") },
      confirmButton = {
        Button(
          onClick = {
            showDiscardDialog = false
            onBack()
          }
        ) {
          Text("Discard")
        }
      },
      dismissButton = {
        TextButton(onClick = { showDiscardDialog = false }) {
          Text(stringResource(R.string.action_cancel))
        }
      },
    )
  }
}
