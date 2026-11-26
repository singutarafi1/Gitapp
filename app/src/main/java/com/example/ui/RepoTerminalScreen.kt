package com.example.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Terminal
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.CodeTextStyle
import com.example.ui.theme.TerminalBackground
import com.example.ui.theme.TerminalBorder
import com.example.ui.theme.TerminalDir
import com.example.ui.theme.TerminalError
import com.example.ui.theme.TerminalKeyCap
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
    QuickTerminalChip("ls -la", "ls -la", executeImmediately = true),
    QuickTerminalChip("cd ..", "cd ..", executeImmediately = true),
    QuickTerminalChip("tree", "tree", executeImmediately = true),
    QuickTerminalChip("git status", "git status", executeImmediately = true),
    QuickTerminalChip("git log", "git log", executeImmediately = true),
    QuickTerminalChip("git show", "git show", executeImmediately = true),
    QuickTerminalChip("git branch", "git branch", executeImmediately = true),
    QuickTerminalChip("grep …", "grep -i ", executeImmediately = false),
    QuickTerminalChip("cat …", "cat ", executeImmediately = false),
    QuickTerminalChip("touch …", "touch ", executeImmediately = false),
    QuickTerminalChip("mkdir …", "mkdir ", executeImmediately = false),
    QuickTerminalChip("cp …", "cp ", executeImmediately = false),
    QuickTerminalChip("mv …", "mv ", executeImmediately = false),
    QuickTerminalChip("rm -rf …", "rm -rf ", executeImmediately = false),
    QuickTerminalChip("termux", "termux", executeImmediately = true),
    QuickTerminalChip("help", "help", executeImmediately = true),
  )

private data class ExtraKeySpec(
  val label: String,
  val testTag: String,
  val accent: Boolean = false,
  val action: ExtraKeyAction,
)

private sealed interface ExtraKeyAction {
  data object TabComplete : ExtraKeyAction
  data object CtrlC : ExtraKeyAction
  data object CtrlL : ExtraKeyAction
  data object HistoryUp : ExtraKeyAction
  data object HistoryDown : ExtraKeyAction
  data object CursorLeft : ExtraKeyAction
  data object CursorRight : ExtraKeyAction
  data class InsertText(val text: String) : ExtraKeyAction
}

private val EXTRA_KEYS =
  listOf(
    ExtraKeySpec("TAB", "extra_key_tab", accent = true, ExtraKeyAction.TabComplete),
    ExtraKeySpec("^C", "extra_key_ctrl_c", accent = true, ExtraKeyAction.CtrlC),
    ExtraKeySpec("/", "extra_key_slash", false, ExtraKeyAction.InsertText("/")),
    ExtraKeySpec("-", "extra_key_dash", false, ExtraKeyAction.InsertText("-")),
    ExtraKeySpec("~", "extra_key_tilde", false, ExtraKeyAction.InsertText("~")),
    ExtraKeySpec("↑", "extra_key_up", false, ExtraKeyAction.HistoryUp),
    ExtraKeySpec("↓", "extra_key_down", false, ExtraKeyAction.HistoryDown),
    ExtraKeySpec("←", "extra_key_left", false, ExtraKeyAction.CursorLeft),
    ExtraKeySpec("→", "extra_key_right", false, ExtraKeyAction.CursorRight),
    ExtraKeySpec("&&", "extra_key_and", false, ExtraKeyAction.InsertText(" && ")),
    ExtraKeySpec(">", "extra_key_redirect", false, ExtraKeyAction.InsertText(" > ")),
    ExtraKeySpec("\"", "extra_key_quote", false, ExtraKeyAction.InsertText("\"")),
    ExtraKeySpec("^L", "extra_key_ctrl_l", accent = true, ExtraKeyAction.CtrlL),
  )

@Composable
fun RepoTerminalScreen(
  uiState: GitFilesUiState,
  onInputChange: (String) -> Unit,
  onRunCommand: (String?) -> Unit,
  onTabComplete: () -> Unit,
  onInterruptOrClear: () -> Unit,
  onClearOutput: () -> Unit,
  onHistoryUp: () -> Unit,
  onHistoryDown: () -> Unit,
  onClearClipboardEvent: () -> Unit,
  onClearLaunchTermuxEvent: () -> Unit,
  onClearStatus: () -> Unit,
  onBack: () -> Unit,
) {
  val session = uiState.session ?: return
  val context = LocalContext.current
  val density = LocalDensity.current
  val listState = rememberLazyListState()
  val snackbarHostState = remember { SnackbarHostState() }
  val clipboardManager = LocalClipboardManager.current
  val focusRequester = remember { FocusRequester() }
  val keyboardController = LocalSoftwareKeyboardController.current
  val tapInteractionSource = remember { MutableInteractionSource() }
  var showQuickCommands by remember { mutableStateOf(false) }

  var commandFieldValue by remember {
    mutableStateOf(
      TextFieldValue(
        text = uiState.terminalCommandInput,
        selection = TextRange(uiState.terminalCommandInput.length),
      )
    )
  }

  val imeBottomPx = WindowInsets.ime.getBottom(density)

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

  LaunchedEffect(uiState.terminalLines.size, imeBottomPx) {
    if (uiState.terminalLines.isNotEmpty()) {
      listState.scrollToItem(uiState.terminalLines.lastIndex)
    }
  }

  LaunchedEffect(uiState.terminalClipboardCopy) {
    val copyText = uiState.terminalClipboardCopy
    if (copyText != null) {
      clipboardManager.setText(AnnotatedString(copyText))
      onClearClipboardEvent()
    }
  }

  LaunchedEffect(uiState.terminalLaunchTermuxCommand) {
    val cmd = uiState.terminalLaunchTermuxCommand
    if (cmd != null) {
      onClearLaunchTermuxEvent()
      val launchIntent = context.packageManager.getLaunchIntentForPackage("com.termux")
      if (launchIntent != null) {
        runCatching { context.startActivity(launchIntent) }
          .onFailure {
            Toast.makeText(context, "Could not launch Termux; command copied.", Toast.LENGTH_SHORT).show()
          }
      } else {
        snackbarHostState.showSnackbar("Copied command. Termux app is not installed on this device.")
      }
    }
  }

  LaunchedEffect(uiState.statusMessage) {
    val msg = uiState.statusMessage
    if (msg != null) {
      snackbarHostState.showSnackbar(msg)
      onClearStatus()
    }
  }

  val displayPath = if (uiState.terminalDir.isEmpty()) "~" else "~/${uiState.terminalDir}"

  val requestTerminalKeyboard: () -> Unit = {
    runCatching {
      focusRequester.requestFocus()
      keyboardController?.show()
    }
  }

  val handleExtraKey: (ExtraKeyAction) -> Unit = { action ->
    when (action) {
      ExtraKeyAction.TabComplete -> {
        onTabComplete()
        requestTerminalKeyboard()
      }
      ExtraKeyAction.CtrlC -> {
        onInterruptOrClear()
        requestTerminalKeyboard()
      }
      ExtraKeyAction.CtrlL -> {
        onClearOutput()
        requestTerminalKeyboard()
      }
      ExtraKeyAction.HistoryUp -> {
        onHistoryUp()
        requestTerminalKeyboard()
      }
      ExtraKeyAction.HistoryDown -> {
        onHistoryDown()
        requestTerminalKeyboard()
      }
      ExtraKeyAction.CursorLeft -> {
        val pos = (commandFieldValue.selection.start - 1).coerceAtLeast(0)
        commandFieldValue = commandFieldValue.copy(selection = TextRange(pos))
        requestTerminalKeyboard()
      }
      ExtraKeyAction.CursorRight -> {
        val pos = (commandFieldValue.selection.end + 1).coerceAtMost(commandFieldValue.text.length)
        commandFieldValue = commandFieldValue.copy(selection = TextRange(pos))
        requestTerminalKeyboard()
      }
      is ExtraKeyAction.InsertText -> {
        val current = commandFieldValue
        val start = current.selection.min.coerceIn(0, current.text.length)
        val end = current.selection.max.coerceIn(start, current.text.length)
        val updatedText = current.text.replaceRange(start, end, action.text)
        val newCursor = start + action.text.length
        commandFieldValue = TextFieldValue(text = updatedText, selection = TextRange(newCursor))
        onInputChange(updatedText)
        requestTerminalKeyboard()
      }
    }
  }

  Scaffold(
    containerColor = TerminalBackground,
    contentWindowInsets = WindowInsets.safeDrawing.exclude(WindowInsets.ime),
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      Surface(
        color = TerminalSurface,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
          Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            IconButton(
              onClick = onBack,
              modifier = Modifier.testTag("terminal_back_button"),
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = TerminalText,
              )
            }
            Icon(
              imageVector = Icons.Rounded.Terminal,
              contentDescription = null,
              tint = TerminalSuccess,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "${session.repo} [${session.branch}]",
                style = MaterialTheme.typography.titleSmall,
                color = TerminalText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                text = displayPath,
                style = CodeTextStyle.copy(fontSize = 11.sp, lineHeight = 14.sp),
                color = TerminalDir,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
            IconButton(
              onClick = { showQuickCommands = !showQuickCommands },
              modifier = Modifier.testTag("terminal_toggle_cmds_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.Code,
                contentDescription = stringResource(R.string.terminal_toggle_cmds),
                tint = if (showQuickCommands) TerminalSuccess else TerminalText,
              )
            }
            IconButton(
              onClick = { onRunCommand("termux") },
              enabled = !uiState.isRunningTerminalCommand,
              modifier = Modifier.testTag("terminal_open_termux_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.OpenInNew,
                contentDescription = stringResource(R.string.terminal_open_termux),
                tint = TerminalText,
              )
            }
            IconButton(
              onClick = requestTerminalKeyboard,
              modifier = Modifier.testTag("terminal_keyboard_button"),
            ) {
              Icon(
                imageVector = Icons.Rounded.Keyboard,
                contentDescription = stringResource(R.string.terminal_show_keyboard),
                tint = TerminalText,
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
                tint = TerminalText,
              )
            }
          }
          if (uiState.isRunningTerminalCommand) {
            LinearProgressIndicator(
              color = TerminalSuccess,
              trackColor = TerminalSurface,
              modifier = Modifier.fillMaxWidth(),
            )
          } else {
            HorizontalDivider(color = TerminalBorder)
          }
        }
      }
    },
  ) { innerPadding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(innerPadding)
          .consumeWindowInsets(innerPadding)
          .imePadding()
          .background(TerminalBackground),
    ) {
      // Scrollable Termux console viewport
      Box(
        modifier =
          Modifier.weight(1f)
            .fillMaxWidth()
            .clickable(
              interactionSource = tapInteractionSource,
              indication = null,
            ) { requestTerminalKeyboard() },
      ) {
        SelectionContainer(modifier = Modifier.fillMaxSize()) {
          LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag("terminal_output_list"),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
          ) {
            items(uiState.terminalLines, key = { it.id }) { line ->
              val color =
                when (line.kind) {
                  TerminalLineKind.COMMAND -> TerminalSuccess
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
      }

      // Optional toggleable quick-command strip
      AnimatedVisibility(visible = showQuickCommands) {
        Column(modifier = Modifier.fillMaxWidth().background(TerminalSurface)) {
          HorizontalDivider(color = TerminalBorder)
          Row(
            modifier =
              Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
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
                label = { Text(chip.label, style = CodeTextStyle.copy(fontSize = 12.sp)) },
                colors =
                  SuggestionChipDefaults.suggestionChipColors(
                    containerColor = TerminalKeyCap,
                    labelColor = TerminalText,
                  ),
                border = SuggestionChipDefaults.suggestionChipBorder(enabled = true, borderColor = TerminalBorder),
                modifier = Modifier.testTag("quick_cmd_${chip.label}"),
              )
            }
          }
        }
      }

      HorizontalDivider(color = TerminalBorder)

      // Inline Termux Prompt Line (`~/path $ <command>`)
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .background(TerminalBackground)
            .heightIn(min = 48.dp)
            .clickable(
              interactionSource = tapInteractionSource,
              indication = null,
            ) { requestTerminalKeyboard() }
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "$displayPath $",
          style = CodeTextStyle.copy(fontWeight = FontWeight.Bold),
          color = TerminalSuccess,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
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
          cursorBrush = SolidColor(TerminalSuccess),
          keyboardOptions =
            KeyboardOptions(
              capitalization = KeyboardCapitalization.None,
              autoCorrectEnabled = false,
              keyboardType = KeyboardType.Ascii,
              imeAction = ImeAction.Done,
            ),
          keyboardActions =
            KeyboardActions(
              onDone = { onRunCommand(null) },
              onSend = { onRunCommand(null) },
            ),
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
              modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp),
              contentAlignment = Alignment.CenterStart,
            ) {
              if (commandFieldValue.text.isEmpty()) {
                Text(
                  text = stringResource(R.string.terminal_hint),
                  style = CodeTextStyle.copy(fontSize = 12.sp),
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
            imageVector = Icons.AutoMirrored.Rounded.KeyboardReturn,
            contentDescription = stringResource(R.string.action_run),
            tint =
              if (!uiState.isRunningTerminalCommand && uiState.terminalCommandInput.isNotBlank()) {
                TerminalSuccess
              } else {
                TerminalMuted
              },
          )
        }
      }

      HorizontalDivider(color = TerminalBorder)

      // Termux Extra Keys Bar pinned directly above the keyboard
      Row(
        modifier =
          Modifier.fillMaxWidth()
            .height(48.dp)
            .background(TerminalSurface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        EXTRA_KEYS.forEach { key ->
          Surface(
            color = TerminalKeyCap,
            shape = RoundedCornerShape(6.dp),
            modifier =
              Modifier.fillMaxHeight()
                .widthIn(min = 48.dp)
                .clickable(enabled = !uiState.isRunningTerminalCommand || key.action is ExtraKeyAction.CtrlC) {
                  handleExtraKey(key.action)
                }
                .testTag(key.testTag),
          ) {
            Box(
              modifier = Modifier.padding(horizontal = 10.dp),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                text = key.label,
                style = CodeTextStyle.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = if (key.accent) TerminalSuccess else TerminalText,
                maxLines = 1,
              )
            }
          }
        }
      }
    }
  }
}
