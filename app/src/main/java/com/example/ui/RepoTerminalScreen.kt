package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.CodeTextStyle
import com.example.ui.theme.TerminalBackground
import com.example.ui.theme.TerminalBorder
import com.example.ui.theme.TerminalDir
import com.example.ui.theme.TerminalError
import com.example.ui.theme.TerminalMuted
import com.example.ui.theme.TerminalPrompt
import com.example.ui.theme.TerminalSuccess
import com.example.ui.theme.TerminalSurface
import com.example.ui.theme.TerminalText
import com.example.ui.theme.TerminalWarning
import kotlinx.coroutines.delay

private data class QuickTerminalChip(
  val label: String,
  val command: String,
  val executeImmediately: Boolean,
)

private val QUICK_COMMANDS =
  listOf(
    QuickTerminalChip("ls", "ls", executeImmediately = true),
    QuickTerminalChip("cd ..", "cd ..", executeImmediately = true),
    QuickTerminalChip("pwd", "pwd", executeImmediately = true),
    QuickTerminalChip("tree", "tree", executeImmediately = true),
    QuickTerminalChip("git log", "git log", executeImmediately = true),
    QuickTerminalChip("git branch", "git branch", executeImmediately = true),
    QuickTerminalChip("git status", "git status", executeImmediately = true),
    QuickTerminalChip("cat …", "cat ", executeImmediately = false),
    QuickTerminalChip("cd …", "cd ", executeImmediately = false),
    QuickTerminalChip("find …", "find ", executeImmediately = false),
    QuickTerminalChip("touch …", "touch ", executeImmediately = false),
    QuickTerminalChip("mkdir …", "mkdir ", executeImmediately = false),
    QuickTerminalChip("mv …", "mv ", executeImmediately = false),
    QuickTerminalChip("rm -rf …", "rm -rf ", executeImmediately = false),
    QuickTerminalChip("help", "help", executeImmediately = true),
  )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RepoTerminalScreen(
  uiState: GitFilesUiState,
  onInputChange: (String) -> Unit,
  onRunCommand: (String?) -> Unit,
  onClearOutput: () -> Unit,
  onHistoryUp: () -> Unit,
  onHistoryDown: () -> Unit,
  onClearClipboardEvent: () -> Unit,
  onClearStatus: () -> Unit,
  onBack: () -> Unit,
) {
  val session = uiState.session ?: return
  val listState = rememberLazyListState()
  val snackbarHostState = remember { SnackbarHostState() }
  val clipboardManager = LocalClipboardManager.current
  val focusRequester = remember { FocusRequester() }
  val keyboardController = LocalSoftwareKeyboardController.current
  val inputInteractionSource = remember { MutableInteractionSource() }

  var commandFieldValue by remember {
    mutableStateOf(
      TextFieldValue(
        text = uiState.terminalCommandInput,
        selection = TextRange(uiState.terminalCommandInput.length),
      )
    )
  }

  LaunchedEffect(Unit) {
    delay(120L)
    runCatching {
      focusRequester.requestFocus()
      keyboardController?.show()
    }
  }

  LaunchedEffect(uiState.terminalCommandInput) {
    if (uiState.terminalCommandInput != commandFieldValue.text) {
      commandFieldValue =
        TextFieldValue(
          text = uiState.terminalCommandInput,
          selection = TextRange(uiState.terminalCommandInput.length),
        )
    }
  }

  BackHandler { onBack() }

  LaunchedEffect(uiState.terminalLines.size) {
    if (uiState.terminalLines.isNotEmpty()) {
      listState.animateScrollToItem(uiState.terminalLines.lastIndex)
    }
  }

  LaunchedEffect(uiState.terminalClipboardCopy) {
    val copyText = uiState.terminalClipboardCopy
    if (copyText != null) {
      clipboardManager.setText(AnnotatedString(copyText))
      onClearClipboardEvent()
    }
  }

  LaunchedEffect(uiState.statusMessage) {
    val msg = uiState.statusMessage
    if (msg != null) {
      snackbarHostState.showSnackbar(msg)
      onClearStatus()
    }
  }

  val displayPath = if (uiState.terminalDir.isEmpty()) "/" else "/${uiState.terminalDir}"

  val requestTerminalKeyboard: () -> Unit = {
    runCatching {
      focusRequester.requestFocus()
      keyboardController?.show()
    }
  }

  Scaffold(
    containerColor = TerminalBackground,
    contentWindowInsets = WindowInsets.safeDrawing,
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      TopAppBar(
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = TerminalSurface,
            titleContentColor = TerminalText,
            navigationIconContentColor = TerminalText,
            actionIconContentColor = TerminalText,
          ),
        navigationIcon = {
          IconButton(
            onClick = onBack,
            modifier = Modifier.testTag("terminal_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
              contentDescription = "Back",
            )
          }
        },
        title = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.Rounded.Terminal,
              contentDescription = null,
              tint = TerminalPrompt,
              modifier = Modifier.size(20.dp),
            )
            Column {
              Text(
                text = session.fullName,
                style = MaterialTheme.typography.titleMedium,
                color = TerminalText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                text = "${session.branch} • $displayPath",
                style = CodeTextStyle,
                color = TerminalDir,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        },
        actions = {
          IconButton(
            onClick = requestTerminalKeyboard,
            modifier = Modifier.testTag("terminal_keyboard_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.Keyboard,
              contentDescription = stringResource(R.string.terminal_show_keyboard),
            )
          }
          IconButton(
            onClick = onClearOutput,
            enabled = !uiState.isRunningTerminalCommand,
            modifier = Modifier.testTag("terminal_clear_button"),
          ) {
            Icon(
              imageVector = Icons.Rounded.DeleteSweep,
              contentDescription = stringResource(R.string.terminal_clear),
            )
          }
        },
      )
    },
  ) { innerPadding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .imePadding()
          .background(TerminalBackground),
    ) {
      if (uiState.isRunningTerminalCommand) {
        LinearProgressIndicator(
          color = TerminalPrompt,
          trackColor = TerminalSurface,
          modifier = Modifier.fillMaxWidth(),
        )
      } else {
        HorizontalDivider(color = TerminalBorder)
      }

      SelectionContainer(
        modifier = Modifier.weight(1f).fillMaxWidth(),
      ) {
        LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize().testTag("terminal_output_list"),
          contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
          verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
          items(uiState.terminalLines, key = { it.id }) { line ->
            val color =
              when (line.kind) {
                TerminalLineKind.COMMAND -> TerminalPrompt
                TerminalLineKind.OUTPUT -> TerminalText
                TerminalLineKind.DIRECTORY -> TerminalDir
                TerminalLineKind.SUCCESS -> TerminalSuccess
                TerminalLineKind.WARNING -> TerminalWarning
                TerminalLineKind.ERROR -> TerminalError
                TerminalLineKind.MUTED -> TerminalMuted
              }
            Text(
              text = line.text,
              style =
                if (line.kind == TerminalLineKind.COMMAND) {
                  CodeTextStyle.copy(fontWeight = FontWeight.SemiBold)
                } else {
                  CodeTextStyle
                },
              color = color,
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      }

      HorizontalDivider(color = TerminalBorder)

      // Quick command shortcuts & history navigation
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .background(TerminalSurface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          QUICK_COMMANDS.forEach { chip ->
            SuggestionChip(
              onClick = {
                if (chip.executeImmediately) {
                  onRunCommand(chip.command)
                } else {
                  onInputChange(chip.command)
                  requestTerminalKeyboard()
                }
              },
              enabled = !uiState.isRunningTerminalCommand,
              label = { Text(chip.label, style = CodeTextStyle) },
              colors =
                SuggestionChipDefaults.suggestionChipColors(
                  containerColor = TerminalBackground,
                  labelColor = TerminalText,
                ),
              border = SuggestionChipDefaults.suggestionChipBorder(enabled = true, borderColor = TerminalBorder),
              modifier = Modifier.testTag("quick_cmd_${chip.label}"),
            )
          }
        }
        IconButton(
          onClick = {
            onHistoryUp()
            requestTerminalKeyboard()
          },
          enabled = !uiState.isRunningTerminalCommand && uiState.terminalHistory.isNotEmpty(),
          modifier = Modifier.testTag("terminal_history_up"),
        ) {
          Icon(
            imageVector = Icons.Rounded.KeyboardArrowUp,
            contentDescription = "Previous command",
            tint = TerminalText,
          )
        }
        IconButton(
          onClick = {
            onHistoryDown()
            requestTerminalKeyboard()
          },
          enabled = !uiState.isRunningTerminalCommand && uiState.terminalHistory.isNotEmpty(),
          modifier = Modifier.testTag("terminal_history_down"),
        ) {
          Icon(
            imageVector = Icons.Rounded.KeyboardArrowDown,
            contentDescription = "Next command",
            tint = TerminalText,
          )
        }
      }

      HorizontalDivider(color = TerminalBorder)

      // Bottom Command Prompt Bar
      Surface(
        color = TerminalSurface,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "$displayPath$",
            style = CodeTextStyle.copy(fontWeight = FontWeight.Bold),
            color = TerminalSuccess,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
              Modifier.clickable(
                interactionSource = inputInteractionSource,
                indication = null,
              ) { requestTerminalKeyboard() },
          )
          Spacer(modifier = Modifier.width(8.dp))
          BasicTextField(
            value = commandFieldValue,
            onValueChange = { updated ->
              commandFieldValue = updated
              if (updated.text != uiState.terminalCommandInput) {
                onInputChange(updated.text)
              }
            },
            enabled = !uiState.isRunningTerminalCommand,
            singleLine = true,
            textStyle = CodeTextStyle.copy(color = TerminalText),
            cursorBrush = SolidColor(TerminalPrompt),
            keyboardOptions =
              KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Send,
              ),
            keyboardActions = KeyboardActions(onSend = { onRunCommand(null) }),
            modifier =
              Modifier.weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { focusState ->
                  if (focusState.isFocused) {
                    keyboardController?.show()
                  }
                }
                .testTag("terminal_input"),
            decorationBox = { innerTextField ->
              Box(
                modifier =
                  Modifier.fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(TerminalBackground, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
              ) {
                if (commandFieldValue.text.isEmpty()) {
                  Text(
                    text = stringResource(R.string.terminal_hint),
                    style = CodeTextStyle,
                    color = TerminalMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
                innerTextField()
              }
            },
          )
          IconButton(
            onClick = { onRunCommand(null) },
            enabled = !uiState.isRunningTerminalCommand && uiState.terminalCommandInput.isNotBlank(),
            modifier = Modifier.testTag("terminal_run_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.Send,
              contentDescription = stringResource(R.string.action_run),
              tint =
                if (!uiState.isRunningTerminalCommand && uiState.terminalCommandInput.isNotBlank()) {
                  TerminalPrompt
                } else {
                  TerminalMuted
                },
            )
          }
        }
      }
    }
  }
}
