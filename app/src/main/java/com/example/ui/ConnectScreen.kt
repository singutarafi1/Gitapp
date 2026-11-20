package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.CodeTextStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(
  uiState: GitFilesUiState,
  onTokenChange: (String) -> Unit,
  onRepoUrlChange: (String) -> Unit,
  onBranchChange: (String) -> Unit,
  onConnect: () -> Unit,
  onBackToBrowser: () -> Unit,
  onDisconnect: () -> Unit,
) {
  var showToken by rememberSaveable { mutableStateOf(false) }
  val hasActiveSession = uiState.session != null

  if (hasActiveSession) {
    BackHandler { onBackToBrowser() }
  }

  Scaffold(
    contentWindowInsets = WindowInsets.safeDrawing,
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

        OutlinedTextField(
          value = uiState.repoUrlInput,
          onValueChange = onRepoUrlChange,
          label = { Text(stringResource(R.string.label_repo_url)) },
          placeholder = { Text(stringResource(R.string.hint_repo_url)) },
          leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
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
          enabled = !uiState.isConnecting,
          modifier = Modifier.fillMaxWidth().height(50.dp).testTag("connect_button"),
          shape = RoundedCornerShape(12.dp),
        ) {
          if (uiState.isConnecting) {
            CircularProgressIndicator(
              modifier = Modifier.size(20.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(modifier = Modifier.width(10.dp))
          }
          Text(stringResource(R.string.action_connect))
        }

        if (hasActiveSession) {
          OutlinedButton(
            onClick = onDisconnect,
            enabled = !uiState.isConnecting,
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("disconnect_button"),
            shape = RoundedCornerShape(12.dp),
          ) {
            Text(stringResource(R.string.action_disconnect))
          }
        }
      }
    }
  }
}
