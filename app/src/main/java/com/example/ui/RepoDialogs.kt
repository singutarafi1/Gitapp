package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.EntryType
import com.example.data.model.RepoEntry
import com.example.ui.theme.CodeTextStyle

@Composable
fun NewFileDialog(
  onDismiss: () -> Unit,
  onConfirm: (fileName: String, content: String, commitMessage: String) -> Unit,
) {
  var fileName by remember { mutableStateOf("") }
  var content by remember { mutableStateOf("") }
  var commitMessage by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.action_new_file)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = fileName,
          onValueChange = { fileName = it },
          label = { Text(stringResource(R.string.label_file_name)) },
          singleLine = true,
          textStyle = CodeTextStyle,
          modifier = Modifier.fillMaxWidth().testTag("new_file_name_input"),
        )
        OutlinedTextField(
          value = content,
          onValueChange = { content = it },
          label = { Text("Content") },
          textStyle = CodeTextStyle,
          minLines = 4,
          maxLines = 8,
          modifier = Modifier.fillMaxWidth().testTag("new_file_content_input"),
        )
        OutlinedTextField(
          value = commitMessage,
          onValueChange = { commitMessage = it },
          label = { Text(stringResource(R.string.label_commit_message)) },
          placeholder = { Text(if (fileName.isBlank()) "Create file" else "Create ${fileName.trim()}") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("new_file_commit_input"),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirm(fileName, content, commitMessage) },
        enabled = fileName.isNotBlank(),
        modifier = Modifier.testTag("confirm_new_file_button"),
      ) {
        Text(stringResource(R.string.action_confirm))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    },
  )
}

@Composable
fun NewFolderDialog(
  onDismiss: () -> Unit,
  onConfirm: (folderName: String, commitMessage: String) -> Unit,
) {
  var folderName by remember { mutableStateOf("") }
  var commitMessage by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.action_new_folder)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = folderName,
          onValueChange = { folderName = it },
          label = { Text(stringResource(R.string.label_folder_name)) },
          singleLine = true,
          textStyle = CodeTextStyle,
          modifier = Modifier.fillMaxWidth().testTag("new_folder_name_input"),
        )
        OutlinedTextField(
          value = commitMessage,
          onValueChange = { commitMessage = it },
          label = { Text(stringResource(R.string.label_commit_message)) },
          placeholder = {
            Text(if (folderName.isBlank()) "Create folder" else "Create folder ${folderName.trim()}")
          },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("new_folder_commit_input"),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirm(folderName, commitMessage) },
        enabled = folderName.isNotBlank(),
        modifier = Modifier.testTag("confirm_new_folder_button"),
      ) {
        Text(stringResource(R.string.action_confirm))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    },
  )
}

@Composable
fun RenameEntryDialog(
  entry: RepoEntry,
  onDismiss: () -> Unit,
  onConfirm: (newName: String, commitMessage: String) -> Unit,
) {
  var newName by remember { mutableStateOf(entry.name) }
  var commitMessage by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.action_rename)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = newName,
          onValueChange = { newName = it },
          label = { Text("Name") },
          singleLine = true,
          textStyle = CodeTextStyle,
          modifier = Modifier.fillMaxWidth().testTag("rename_input"),
        )
        OutlinedTextField(
          value = commitMessage,
          onValueChange = { commitMessage = it },
          label = { Text(stringResource(R.string.label_commit_message)) },
          placeholder = { Text("Rename ${entry.name}") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("rename_commit_input"),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirm(newName, commitMessage) },
        enabled = newName.isNotBlank() && newName.trim() != entry.name,
        modifier = Modifier.testTag("confirm_rename_button"),
      ) {
        Text(stringResource(R.string.action_confirm))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    },
  )
}

@Composable
fun DeleteConfirmDialog(
  entry: RepoEntry,
  onDismiss: () -> Unit,
  onConfirm: (commitMessage: String) -> Unit,
) {
  var commitMessage by remember { mutableStateOf("") }
  val isDir = entry.type == EntryType.DIR

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (isDir) "Delete folder?" else "Delete file?") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          text = entry.path,
          style = CodeTextStyle,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
          value = commitMessage,
          onValueChange = { commitMessage = it },
          label = { Text(stringResource(R.string.label_commit_message)) },
          placeholder = { Text("Delete ${entry.name}") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("delete_commit_input"),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirm(commitMessage) },
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
          ),
        modifier = Modifier.testTag("confirm_delete_button"),
      ) {
        Text(stringResource(R.string.action_delete))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    },
  )
}

@Composable
fun FolderActionConfirmDialog(
  pending: PendingFolderAction,
  currentRepoPath: String,
  onDismiss: () -> Unit,
  onConfirm: (targetFolderName: String, replaceExisting: Boolean, commitMessage: String) -> Unit,
) {
  var targetFolderName by remember { mutableStateOf(pending.initialTargetFolderName) }
  var replaceExisting by remember { mutableStateOf(pending.replaceExisting) }
  var commitMessage by remember { mutableStateOf("") }
  val isFixedTarget = pending.fixedTargetFolderPath != null

  val resolvedTarget =
    pending.fixedTargetFolderPath
      ?: run {
        val clean = targetFolderName.trim().trim('/')
        when {
          clean.isEmpty() -> currentRepoPath.ifEmpty { "/" }
          currentRepoPath.isEmpty() -> clean
          else -> "$currentRepoPath/$clean"
        }
      }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        if (isFixedTarget || replaceExisting) stringResource(R.string.action_replace_folder)
        else stringResource(R.string.action_upload_folder)
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          text = "${pending.selection.files.size} files -> $resolvedTarget",
          style = CodeTextStyle,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!isFixedTarget) {
          OutlinedTextField(
            value = targetFolderName,
            onValueChange = { targetFolderName = it },
            label = { Text(stringResource(R.string.label_folder_name)) },
            singleLine = true,
            textStyle = CodeTextStyle,
            modifier = Modifier.fillMaxWidth().testTag("folder_upload_target_input"),
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = "Replace existing folder",
              style = MaterialTheme.typography.bodyMedium,
              modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Switch(
              checked = replaceExisting,
              onCheckedChange = { replaceExisting = it },
              modifier = Modifier.testTag("replace_folder_switch"),
            )
          }
        }
        OutlinedTextField(
          value = commitMessage,
          onValueChange = { commitMessage = it },
          label = { Text(stringResource(R.string.label_commit_message)) },
          placeholder = {
            Text(if (replaceExisting) "Replace $resolvedTarget" else "Upload $resolvedTarget")
          },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("folder_upload_commit_input"),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirm(targetFolderName, replaceExisting, commitMessage) },
        modifier = Modifier.testTag("confirm_folder_upload_button"),
      ) {
        Text(if (replaceExisting) "Replace" else "Upload")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    },
  )
}

@Composable
fun CommitFileDialog(
  fileName: String,
  onDismiss: () -> Unit,
  onConfirm: (commitMessage: String) -> Unit,
) {
  var commitMessage by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.action_save)) },
    text = {
      OutlinedTextField(
        value = commitMessage,
        onValueChange = { commitMessage = it },
        label = { Text(stringResource(R.string.label_commit_message)) },
        placeholder = { Text("Update $fileName") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("save_file_commit_input"),
      )
    },
    confirmButton = {
      Button(
        onClick = { onConfirm(commitMessage) },
        modifier = Modifier.testTag("confirm_save_file_button"),
      ) {
        Text(stringResource(R.string.action_save))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    },
  )
}

@Composable
fun BranchPickerDialog(
  branches: List<String>,
  currentBranch: String,
  onDismiss: () -> Unit,
  onSelect: (String) -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Branch") },
    text = {
      LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
        items(branches) { branch ->
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .clickable { onSelect(branch) }
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(text = branch, style = CodeTextStyle)
            if (branch == currentBranch) {
              Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
              )
            }
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    },
  )
}
