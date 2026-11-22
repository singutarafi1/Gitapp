package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.SavedToken
import com.example.data.local.maskToken
import com.example.data.model.EntryType
import com.example.data.model.RepoEntry
import com.example.data.model.RepoSummary
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
      val totalBytes = remember(pending.selection.files) {
        pending.selection.files.sumOf { it.size.coerceAtLeast(0L) }
      }
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          text = "${pending.selection.files.size} files (${formatBytes(totalBytes)}) -> $resolvedTarget",
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

@Composable
fun CreateRepoDialog(
  onDismiss: () -> Unit,
  onConfirm: (name: String, description: String, isPrivate: Boolean, autoInit: Boolean) -> Unit,
) {
  var repoName by remember { mutableStateOf("") }
  var description by remember { mutableStateOf("") }
  var isPrivate by remember { mutableStateOf(false) }
  var autoInit by remember { mutableStateOf(true) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.action_new_repo)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = repoName,
          onValueChange = { repoName = it },
          label = { Text(stringResource(R.string.label_repo_name)) },
          placeholder = { Text("my-new-repo") },
          singleLine = true,
          textStyle = CodeTextStyle,
          modifier = Modifier.fillMaxWidth().testTag("create_repo_name_input"),
        )
        OutlinedTextField(
          value = description,
          onValueChange = { description = it },
          label = { Text(stringResource(R.string.label_description_optional)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("create_repo_description_input"),
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = stringResource(R.string.label_private_repo),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
          )
          Switch(
            checked = isPrivate,
            onCheckedChange = { isPrivate = it },
            modifier = Modifier.testTag("create_repo_private_switch"),
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = stringResource(R.string.label_init_readme),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
          )
          Switch(
            checked = autoInit,
            onCheckedChange = { autoInit = it },
            modifier = Modifier.testTag("create_repo_init_readme_switch"),
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirm(repoName, description, isPrivate, autoInit) },
        enabled = repoName.trim().isNotEmpty(),
        modifier = Modifier.testTag("confirm_create_repo_button"),
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
fun ToggleRepoVisibilityDialog(
  repo: RepoSummary,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  val makePrivate = !repo.isPrivate
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        if (makePrivate) stringResource(R.string.action_make_private)
        else stringResource(R.string.action_make_public)
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = repo.fullName,
          style = CodeTextStyle.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
          text =
            if (makePrivate) {
              "This repository will become Private and will only be visible to you and collaborators with access."
            } else {
              "This repository will become Public and anyone on the internet will be able to see it."
            },
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = {
      Button(
        onClick = onConfirm,
        modifier = Modifier.testTag("confirm_toggle_repo_visibility_button"),
      ) {
        Text(
          if (makePrivate) stringResource(R.string.action_make_private)
          else stringResource(R.string.action_make_public)
        )
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    },
  )
}

@Composable
fun DeleteRepoConfirmDialog(
  repo: RepoSummary,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  val shortName = repo.fullName.substringAfter('/')
  var confirmText by remember(repo.fullName) { mutableStateOf("") }
  val canDelete = confirmText.trim() == shortName || confirmText.trim() == repo.fullName

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(R.string.action_delete_repo)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
          text = repo.fullName,
          style = CodeTextStyle.copy(fontWeight = FontWeight.SemiBold),
          color = MaterialTheme.colorScheme.error,
        )
        Text(
          text = "This permanently deletes the repository, branches, and all files on GitHub. This cannot be undone.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
          value = confirmText,
          onValueChange = { confirmText = it },
          label = { Text("Type $shortName to confirm") },
          singleLine = true,
          textStyle = CodeTextStyle,
          modifier = Modifier.fillMaxWidth().testTag("delete_repo_confirm_input"),
        )
      }
    },
    confirmButton = {
      Button(
        onClick = onConfirm,
        enabled = canDelete,
        colors =
          ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
          ),
        modifier = Modifier.testTag("confirm_delete_repo_button"),
      ) {
        Text(stringResource(R.string.action_delete_forever))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    },
  )
}

@Composable
fun SavedTokensDialog(
  savedTokens: List<SavedToken>,
  activeToken: String,
  onDismiss: () -> Unit,
  onSelect: (SavedToken) -> Unit,
  onDelete: (SavedToken) -> Unit,
  onOpenAddToken: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(stringResource(R.string.label_saved_tokens))
        TextButton(
          onClick = onOpenAddToken,
          modifier = Modifier.testTag("saved_tokens_add_button"),
        ) {
          Icon(
            imageVector = Icons.Rounded.Add,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(4.dp))
          Text(stringResource(R.string.action_add_token))
        }
      }
    },
    text = {
      if (savedTokens.isEmpty()) {
        Text(
          text = "No saved tokens yet. Tap Add Token to store multiple GitHub tokens.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
          items(savedTokens, key = { it.id }) { item ->
            val isSelected = item.token == activeToken.trim()
            Row(
              modifier =
                Modifier.fillMaxWidth()
                  .clickable { onSelect(item) }
                  .padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
                  .testTag("saved_token_row_${item.id}"),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                imageVector = if (isSelected) Icons.Rounded.Check else Icons.Rounded.Key,
                contentDescription = if (isSelected) "Active" else "Token",
                tint =
                  if (isSelected) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
              )
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text =
                    if (item.username.isNotBlank() && item.label != "@${item.username}") {
                      "${item.label} (@${item.username})"
                    } else {
                      item.label
                    },
                  style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Text(
                  text = maskToken(item.token),
                  style = CodeTextStyle,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
              IconButton(
                onClick = { onDelete(item) },
                modifier = Modifier.testTag("delete_saved_token_${item.id}"),
              ) {
                Icon(
                  imageVector = Icons.Rounded.Delete,
                  contentDescription = stringResource(R.string.action_delete),
                  tint = MaterialTheme.colorScheme.error,
                )
              }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
    },
  )
}

@Composable
fun AddTokenDialog(
  isSaving: Boolean,
  errorMessage: String?,
  onDismiss: () -> Unit,
  onSave: (label: String, token: String) -> Unit,
) {
  var label by rememberSaveable { mutableStateOf("") }
  var tokenValue by rememberSaveable { mutableStateOf("") }
  var showToken by rememberSaveable { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = { if (!isSaving) onDismiss() },
    title = { Text(stringResource(R.string.action_add_token)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = label,
          onValueChange = { label = it },
          label = { Text(stringResource(R.string.label_token_label)) },
          placeholder = { Text(stringResource(R.string.hint_token_label)) },
          singleLine = true,
          enabled = !isSaving,
          modifier = Modifier.fillMaxWidth().testTag("add_token_label_input"),
        )
        OutlinedTextField(
          value = tokenValue,
          onValueChange = { tokenValue = it },
          label = { Text(stringResource(R.string.label_github_token)) },
          placeholder = { Text(stringResource(R.string.hint_github_token)) },
          trailingIcon = {
            IconButton(
              onClick = { showToken = !showToken },
              modifier = Modifier.testTag("add_token_visibility_button"),
            ) {
              Icon(
                imageVector = if (showToken) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                contentDescription = if (showToken) "Hide token" else "Show token",
              )
            }
          },
          visualTransformation =
            if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
          singleLine = true,
          textStyle = CodeTextStyle,
          enabled = !isSaving,
          modifier = Modifier.fillMaxWidth().testTag("add_token_value_input"),
        )
        if (errorMessage != null) {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = errorMessage,
              color = MaterialTheme.colorScheme.onErrorContainer,
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.padding(10.dp),
            )
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onSave(label, tokenValue) },
        enabled = !isSaving && tokenValue.trim().isNotEmpty(),
        modifier = Modifier.testTag("confirm_add_token_button"),
      ) {
        if (isSaving) {
          CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary,
          )
          Spacer(modifier = Modifier.width(8.dp))
        }
        Text(stringResource(R.string.action_confirm))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss, enabled = !isSaving) {
        Text(stringResource(R.string.action_cancel))
      }
    },
  )
}
